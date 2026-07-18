package com.SdataG.sunwell.mixin.client;

import com.SdataG.sunwell.ShaderCompat;
import com.SdataG.sunwell.SunwellManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium clones the raw light nibbles into {@code LevelSlice} and meshes chunks from that copy, so it
 * never calls the vanilla light engine where Sunwell injects its virtual sky light ({@code
 * LightEngineMixin}). That's why sunwell rooms render dark under Sodium (and Iris, which pulls Sodium
 * in). This reapplies the same boost at Sodium's own read point, so the projected sky light shows up.
 *
 * <p>Targeted by string and gated by {@code SunwellMixinPlugin} to load only when Sodium is present.
 * {@code skyAt} is documented lock-free for exactly this off-thread (mesh worker) access.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class SodiumLevelSliceMixin {

    @Shadow @Final private ClientLevel level;

    @Inject(
            method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private void sunwell$boostSodiumLight(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!SunwellManager.ACTIVE || this.level == null) {
            return;
        }
        SunwellManager manager = SunwellManager.get(this.level);
        if (manager == null) {
            return;
        }
        if (layer == LightLayer.SKY) {
            int virtual = manager.skyAt(pos);
            if (virtual > cir.getReturnValueI()) {
                cir.setReturnValue(Math.min(15, virtual));
            }
        } else if (layer == LightLayer.BLOCK && ShaderCompat.shadersActive()) {
            // Shaderpacks light the room off block light; fill it in the lit region (see LightEngineMixin).
            int fill = manager.shaderBlockFillAt(pos);
            if (fill > cir.getReturnValueI()) {
                cir.setReturnValue(Math.min(15, fill));
            }
        }
    }
}
