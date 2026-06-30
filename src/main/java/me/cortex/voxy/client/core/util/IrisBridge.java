package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipeline;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;

import java.io.IOException;
import java.util.function.BooleanSupplier;

final class IrisBridge {
    private IrisBridge() {
    }

    static boolean irisShadowActive() {
        return ShadowRenderer.ACTIVE;
    }

    static void clearIrisSamplers() {
        for (int i = 0; i < 16; i++) {
            IrisRenderSystem.bindSamplerToUnit(i, 0);
        }
    }

    static void reload() {
        try {
            if (IrisApi.getInstance().isShaderPackInUse()) {//Only reload if there is a shaderpack
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean irisShaderPackEnabled() {
        return Iris.isPackInUseQuick();
    }

    static void disableIrisShaders() {
        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);//Disable shaders
    }

    static AbstractRenderPipeline createPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            return null;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                return null;
            }
            Logger.info("Creating voxy iris render pipeline");
            try {
                return new IrisVoxyRenderPipeline(pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception e) {
                Logger.error("Failed to create iris render pipeline", e);
                disableIrisShaders();
                return null;
            }
        }
        return null;
    }
}
