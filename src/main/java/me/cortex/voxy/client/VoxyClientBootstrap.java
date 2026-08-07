package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = "voxy", dist = Dist.CLIENT)
public final class VoxyClientBootstrap {
    public VoxyClientBootstrap(ModContainer container) {
        VoxyNeoForgeConfig.register(container);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
