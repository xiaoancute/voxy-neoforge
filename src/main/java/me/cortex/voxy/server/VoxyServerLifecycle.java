package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

@EventBusSubscriber(modid = "voxy", value = Dist.DEDICATED_SERVER)
public final class VoxyServerLifecycle {
    private static final LongAdder SEEN_CHUNKS = new LongAdder();
    private static final LongAdder INGESTED_CHUNKS = new LongAdder();
    private static final LongAdder BACKPRESSURE_SKIPS = new LongAdder();
    private static final LongAdder UPDATED_SECTIONS = new LongAdder();
    private static final LongAdder COALESCED_UPDATES = new LongAdder();
    private static final Set<DirtySection> DIRTY_SECTION_SET = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<DirtySection> DIRTY_SECTION_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean UPDATE_FLUSH_SCHEDULED = new AtomicBoolean();

    private VoxyServerLifecycle() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!VoxyCommon.isAvailable()) {
            VoxyCommon.setInstanceFactory(() -> new VoxyServerInstance(event.getServer()));
        }
        if (!VoxyServerConfig.isEnabled()) {
            Logger.info("Voxy dedicated server companion is disabled by configuration");
            return;
        }
        if (VoxyCommon.getInstance() == null) {
            VoxyCommon.createInstance();
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!(VoxyCommon.getInstance() instanceof VoxyServerInstance)) {
            return;
        }
        Logger.info("Voxy dedicated server companion initialized");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VoxyServerNetwork.clearClients();
        DIRTY_SECTION_SET.clear();
        DIRTY_SECTION_QUEUE.clear();
        UPDATE_FLUSH_SCHEDULED.set(false);
        if (VoxyCommon.getInstance() instanceof VoxyServerInstance) {
            VoxyCommon.shutdownInstance();
            Logger.info("Voxy dedicated server companion stopped");
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        VoxyServerNetwork.removeClient(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)
                || !(VoxyCommon.getInstance() instanceof VoxyServerInstance instance)) {
            return;
        }

        boolean shouldIngest = event.isNewChunk()
                ? VoxyServerConfig.ingestGeneratedChunks()
                : VoxyServerConfig.ingestLoadedChunks();
        if (!shouldIngest) {
            return;
        }

        SEEN_CHUNKS.increment();
        level.getServer().execute(() -> {
            if (!instance.isRunning()) {
                return;
            }
            if (instance.getIngestService().getTaskCount() >= VoxyServerConfig.maxIngestQueue()) {
                BACKPRESSURE_SKIPS.increment();
                return;
            }
            if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
                INGESTED_CHUNKS.increment();
            }
        });
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(VoxyServerCommands.register());
    }

    public static void queueBlockUpdate(ServerLevel level, BlockPos pos) {
        if (!(VoxyCommon.getInstance() instanceof VoxyServerInstance instance) || !instance.isRunning()) {
            return;
        }
        DirtySection dirty = new DirtySection(
                level,
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        if (!DIRTY_SECTION_SET.add(dirty)) {
            COALESCED_UPDATES.increment();
            return;
        }
        DIRTY_SECTION_QUEUE.add(dirty);
        scheduleBlockUpdateFlush(level);
    }

    private static void scheduleBlockUpdateFlush(ServerLevel level) {
        if (UPDATE_FLUSH_SCHEDULED.compareAndSet(false, true)) {
            level.getServer().execute(VoxyServerLifecycle::flushBlockUpdates);
        }
    }

    private static void flushBlockUpdates() {
        int remaining = 256;
        try {
            DirtySection dirty;
            while (remaining-- > 0 && (dirty = DIRTY_SECTION_QUEUE.poll()) != null) {
                DIRTY_SECTION_SET.remove(dirty);
                ingestChangedSection(dirty);
            }
        } finally {
            UPDATE_FLUSH_SCHEDULED.set(false);
            DirtySection next = DIRTY_SECTION_QUEUE.peek();
            if (next != null) scheduleBlockUpdateFlush(next.level);
        }
    }

    private static void ingestChangedSection(DirtySection dirty) {
        if (!(VoxyCommon.getInstance() instanceof VoxyServerInstance instance)
                || !instance.isRunning()
                || instance.getIngestService().getTaskCount() >= VoxyServerConfig.maxIngestQueue()) {
            BACKPRESSURE_SKIPS.increment();
            return;
        }
        var access = dirty.level.getChunk(dirty.x, dirty.z, ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return;
        int index = dirty.level.getSectionIndexFromSectionY(dirty.y);
        if (index < 0 || index >= chunk.getSections().length) return;

        SectionPos pos = SectionPos.of(dirty.x, dirty.y, dirty.z);
        var lightEngine = dirty.level.getLightEngine();
        var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
        var skyLight = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
        if (VoxelIngestService.rawIngest(
                WorldIdentifier.of(dirty.level), chunk.getSection(index), dirty.x, dirty.y, dirty.z, blockLight, skyLight)) {
            UPDATED_SECTIONS.increment();
        }
    }

    public static String getIngestStatus() {
        return "seen=" + SEEN_CHUNKS.sum()
                + ",ingested=" + INGESTED_CHUNKS.sum()
                + ",updatedSections=" + UPDATED_SECTIONS.sum()
                + ",coalescedUpdates=" + COALESCED_UPDATES.sum()
                + ",backpressureSkipped=" + BACKPRESSURE_SKIPS.sum();
    }

    private record DirtySection(ServerLevel level, int x, int y, int z) {}
}
