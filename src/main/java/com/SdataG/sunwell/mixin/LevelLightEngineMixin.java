package com.SdataG.sunwell.mixin;

import com.SdataG.sunwell.SunwellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boosts combined raw-brightness queries (crops, saplings, mob AI). Unlike the sky-layer
 * hook, this also works in dimensions with has_skylight=false, where the sky layer is a
 * dummy listener and never consulted.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Inject(method = "getRawBrightness", at = @At("RETURN"), cancellable = true)
    private void sunwell$boostRawBrightness(BlockPos pos, int ambientDarkening,
                                                     CallbackInfoReturnable<Integer> cir) {
        if (!SunwellManager.ACTIVE || SunwellManager.isBoostSuppressed()) {
            return;
        }
        SunwellManager manager = SunwellManager.byEngine(this);
        if (manager == null) {
            return;
        }
        int virtual = manager.skyAt(pos) - ambientDarkening;
        if (virtual > cir.getReturnValueI()) {
            cir.setReturnValue(Math.min(15, virtual));
        }
    }
}
