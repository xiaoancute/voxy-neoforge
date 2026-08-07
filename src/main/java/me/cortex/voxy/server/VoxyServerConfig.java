package me.cortex.voxy.server;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class VoxyServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable the Voxy dedicated-server companion")
            .define("enabled", true);

    private static final ModConfigSpec.BooleanValue INGEST_GENERATED_CHUNKS = BUILDER
            .comment("Build server-side LOD data for newly generated chunks")
            .define("ingestGeneratedChunks", true);

    private static final ModConfigSpec.BooleanValue INGEST_LOADED_CHUNKS = BUILDER
            .comment("Also build LOD data whenever an existing chunk is loaded",
                    "This can add CPU and disk load on established servers.")
            .define("ingestLoadedChunks", false);

    private static final ModConfigSpec.IntValue SERVICE_THREADS = BUILDER
            .comment("Background threads used for Voxy ingestion and storage")
            .defineInRange("serviceThreads", defaultThreadCount(), 1, 32);

    private static final ModConfigSpec.IntValue MAX_INGEST_QUEUE = BUILDER
            .comment("Stop accepting chunk-load work while this many ingestion tasks are queued")
            .defineInRange("maxIngestQueue", 1024, 64, 16384);

    private static final ModConfigSpec.BooleanValue SERVE_REMOTE_LOD = BUILDER
            .comment("Allow Voxy clients to request cached LOD sections from this server")
            .define("serveRemoteLod", true);

    private static final ModConfigSpec.IntValue MAX_REMOTE_REQUESTS_PER_SECOND = BUILDER
            .comment("Maximum remote LOD section requests accepted per player per second")
            .defineInRange("maxRemoteRequestsPerSecond", 32, 1, 512);

    private static final ModConfigSpec.IntValue MAX_REMOTE_RESPONSE_BYTES = BUILDER
            .comment("Maximum uncompressed bytes in one remote LOD response")
            .defineInRange("maxRemoteResponseBytes", 900_000, 262_144, 1_000_000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VoxyServerConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC, "voxy-server.toml");
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean ingestGeneratedChunks() {
        return INGEST_GENERATED_CHUNKS.get();
    }

    public static boolean ingestLoadedChunks() {
        return INGEST_LOADED_CHUNKS.get();
    }

    public static int serviceThreads() {
        return SERVICE_THREADS.get();
    }

    public static int maxIngestQueue() {
        return MAX_INGEST_QUEUE.get();
    }

    public static boolean serveRemoteLod() {
        return isEnabled() && SERVE_REMOTE_LOD.get();
    }

    public static int maxRemoteRequestsPerSecond() {
        return MAX_REMOTE_REQUESTS_PER_SECOND.get();
    }

    public static int maxRemoteResponseBytes() {
        return Math.min(MAX_REMOTE_RESPONSE_BYTES.get(), me.cortex.voxy.network.VoxyPayloads.MAX_RESPONSE_BYTES);
    }

    private static int defaultThreadCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }
}
