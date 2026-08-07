package me.cortex.voxy;

import me.cortex.voxy.server.VoxyServerConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Common NeoForge entrypoint. Client-only setup lives in the physical-client
 * mod entrypoint so the same jar can be installed on a dedicated server.
 */
@Mod("voxy")
public class Voxy {

    public Voxy(IEventBus modEventBus, ModContainer container) {
        VoxyServerConfig.register(container);
    }
}
