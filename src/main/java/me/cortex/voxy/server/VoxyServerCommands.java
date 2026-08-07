package me.cortex.voxy.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class VoxyServerCommands {
    private VoxyServerCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("voxy")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("server")
                        .then(Commands.literal("status")
                                .executes(context -> printStatus(context.getSource()))));
    }

    private static int printStatus(CommandSourceStack source) {
        if (!(VoxyCommon.getInstance() instanceof VoxyServerInstance instance)) {
            source.sendSuccess(() -> Component.literal("Voxy server companion is disabled"), false);
            return 0;
        }
        String status = instance.getStatus() + "," + VoxyServerLifecycle.getIngestStatus();
        source.sendSuccess(() -> Component.literal("Voxy server: " + status), false);
        return 1;
    }
}
