package me.cortex.voxy.client.network;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic client-side probe used only by the remote LOD GitHub Action. */
public final class RemoteLodCiProbe {
    private static final boolean ENABLED = Boolean.getBoolean("voxy.ci.remoteLodProbe");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Voxy remote LOD CI probe");
        thread.setDaemon(true);
        return thread;
    });

    private RemoteLodCiProbe() {}

    public static void prepareClientConfig() {
        if (!ENABLED) return;
        VoxyConfig.CONFIG.enabled = true;
        VoxyConfig.CONFIG.enableRendering = false;
        VoxyConfig.CONFIG.ingestEnabled = false;
        VoxyConfig.CONFIG.useServerLod = true;
    }

    public static void tick() {
        if (!ENABLED || STARTED.get()) return;
        prepareClientConfig();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.getConnection() == null
                || VoxyCommon.getInstance() == null) return;

        WorldIdentifier identifier = WorldIdentifier.of(client.level);
        if (identifier == null) return;
        int sectionX = Math.floorDiv(client.player.getBlockX(), 32);
        int sectionY = Math.floorDiv(client.player.getBlockY(), 32);
        int sectionZ = Math.floorDiv(client.player.getBlockZ(), 32);
        if (STARTED.compareAndSet(false, true)) {
            EXECUTOR.execute(() -> run(identifier, sectionX, sectionY, sectionZ));
        }
    }

    private static void run(WorldIdentifier identifier, int sectionX, int sectionY, int sectionZ) {
        try {
            WorldEngine engine = identifier.getOrCreateEngine();
            if (engine == null) throw new IllegalStateException("Client world engine is unavailable");
            long key = WorldEngine.getWorldSectionId(0, sectionX, sectionY, sectionZ);
            long requestedBefore = VoxyClientNetwork.requestedSections();
            long storedBefore = VoxyClientNetwork.storedSections();
            long deadline = System.nanoTime() + 90_000_000_000L;
            boolean loaded = false;
            while (System.nanoTime() < deadline) {
                var section = engine.acquireIfExists(key);
                if (section != null) {
                    section.release();
                    loaded = VoxyClientNetwork.requestedSections() > requestedBefore
                            && VoxyClientNetwork.storedSections() > storedBefore;
                    if (loaded) break;
                }
                Thread.sleep(1000);
            }
            if (!loaded) throw new IllegalStateException("Server did not provide the spawn LOD section");

            int blockX = sectionX * 32 + 1;
            int blockY = sectionY * 32 + 1;
            int blockZ = sectionZ * 32 + 1;
            long storedAfterLoad = VoxyClientNetwork.storedSections();
            long invalidatedAfterLoad = VoxyClientNetwork.invalidatedSections();
            Logger.info("VOXY_REMOTE_LOD_PROBE stored key=" + key
                    + " block=" + blockX + "," + blockY + "," + blockZ);

            deadline = System.nanoTime() + 90_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (VoxyClientNetwork.invalidatedSections() > invalidatedAfterLoad
                        && VoxyClientNetwork.storedSections() > storedAfterLoad) {
                    Logger.info("VOXY_REMOTE_LOD_PROBE success key=" + key
                            + " " + VoxyClientNetwork.getStatus());
                    return;
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException("Invalidated section was not refreshed");
        } catch (Throwable error) {
            Logger.error("VOXY_REMOTE_LOD_PROBE failed", error);
        }
    }
}
