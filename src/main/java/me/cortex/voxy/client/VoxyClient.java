package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.network.VoxyClientNetwork;
import me.cortex.voxy.client.network.RemoteLodCiProbe;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
// TODO: Debug screen API changed in MC 1.21.1 - disabled for now
// import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
// import net.minecraft.client.gui.components.debug.DebugScreenEntries;
// import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client initialization for Voxy on NeoForge.
 * Uses NeoForge event bus for command registration.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClient {
    private static final String RENDER_COMPATIBILITY_PROBE_PROPERTY = "voxy.clientSmokeRenderCompatibilityProbe";
    private static final String SODIUM_RENDER_SECTION_MANAGER = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager";
    private static final HashSet<String> FREX = new HashSet<>();
    private static boolean rendererRefreshQueued;
    private static String rendererRefreshReason = "";

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();
            BudgetBufferRenderer.init();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        } else {
            Logger.error("Voxy is unsupported on your system.");
        }

        runRenderCompatibilityProbe();
        Logger.info("Voxy client initialization completed");
    }

    private static void runRenderCompatibilityProbe() {
        if (!Boolean.getBoolean(RENDER_COMPATIBILITY_PROBE_PROPERTY)) {
            return;
        }

        // Sodium normally loads this class only after joining a world. Loading it during
        // smoke tests makes Mixin validate both Voxy's and Iris's version-specific hooks.
        try {
            Class.forName(SODIUM_RENDER_SECTION_MANAGER, false, VoxyClient.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load Sodium's render section manager during the client smoke test", e);
        }

        Logger.info("Voxy Sodium/Iris render compatibility probe completed");
    }

    /**
     * NeoForge event handler for client command registration.
     * Replaces Fabric's ClientCommandRegistrationCallback.
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        RemoteLodCiProbe.tick();
        if (!rendererRefreshQueued) {
            return;
        }
        String reason = rendererRefreshReason;
        rendererRefreshQueued = false;
        rendererRefreshReason = "";
        refreshRenderer("auto recovery: " + reason, true);
    }

    public static void queueRendererRefresh(String reason) {
        if (rendererRefreshQueued) {
            return;
        }
        rendererRefreshQueued = true;
        rendererRefreshReason = reason;
    }

    public static boolean refreshRenderer(String reason, boolean actionBarMessage) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.levelRenderer == null) {
            sendClientMessage(Component.translatable("voxy.command.refresh.failed"), actionBarMessage);
            return false;
        }

        Logger.warn("Refreshing Voxy renderer: " + reason);
        client.levelRenderer.allChanged();

        int syncedSections = -1;
        var renderer = ((IGetVoxyRenderSystem) client.levelRenderer).getVoxyRenderSystem();
        if (renderer != null) {
            syncedSections = renderer.syncVanillaSectionsFromSodium();
        }

        sendClientMessage(Component.translatable("voxy.command.refresh.done", syncedSections), actionBarMessage);
        return true;
    }

    public static String getRendererStatus() {
        var client = Minecraft.getInstance();
        String baseStatus = getConfigStatus() + "," + getInstanceStatus() + "," + VoxyClientNetwork.getStatus();
        if (client.levelRenderer == null) {
            return baseStatus + ",levelRenderer=null";
        }
        var renderer = ((IGetVoxyRenderSystem) client.levelRenderer).getVoxyRenderSystem();
        if (renderer == null) {
            return baseStatus + ",renderer=null";
        }
        return baseStatus + "," + renderer.getLodRecoveryDebugSummary();
    }

    private static String getConfigStatus() {
        var config = VoxyConfig.CONFIG;
        return "config={"
                + "enabled=" + config.enabled
                + ",rendering=" + config.enableRendering
                + ",ingest=" + config.ingestEnabled
                + ",serverLod=" + config.useServerLod
                + ",renderDistance=" + config.sectionRenderDistance
                + ",autoLodRecovery=" + config.autoLodRecovery
                + ",sodiumBuilderThreads=" + (!config.dontUseSodiumBuilderThreads)
                + "}";
    }

    private static String getInstanceStatus() {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return "instance=null,storagePath=null";
        }
        if (instance instanceof VoxyClientInstance clientInstance) {
            return "instance=client,storagePath=" + clientInstance.getStorageBasePath();
        }
        return "instance=" + instance.getClass().getSimpleName() + ",storagePath=unknown";
    }

    public static void sendClientMessage(Component component, boolean actionBar) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(component, actionBar);
        }
    }

    // Note: FREX flawless frames integration disabled on NeoForge
    // (Fabric-specific entrypoint mechanism not available)

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
