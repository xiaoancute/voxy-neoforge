package me.cortex.voxy.client.network;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.network.VoxyPayloads;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.LongConsumer;

/** Adds server-backed reads in front of an existing local section store. */
public final class RemoteSectionStorage extends SectionStorage {
    private static final Map<String, CopyOnWriteArraySet<RemoteSectionStorage>> ACTIVE_STORES = new ConcurrentHashMap<>();

    private final WorldIdentifier identifier;
    private final SectionStorage delegate;
    private final Set<Long> loadedKeys = ConcurrentHashMap.newKeySet();
    private final Map<Long, VoxyPayloads.Response> prefetched = new ConcurrentHashMap<>();
    private final Map<Long, Long> invalidationGenerations = new ConcurrentHashMap<>();
    private volatile Mapper mapper;

    public RemoteSectionStorage(WorldIdentifier identifier, SectionStorage delegate) {
        this.identifier = identifier;
        this.delegate = delegate;
        ACTIVE_STORES.computeIfAbsent(identifier.key.location().toString(), ignored -> new CopyOnWriteArraySet<>()).add(this);
    }

    @Override
    public int loadSection(WorldSection into) {
        int local = this.delegate.loadSection(into);
        if (local != 1) {
            if (local == 0) this.loadedKeys.add(into.key);
            return local;
        }

        Mapper activeMapper = this.mapper;
        if (activeMapper == null) return 1;
        VoxyPayloads.Response remote = this.prefetched.remove(into.key);
        if (remote == null) remote = VoxyClientNetwork.requestSection(this.identifier, into.key);
        if (remote == null || !remote.found()) return 1;

        return this.importResponse(into, remote) ? 0 : 1;
    }

    private boolean importResponse(WorldSection into, VoxyPayloads.Response remote) {
        try {
            Mapper activeMapper = this.mapper;
            if (activeMapper == null || remote.key() != into.key
                    || !remote.dimension().equals(this.identifier.key.location().toString())) return false;
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
            this.loadedKeys.add(into.key);
            VoxyClientNetwork.recordStored(remote);
            return true;
        } catch (RuntimeException error) {
            Logger.error("Unable to import remote LOD section " + into.key, error);
            VoxyClientNetwork.recordRejected();
            return false;
        }
    }

    private void invalidate(long key) {
        boolean refresh = this.loadedKeys.remove(key);
        this.prefetched.remove(key);
        this.delegate.deleteSection(key);
        if (refresh || this.invalidationGenerations.containsKey(key)) {
            long generation = this.invalidationGenerations.merge(key, 1L, Long::sum);
            if (refresh) this.scheduleRefresh(key, generation);
        }
    }

    private void scheduleRefresh(long key, long generation) {
        VoxyClientNetwork.refreshSection(this.identifier, key, response -> this.applyRefresh(key, generation, response));
    }

    private void applyRefresh(long key, long generation, VoxyPayloads.Response response) {
        long currentGeneration = this.invalidationGenerations.getOrDefault(key, generation);
        if (currentGeneration != generation) {
            this.scheduleRefresh(key, currentGeneration);
            return;
        }
        if (response == null) {
            this.loadedKeys.add(key);
            this.invalidationGenerations.remove(key, generation);
            return;
        }
        var engine = this.identifier.getNullable();
        if (engine == null || !engine.isLive()) return;
        this.prefetched.put(key, response);
        WorldSection section = engine.acquire(key);
        try {
            VoxyPayloads.Response unapplied = this.prefetched.remove(key);
            if (!response.found()) {
                if (unapplied != null) {
                    long[] air = new long[WorldSection.SECTION_VOLUME];
                    Arrays.fill(air, Mapper.composeMappingId((byte) 15, 0, 0));
                    section.applyRemoteData(air, (byte) 0);
                }
                this.loadedKeys.remove(key);
            } else if (unapplied != null && !this.importResponse(section, unapplied)) {
                return;
            }
            engine.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS | WorldEngine.UPDATE_TYPE_DONT_SAVE, 0);
            this.invalidationGenerations.remove(key, generation);
        } finally {
            section.release();
        }
    }

    public static void handleInvalidation(VoxyPayloads.Invalidate payload) {
        var stores = ACTIVE_STORES.get(payload.dimension());
        if (stores == null) return;
        for (RemoteSectionStorage store : stores) {
            for (long key : payload.keys()) store.invalidate(key);
        }
    }

    @Override
    public void setMapper(Mapper mapper) {
        this.mapper = mapper;
        this.delegate.setMapper(mapper);
    }

    @Override public void saveSection(WorldSection section) { this.delegate.saveSection(section); }
    @Override public void deleteSection(long key) { this.delegate.deleteSection(key); }
    @Override public void putIdMapping(int id, ByteBuffer data) { this.delegate.putIdMapping(id, data); }
    @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return this.delegate.getIdMappingsData(); }
    @Override public void flush() { this.delegate.flush(); }
    @Override public void close() {
        var stores = ACTIVE_STORES.get(this.identifier.key.location().toString());
        if (stores != null) {
            stores.remove(this);
            if (stores.isEmpty()) ACTIVE_STORES.remove(this.identifier.key.location().toString(), stores);
        }
        this.delegate.close();
    }
    @Override public void iterateStoredSectionPositions(LongConsumer consumer) { this.delegate.iterateStoredSectionPositions(consumer); }
}
