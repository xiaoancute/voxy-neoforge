package me.cortex.voxy.client.network;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.network.VoxyPayloads;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/** Adds server-backed reads in front of an existing local section store. */
public final class RemoteSectionStorage extends SectionStorage {
    private final WorldIdentifier identifier;
    private final SectionStorage delegate;
    private volatile Mapper mapper;

    public RemoteSectionStorage(WorldIdentifier identifier, SectionStorage delegate) {
        this.identifier = identifier;
        this.delegate = delegate;
    }

    @Override
    public int loadSection(WorldSection into) {
        int local = this.delegate.loadSection(into);
        if (local != 1) return local;

        Mapper activeMapper = this.mapper;
        if (activeMapper == null) return 1;
        VoxyPayloads.Response remote = VoxyClientNetwork.requestSection(this.identifier, into.key);
        if (remote == null || !remote.found()) return 1;

        try {
            Map<Integer, Integer> blockRemap = new HashMap<>();
            Map<Integer, Integer> biomeRemap = new HashMap<>();
            blockRemap.put(0, 0);
            for (var mapping : remote.mappings()) {
                if (mapping.type() == Mapper.BLOCK_STATE_TYPE) {
                    blockRemap.put(mapping.id(), activeMapper.importBlockStateMapping(mapping.id(), mapping.data()));
                } else if (mapping.type() == Mapper.BIOME_TYPE) {
                    biomeRemap.put(mapping.id(), activeMapper.importBiomeMapping(mapping.id(), mapping.data()));
                } else {
                    throw new IllegalArgumentException("Unknown remote mapping type " + mapping.type());
                }
            }

            long[] mapped = remote.data().clone();
            for (int i = 0; i < mapped.length; i++) {
                long value = mapped[i];
                int remoteBlock = Mapper.getBlockId(value);
                if (remoteBlock == 0) {
                    mapped[i] = Mapper.withBlockBiome(value, 0, 0);
                    continue;
                }
                Integer localBlock = blockRemap.get(remoteBlock);
                Integer localBiome = biomeRemap.get(Mapper.getBiomeId(value));
                if (localBlock == null || localBiome == null) {
                    throw new IllegalArgumentException("Remote section omitted a referenced mapping");
                }
                mapped[i] = Mapper.withBlockBiome(value, localBlock, localBiome);
            }

            into.applyRemoteData(mapped, remote.nonEmptyChildren());
            this.delegate.saveSection(into);
            VoxyClientNetwork.recordStored(remote);
            return 0;
        } catch (RuntimeException error) {
            Logger.error("Unable to import remote LOD section " + into.key, error);
            VoxyClientNetwork.recordRejected();
            return 1;
        }
    }

    @Override
    public void setMapper(Mapper mapper) {
        this.mapper = mapper;
        this.delegate.setMapper(mapper);
    }

    @Override public void saveSection(WorldSection section) { this.delegate.saveSection(section); }
    @Override public void putIdMapping(int id, ByteBuffer data) { this.delegate.putIdMapping(id, data); }
    @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return this.delegate.getIdMappingsData(); }
    @Override public void flush() { this.delegate.flush(); }
    @Override public void close() { this.delegate.close(); }
    @Override public void iterateStoredSectionPositions(LongConsumer consumer) { this.delegate.iterateStoredSectionPositions(consumer); }
}
