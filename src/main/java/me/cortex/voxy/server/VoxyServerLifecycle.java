package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.concurrent.atomic.LongAdder;

@EventBusSubscriber(modid = "voxy", value = Dist.DEDICATED_SERVER)
public final class VoxyServerLifecycle {
    private static final LongAdder SEEN_CHUNKS = new LongAdder();
    private static final LongAdder INGESTED_CHUNKS = new LongAdder();
    private static final LongAdder BACKPRESSURE_SKIPS = new LongAdder();

    private VoxyServerLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
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
        Logger.info("Voxy dedicated server companion initialized");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (VoxyCommon.getInstance() instanceof VoxyServerInstance) {
            VoxyCommon.shutdownInstance();
            Logger.info("Voxy dedicated server companion stopped");
        }
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

    public static String getIngestStatus() {
        return "seen=" + SEEN_CHUNKS.sum()
                + ",ingested=" + INGESTED_CHUNKS.sum()
                + ",backpressureSkipped=" + BACKPRESSURE_SKIPS.sum();
    }
}
