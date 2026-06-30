package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.neoforged.fml.ModList;

import java.util.function.BooleanSupplier;

public class IrisUtil {

    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = ModList.get() != null && ModList.get().isLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;//System.getProperty("voxy.enableExperimentalIrisPipeline", "false").equalsIgnoreCase("true");


    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && IrisBridge.irisShadowActive();
    }

    public static void clearIrisSamplers() {
        if (IRIS_INSTALLED) IrisBridge.clearIrisSamplers();
    }
    public static void reload() {
        if (IRIS_INSTALLED) IrisBridge.reload();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && IrisBridge.irisShaderPackEnabled();
    }
    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) IrisBridge.disableIrisShaders();
    }

    public static AbstractRenderPipeline createPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        if (IRIS_INSTALLED && SHADER_SUPPORT) {
            return IrisBridge.createPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        return null;
    }
}
