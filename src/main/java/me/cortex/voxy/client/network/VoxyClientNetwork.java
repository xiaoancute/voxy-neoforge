package me.cortex.voxy.client.network;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.network.VoxyPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class VoxyClientNetwork {
    private static final ConcurrentHashMap<SectionKey, CompletableFuture<VoxyPayloads.Response>> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<SectionKey> SEND_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
    private static final ScheduledExecutorService SENDER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Voxy remote LOD requests");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService REFRESHER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Voxy remote LOD refreshes");
        thread.setDaemon(true);
        return thread;
    });
    private static final LongAdder REQUESTED = new LongAdder();
    private static final LongAdder RECEIVED = new LongAdder();
    private static final LongAdder STORED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder BYTES = new LongAdder();
    private static final LongAdder INVALIDATED = new LongAdder();

    private static volatile ClientPacketListener activeConnection;
    private static volatile CompletableFuture<Boolean> handshake = new CompletableFuture<>();
    private static volatile int serverBatchSize = 1;
    private static volatile int serverRequestRate = 1;
    private static long nextRefreshNanos;

    private VoxyClientNetwork() {}

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Integer.toString(VoxyPayloads.PROTOCOL_VERSION)).optional();
        registrar.playToServer(VoxyPayloads.Hello.TYPE, VoxyPayloads.Hello.STREAM_CODEC, (payload, context) -> {});
        registrar.playToServer(VoxyPayloads.Request.TYPE, VoxyPayloads.Request.STREAM_CODEC, (payload, context) -> {});
        registrar.playToClient(VoxyPayloads.HelloResponse.TYPE, VoxyPayloads.HelloResponse.STREAM_CODEC, VoxyClientNetwork::handleHello);
        registrar.playToClient(VoxyPayloads.Response.TYPE, VoxyPayloads.Response.STREAM_CODEC, VoxyClientNetwork::handleResponse);
        registrar.playToClient(VoxyPayloads.Invalidate.TYPE, VoxyPayloads.Invalidate.STREAM_CODEC, VoxyClientNetwork::handleInvalidation);
    }

    public static VoxyPayloads.Response requestSection(WorldIdentifier identifier, long key) {
        if (!VoxyConfig.CONFIG.useServerLod || !ensureHandshake()) return null;
        String dimension = identifier.key.location().toString();
        SectionKey sectionKey = new SectionKey(dimension, key);
        CompletableFuture<VoxyPayloads.Response> created = new CompletableFuture<>();
        CompletableFuture<VoxyPayloads.Response> future = PENDING.putIfAbsent(sectionKey, created);
        if (future == null) {
            future = created;
            REQUESTED.increment();
            SEND_QUEUE.add(sectionKey);
            scheduleDrain();
        }
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            PENDING.remove(sectionKey, future);
            REJECTED.increment();
            return null;
        } catch (Exception error) {
            PENDING.remove(sectionKey, future);
            Logger.warn("Remote LOD request failed for " + key, error);
            REJECTED.increment();
            return null;
        }
    }

    private static boolean ensureHandshake() {
        if (Minecraft.getInstance().getSingleplayerServer() != null) return false;
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null
                || !NetworkRegistry.hasChannel(listener, VoxyPayloads.HELLO_ID)
                || !NetworkRegistry.hasChannel(listener, VoxyPayloads.REQUEST_ID)) return false;
        synchronized (VoxyClientNetwork.class) {
            if (activeConnection != listener) {
                resetConnection(listener);
                try {
                    PacketDistributor.sendToServer(new VoxyPayloads.Hello(VoxyPayloads.PROTOCOL_VERSION, true));
                } catch (RuntimeException error) {
                    handshake.complete(false);
                }
            }
        }
        try {
            return handshake.get(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            handshake.complete(false);
            return false;
        }
    }

    private static void handleHello(VoxyPayloads.HelloResponse payload, IPayloadContext context) {
        boolean accepted = payload.protocol() == VoxyPayloads.PROTOCOL_VERSION && payload.enabled();
        serverBatchSize = Math.max(1, Math.min(VoxyPayloads.MAX_REQUEST_SECTIONS, payload.maxSections()));
        serverRequestRate = Math.max(1, Math.min(512, payload.maxRequestsPerSecond()));
        handshake.complete(accepted);
    }

    private static void handleResponse(VoxyPayloads.Response payload, IPayloadContext context) {
        RECEIVED.increment();
        CompletableFuture<VoxyPayloads.Response> future = PENDING.remove(new SectionKey(payload.dimension(), payload.key()));
        if (future != null) future.complete(payload);
    }

    private static void handleInvalidation(VoxyPayloads.Invalidate payload, IPayloadContext context) {
        INVALIDATED.add(payload.keys().length);
        RemoteSectionStorage.handleInvalidation(payload);
    }

    public static void refreshSection(WorldIdentifier identifier, long key, Consumer<VoxyPayloads.Response> callback) {
        long delay;
        synchronized (VoxyClientNetwork.class) {
            long now = System.nanoTime();
            long target = Math.max(now, nextRefreshNanos);
            delay = target - now;
            nextRefreshNanos = target + Math.max(1L, 1_000_000_000L / serverRequestRate);
        }
        REFRESHER.schedule(() -> {
            VoxyPayloads.Response response = requestSection(identifier, key);
            Minecraft.getInstance().execute(() -> callback.accept(response));
        }, delay, TimeUnit.NANOSECONDS);
    }

    private static void scheduleDrain() {
        if (DRAIN_SCHEDULED.compareAndSet(false, true)) {
            SENDER.schedule(VoxyClientNetwork::drainRequests, 2, TimeUnit.MILLISECONDS);
        }
    }

    private static void drainRequests() {
        try {
            Map<String, ArrayList<Long>> batches = new LinkedHashMap<>();
            SectionKey key;
            while ((key = SEND_QUEUE.poll()) != null) {
                if (!PENDING.containsKey(key)) continue;
                ArrayList<Long> batch = batches.computeIfAbsent(key.dimension, ignored -> new ArrayList<>(serverBatchSize));
                if (batch.size() >= serverBatchSize) {
                    sendBatch(key.dimension, batch);
                    batch = new ArrayList<>(serverBatchSize);
                    batches.put(key.dimension, batch);
                }
                batch.add(key.key);
            }
            for (var entry : batches.entrySet()) sendBatch(entry.getKey(), entry.getValue());
        } finally {
            DRAIN_SCHEDULED.set(false);
            if (!SEND_QUEUE.isEmpty()) scheduleDrain();
        }
    }

    private static void sendBatch(String dimension, ArrayList<Long> batch) {
        if (batch.isEmpty()) return;
        long[] keys = new long[batch.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = batch.get(i);
        try {
            PacketDistributor.sendToServer(new VoxyPayloads.Request(dimension, keys));
        } catch (RuntimeException error) {
            for (long key : keys) {
                CompletableFuture<VoxyPayloads.Response> future = PENDING.remove(new SectionKey(dimension, key));
                if (future != null) future.completeExceptionally(error);
            }
        }
    }

    private static void resetConnection(ClientPacketListener listener) {
        activeConnection = listener;
        handshake = new CompletableFuture<>();
        serverBatchSize = 1;
        serverRequestRate = 1;
        nextRefreshNanos = 0;
        PENDING.forEach((key, future) -> future.completeExceptionally(new IllegalStateException("Connection changed")));
        PENDING.clear();
        SEND_QUEUE.clear();
    }

    public static void recordStored(VoxyPayloads.Response response) {
        STORED.increment();
        long bytes = response.data().length * Long.BYTES;
        for (var mapping : response.mappings()) bytes += mapping.data().length;
        BYTES.add(bytes);
    }

    public static void recordRejected() {
        REJECTED.increment();
    }

    static long requestedSections() { return REQUESTED.sum(); }
    static long storedSections() { return STORED.sum(); }
    static long invalidatedSections() { return INVALIDATED.sum(); }

    public static String getStatus() {
        boolean enabled = handshake.isDone() && handshake.getNow(false);
        return "remoteLod=" + enabled
                + ",remotePending=" + PENDING.size()
                + ",remoteRequested=" + REQUESTED.sum()
                + ",remoteReceived=" + RECEIVED.sum()
                + ",remoteStored=" + STORED.sum()
                + ",remoteRejected=" + REJECTED.sum()
                + ",remoteInvalidated=" + INVALIDATED.sum()
                + ",remoteBytes=" + BYTES.sum();
    }

    private record SectionKey(String dimension, long key) {}
}
