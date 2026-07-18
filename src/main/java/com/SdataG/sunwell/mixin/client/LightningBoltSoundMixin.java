package com.SdataG.sunwell.mixin.client;

import com.SdataG.sunwell.SunwellManager;
import com.SdataG.sunwell.client.render.SunwellBoltRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the thunder/impact sound of a sunwell bolt to the moment it actually strikes.
 *
 * <p>Vanilla fires both sounds on the bolt's first tick ({@code life == 2}). Our animation spends its
 * first several ticks on the dim leader creeping down, and only lands the bright return stroke at
 * {@link SunwellBoltRenderer#STRIKE_TICK}. So the crack arrived early, during the spread. This mutes
 * vanilla's early sound for our bolts and plays it on the strike tick instead — the flash and the
 * crack together. Non-sunwell bolts (a real storm outside) are untouched.</p>
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltSoundMixin {

    @Unique
    private boolean sunwell$playedStrike;

    /** Silence vanilla's first-tick thunder + impact for sunwell bolts (both calls match this @At). */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;"
                            + "Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void sunwell$muteEarlySound(Level level, double x, double y, double z, SoundEvent sound,
                                        SoundSource source, float volume, float pitch, boolean distance) {
        LightningBolt self = (LightningBolt) (Object) this;
        if (isSunwellBolt(level, self)) {
            return; // ours plays on the strike tick instead of at spawn
        }
        level.playLocalSound(x, y, z, sound, source, volume, pitch, distance);
    }

    /** Play the crack once, when the return stroke lands. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void sunwell$strikeSound(CallbackInfo ci) {
        LightningBolt self = (LightningBolt) (Object) this;
        Level level = self.level();
        if (!level.isClientSide || sunwell$playedStrike || self.tickCount < SunwellBoltRenderer.STRIKE_TICK) {
            return;
        }
        if (!isSunwellBolt(level, self)) {
            return;
        }
        sunwell$playedStrike = true;
        double x = self.getX();
        double y = self.getY();
        double z = self.getZ();
        float pitch = 0.85F + level.random.nextFloat() * 0.2F;
        level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 1.4F, pitch, false);
        level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.2F, pitch, false);
    }

    @Unique
    private static boolean isSunwellBolt(Level level, LightningBolt bolt) {
        SunwellManager manager = SunwellManager.get(level);
        if (manager == null) {
            return false;
        }
        BlockPos pos = bolt.blockPosition();
        return manager.nearestSourceAbove(pos) != null || manager.baseSkyAt(pos) > 0;
    }
}
