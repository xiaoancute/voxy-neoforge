package me.cortex.voxy.server;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.network.VoxyPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

@EventBusSubscriber(modid = "voxy", value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.MOD)
public final class VoxyServerNetwork {
    private static final Map<UUID, ClientState> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<String, Set<Long>> INVALIDATIONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean INVALIDATION_FLUSH_SCHEDULED = new AtomicBoolean();
    private static final LongAdder REQUESTED = new LongAdder();
    private static final LongAdder SERVED = new LongAdder();
    private static final LongAdder MISSING = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder BYTES = new LongAdder();
    private static final LongAdder INVALIDATED = new LongAdder();
    private static final LongAdder INVALIDATION_PACKETS = new LongAdder();

    private VoxyServerNetwork() {}

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Integer.toString(VoxyPayloads.PROTOCOL_VERSION)).optional();
        registrar.playToServer(VoxyPayloads.Hello.TYPE, VoxyPayloads.Hello.STREAM_CODEC, VoxyServerNetwork::handleHello);
        registrar.playToServer(VoxyPayloads.Request.TYPE, VoxyPayloads.Request.STREAM_CODEC, VoxyServerNetwork::handleRequest);
        registrar.playToClient(VoxyPayloads.HelloResponse.TYPE, VoxyPayloads.HelloResponse.STREAM_CODEC, (payload, context) -> {});
        registrar.playToClient(VoxyPayloads.Response.TYPE, VoxyPayloads.Response.STREAM_CODEC, (payload, context) -> {});
        registrar.playToClient(VoxyPayloads.Invalidate.TYPE, VoxyPayloads.Invalidate.STREAM_CODEC, (payload, context) -> {});
    }

    private static void handleHello(VoxyPayloads.Hello payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        boolean accepted = payload.protocol() == VoxyPayloads.PROTOCOL_VERSION
                && payload.remoteLod()
                && VoxyServerConfig.serveRemoteLod()
                && VoxyCommon.getInstance() instanceof VoxyServerInstance;
        CLIENTS.put(player.getUUID(), new ClientState(accepted));
        context.reply(new VoxyPayloads.HelloResponse(
                VoxyPayloads.PROTOCOL_VERSION,
                accepted,
                VoxyPayloads.MAX_REQUEST_SECTIONS,
                VoxyServerConfig.maxRemoteRequestsPerSecond()));
    }

    private static void handleRequest(VoxyPayloads.Request payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ClientState state = CLIENTS.get(player.getUUID());
        if (state == null || !state.enabled || !state.tryAcquire(payload.keys().length)) {
            REJECTED.add(payload.keys().length);
            replyMissing(payload.dimension(), payload.keys(), context);
            return;
        }
        if (!player.level().dimension().location().toString().equals(payload.dimension())
                || !(VoxyCommon.getInstance() instanceof VoxyServerInstance instance)) {
            REJECTED.add(payload.keys().length);
            replyMissing(payload.dimension(), payload.keys(), context);
            return;
        }

        WorldIdentifier identifier = WorldIdentifier.of(player.level());
        WorldEngine engine = instance.getOrCreate(identifier, true);
        if (engine == null) {
            replyMissing(payload.dimension(), payload.keys(), context);
            return;
        }
        try {
            for (long key : payload.keys()) {
                REQUESTED.increment();
                VoxyPayloads.Response response = createResponse(payload.dimension(), engine, key);
                context.reply(response);
                if (response.found()) {
                    SERVED.increment();
                    BYTES.add(estimateSize(response));
                } else {
                    MISSING.increment();
                }
            }
        } catch (RuntimeException error) {
            Logger.error("Failed to serve remote LOD request for " + player.getGameProfile().getName(), error);
            REJECTED.add(payload.keys().length);
        } finally {
            engine.releaseRef();
        }
    }

    private static VoxyPayloads.Response createResponse(String dimension, WorldEngine engine, long key) {
        if ((key & 15L) != 0 || WorldEngine.getLevel(key) > WorldEngine.MAX_LOD_LAYER) return missing(dimension, key);
        WorldSection section = engine.acquireIfExists(key);
        if (section == null) return missing(dimension, key);
        try {
            long[] data = section.copyData();
            var mappings = collectMappings(engine.getMapper(), data);
            var response = new VoxyPayloads.Response(dimension, key, true, section.getNonEmptyChildren(), data, mappings);
            if (estimateSize(response) > VoxyServerConfig.maxRemoteResponseBytes()) {
                REJECTED.increment();
                return missing(dimension, key);
            }
            return response;
        } finally {
            section.release();
        }
    }

    private static ArrayList<VoxyPayloads.Mapping> collectMappings(Mapper mapper, long[] data) {
        IntOpenHashSet blockIds = new IntOpenHashSet();
        IntOpenHashSet biomeIds = new IntOpenHashSet();
        for (long value : data) {
            int blockId = Mapper.getBlockId(value);
            if (blockId == 0) continue;
            blockIds.add(blockId);
            biomeIds.add(Mapper.getBiomeId(value));
        }

        Mapper.StateEntry[] states = mapper.getStateEntries();
        Mapper.BiomeEntry[] biomes = mapper.getBiomeEntries();
        ArrayList<VoxyPayloads.Mapping> mappings = new ArrayList<>(blockIds.size() + biomeIds.size());
        for (int id : blockIds) {
            if (id < 0 || id >= states.length) throw new IllegalStateException("Invalid block mapping " + id);
            mappings.add(new VoxyPayloads.Mapping(1, id, states[id].serialize()));
        }
        for (int id : biomeIds) {
            if (id < 0 || id >= biomes.length) throw new IllegalStateException("Invalid biome mapping " + id);
            mappings.add(new VoxyPayloads.Mapping(2, id, biomes[id].serialize()));
        }
        return mappings;
    }

    private static int estimateSize(VoxyPayloads.Response response) {
        long size = 32L + response.data().length * Long.BYTES;
        for (var mapping : response.mappings()) size += 16L + mapping.data().length;
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    private static void replyMissing(String dimension, long[] keys, IPayloadContext context) {
        for (long key : keys) context.reply(missing(dimension, key));
    }

    private static VoxyPayloads.Response missing(String dimension, long key) {
        return new VoxyPayloads.Response(dimension, key, false, (byte) 0, new long[0], java.util.List.of());
    }

    public static void removeClient(UUID id) {
        CLIENTS.remove(id);
    }

    public static void clearClients() {
        CLIENTS.clear();
        INVALIDATIONS.clear();
        INVALIDATION_FLUSH_SCHEDULED.set(false);
    }

    public static void queueInvalidation(MinecraftServer server, String dimension, long key) {
        if (CLIENTS.values().stream().noneMatch(client -> client.enabled)) return;
        INVALIDATIONS.computeIfAbsent(dimension, ignored -> ConcurrentHashMap.newKeySet()).add(key);
        scheduleInvalidationFlush(server);
    }

    private static void scheduleInvalidationFlush(MinecraftServer server) {
        if (INVALIDATION_FLUSH_SCHEDULED.compareAndSet(false, true)) {
            server.execute(() -> flushInvalidations(server));
        }
    }

    private static void flushInvalidations(MinecraftServer server) {
        int batchesRemaining = 16;
        try {
            for (var entry : INVALIDATIONS.entrySet()) {
                Set<Long> pending = entry.getValue();
                while (!pending.isEmpty() && batchesRemaining-- > 0) {
                    long[] keys = takeInvalidations(pending);
                    if (keys.length == 0) break;
                    var payload = new VoxyPayloads.Invalidate(entry.getKey(), keys);
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        ClientState state = CLIENTS.get(player.getUUID());
                        if (state != null && state.enabled
                                && player.level().dimension().location().toString().equals(entry.getKey())) {
                            PacketDistributor.sendToPlayer(player, payload);
                            INVALIDATION_PACKETS.increment();
                        }
                    }
                    INVALIDATED.add(keys.length);
                }
                if (pending.isEmpty()) INVALIDATIONS.remove(entry.getKey(), pending);
                if (batchesRemaining <= 0) break;
            }
        } finally {
            INVALIDATION_FLUSH_SCHEDULED.set(false);
            if (!INVALIDATIONS.isEmpty()) scheduleInvalidationFlush(server);
        }
    }

    private static long[] takeInvalidations(Set<Long> pending) {
        long[] scratch = new long[Math.min(VoxyPayloads.MAX_INVALIDATION_SECTIONS, pending.size())];
        int count = 0;
        for (Long key : pending) {
            if (count >= scratch.length) break;
            if (pending.remove(key)) scratch[count++] = key;
        }
        return count == scratch.length ? scratch : java.util.Arrays.copyOf(scratch, count);
    }

    public static String getStatus() {
        return "networkClients=" + CLIENTS.values().stream().filter(client -> client.enabled).count()
                + ",requested=" + REQUESTED.sum()
                + ",served=" + SERVED.sum()
                + ",missing=" + MISSING.sum()
                + ",rejected=" + REJECTED.sum()
                + ",sentBytes=" + BYTES.sum()
                + ",invalidated=" + INVALIDATED.sum()
                + ",invalidationPackets=" + INVALIDATION_PACKETS.sum();
    }

    private static final class ClientState {
        private final boolean enabled;
        private long windowStart = System.nanoTime();
        private int used;

        private ClientState(boolean enabled) {
            this.enabled = enabled;
        }

        private synchronized boolean tryAcquire(int amount) {
            long now = System.nanoTime();
            if (now - this.windowStart >= 1_000_000_000L) {
                this.windowStart = now;
                this.used = 0;
            }
            if (amount <= 0 || this.used + amount > VoxyServerConfig.maxRemoteRequestsPerSecond()) return false;
            this.used += amount;
            return true;
        }
    }
}
