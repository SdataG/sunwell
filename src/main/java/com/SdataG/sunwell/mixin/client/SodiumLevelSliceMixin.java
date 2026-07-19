package com.SdataG.sunwell.mixin.client;

import com.SdataG.sunwell.ShaderCompat;
import com.SdataG.sunwell.SunwellManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Embeddium (Sodium for 1.20.1 Forge) clones the raw light nibbles into {@code WorldSlice} and meshes
 * chunks from that copy, so it never calls the vanilla light engine where Sunwell injects its virtual
 * sky light ({@code LightEngineMixin}). That's why sunwell rooms render dark under Embeddium (and Iris,
 * which pulls Embeddium in). This reapplies the same boost at Embeddium's own read point, so the
 * projected sky light shows up.
 *
 * <p>Targeted by string and gated by {@code SunwellMixinPlugin} to load only when Embeddium is present.
 * {@code skyAt} is documented lock-free for exactly this off-thread (mesh worker) access. {@code
 * getBrightness(LightLayer, BlockPos)} is a vanilla {@code BlockAndTintGetter} method that {@code
 * WorldSlice} overrides, so it remaps through Forge's own SRG mapping like any other vanilla-method
 * override -- no different from targeting a real vanilla class.</p>
 *
 * <p>{@code @Pseudo}: unlike Amendments/Moonlight, Embeddium isn't wired into the compile classpath, so
 * the Mixin annotation processor can't resolve this target class at compile time. {@code @Pseudo} skips
 * that compile-time class-hierarchy check and defers resolution to runtime, where {@code
 * SunwellMixinPlugin} only ever applies this mixin once Embeddium is confirmed loaded.</p>
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.WorldSlice", remap = true)
public abstract class SodiumLevelSliceMixin {

    @Shadow @Final public ClientLevel world;

    @Inject(
            method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true)
    private void sunwell$boostSodiumLight(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!SunwellManager.ACTIVE || this.world == null) {
            return;
        }
        SunwellManager manager = SunwellManager.get(this.world);
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
