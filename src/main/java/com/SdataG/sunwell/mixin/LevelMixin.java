package com.SdataG.sunwell.mixin;

import com.SdataG.sunwell.SunwellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes rain and snow into rain-through sunwell columns via {@code isRainingAt}.
 * Open-sky weather checks (canSeeSky) are handled on the {@code BlockAndTintGetter}
 * interface default by {@link BlockAndTintGetterMixin}, since {@code Level} does not
 * declare its own {@code canSeeSky} for an {@code @Inject} to target.
 * Mob sun-burn stays on {@link MobMixin}, not sky light 15.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "isRainingAt(Lnet/minecraft/core/BlockPos;)Z", at = @At("RETURN"), cancellable = true)
    private void sunwell$weatherThroughSkylight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        Level self = (Level) (Object) this;
        if (SunwellManager.allowsPrecipitationAt(self, pos)) {
            cir.setReturnValue(true);
        }
    }
}
