package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world,
                                 PalettedContainer<BlockState> states, Holder<Biome>[] biomes,
                                 DataLayer blockLight, DataLayer skyLight){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        try {
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (!task.states.maybeHas(state -> !state.isAir()) && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
                WorldUpdater.insertUpdate(task.world, vs.zero());
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        SECTION_CACHE.get(),
                        task.world.getMapper(),
                        task.states,
                        task.biomes,
                        getLightingSupplier(task)
                );
                WorldConversionFactory.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
        } finally {
            task.world.releaseRef();
        }
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;
        boolean enqueued = false;

        // MC 1.21.1: LevelChunk.getMinSectionY() → chunk.getLevel().getMinSection()
        int i = chunk.getLevel().getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            // MC 1.21.1: LevelChunk.getMinSectionY() → chunk.getLevel().getMinSection()
            i = chunk.getLevel().getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                if (!this.enqueueSnapshot(snapshot(chunk.getPos().x, i, chunk.getPos().z, engine, section, null, null))) break;
                enqueued = true;
            }
        }

        if (!gotLighting) {
            return enqueued;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        // MC 1.21.1: LevelChunk.getMinSectionY() → chunk.getLevel().getMinSection()
        i = chunk.getLevel().getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            var sl = slp.getDataLayerData(pos);

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            if (!this.enqueueSnapshot(snapshot(chunk.getPos().x, i, chunk.getPos().z, engine, section, bl, sl))) break;
            enqueued = true;
        }
        return enqueued;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        while (!this.ingestQueue.isEmpty()) {
            this.ingestQueue.pop().world.releaseRef();
        }
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return this.enqueueSnapshot(snapshot(x, y, z, engine, section, bl, sl));
    }

    private boolean enqueueSnapshot(IngestSection task) {
        task.world.acquireRef();
        this.ingestQueue.add(task);
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            if (this.ingestQueue.remove(task)) task.world.releaseRef();
            return false;
        }
    }

    private static IngestSection snapshot(int x, int y, int z, WorldEngine engine,
                                          LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
        PalettedContainer<BlockState> states;
        Holder<Biome>[] biomes;
        section.acquire();
        try {
            states = section.getStates().copy();
            biomes = copyBiomes(section);
        } finally {
            section.release();
        }
        return new IngestSection(x, y, z, engine, states, biomes,
                blockLight == null ? null : blockLight.copy(),
                skyLight == null ? null : skyLight.copy());
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] copyBiomes(LevelChunkSection section) {
        Holder<Biome>[] snapshot = (Holder<Biome>[]) new Holder<?>[64];
        int index = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) snapshot[index++] = section.getBiomes().get(x, y, z);
            }
        }
        return snapshot;
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
    }
}
