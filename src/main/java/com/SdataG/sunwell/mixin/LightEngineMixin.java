package com.SdataG.sunwell.mixin;

import com.SdataG.sunwell.SunwellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The real chokepoint: both {@code getBrightness(SKY, pos)} (what Dynamic Trees
 * reads for leaf light) and {@code getRawBrightness} (vanilla crop/sapling
 * growth) ultimately call {@code getLightValue} on the level's SKY light engine.
 *
 * <p>We boost only the registered SKY engine instance, so block light is never
 * faked. The virtual level stays below 15 by default, so {@code canSeeSky}
 * (which requires exactly 15) never flips - no mob burning, no weather.</p>
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMixin {

    @Inject(
            method = "getLightValue(Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true)
    private void sunwell$boostSky(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!SunwellManager.ACTIVE) {
            return;
        }
        if (SunwellManager.isBoostSuppressed()) {
            return;
        }
        SunwellManager manager = SunwellManager.byEngine(this);
        if (manager == null) {
            return;
        }
        if (manager.isSkyEngine(this)) {
            int virtual = manager.skyAt(pos);
            if (virtual > 0 && virtual > cir.getReturnValueI()) {
                cir.setReturnValue(Math.min(15, virtual));
            }
        } else if (manager.isBlockEngine(this)) {
            // Shaderpacks don't light the room off our virtual SKY light, so fill BLOCK light across the
            // lit region for them (client only; reflection-gated, so no effect without Iris/Oculus).
            int fill = com.SdataG.sunwell.ShaderCompat.shadersActive() ? manager.shaderBlockFillAt(pos) : 0;
            // A lightning strike flares real BLOCK light at its impact point for a moment -- an actual
            // in-world lighting effect from the bolt, on top of the additive glow geometry. Unconditional
            // (works with or without a shaderpack), since vanilla renders block light either way.
            int flash = com.SdataG.sunwell.client.render.LightningFlashLight.extraLightAt(
                    pos.getX(), pos.getY(), pos.getZ());
            int boost = Math.max(fill, flash);
            if (boost > 0 && boost > cir.getReturnValueI()) {
                cir.setReturnValue(Math.min(15, boost));
            }
        }
    }
}
