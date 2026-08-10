package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.server.VoxyServerLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dedicated-server-only hook for coalescing live block changes into LOD updates. */
@Mixin(Level.class)
public abstract class MixinServerWorld {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void voxy$queueSectionUpdate(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (Object) this instanceof ServerLevel level) {
            VoxyServerLifecycle.queueBlockUpdate(level, pos);
        }
    }
}
