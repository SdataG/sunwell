package com.SdataG.sunwell.mixin;

import com.SdataG.sunwell.SunwellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Undead sun-burn inside virtual sunwell requires the {@code undead_burning} block tag
 * on the lighting source, plus {@link com.SdataG.sunwell.SunwellConfig#enableUndeadBurning}.
 * Static grow-light sources (sunwell_source only) never trigger burns here.
 */
@Mixin(Mob.class)
public abstract class MobMixin {

    @Inject(method = "isSunBurnTick()Z", at = @At("HEAD"), cancellable = true)
    private void sunwell$sunBurnInRegion(CallbackInfoReturnable<Boolean> cir) {
        Mob self = (Mob) (Object) this;
        Level level = self.level();
        if (level.isClientSide) {
            return;
        }

        BlockPos eye = BlockPos.containing(self.getX(), self.getEyeY(), self.getZ());
        SunwellManager manager = SunwellManager.get(level);
        if (manager == null) {
            return;
        }

        boolean inVirtual = manager.baseSkyAt(eye) > 0;
        if (!inVirtual) {
            return;
        }

        if (manager.allowsUndeadBurningAt(eye)) {
            if (!level.isDay() || !isSunBurnCandidate(self) || self.isInWaterRainOrBubble()) {
                return;
            }
            float brightness = self.getLightLevelDependentMagicValue();
            if (brightness > 0.5F
                    && self.getRandom().nextFloat() * 30.0F < (brightness - 0.4F) * 2.0F) {
                cir.setReturnValue(true);
            }
            return;
        }

        cir.setReturnValue(false);
    }

    private static boolean isSunBurnCandidate(Mob mob) {
        if (mob.fireImmune()) {
            return false;
        }
        return !(mob instanceof Husk || mob instanceof WitherSkeleton);
    }
}
