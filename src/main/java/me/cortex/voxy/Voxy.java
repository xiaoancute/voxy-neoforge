package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Handles config registration and config screen setup.
 * Actual initialization happens via mixins (MixinRenderSystem).
 */
@Mod(value = "voxy", dist = Dist.CLIENT)
public class Voxy {

    public Voxy(IEventBus modEventBus, ModContainer container) {
        // Register NeoForge config
        VoxyNeoForgeConfig.register(container);

        // Register the built-in NeoForge config screen
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
