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
 * <p><b>Do not mark this {@code @Pseudo}.</b> An earlier revision did, because Embeddium wasn't wired
 * into the compile classpath and {@code @Pseudo} lets the Mixin annotation processor skip the class it
 * can't resolve. But that skip also disables refmap generation for this injector: with no refmap entry,
 * {@code remap = true} has nothing to remap from, so at runtime Mixin falls back to searching {@code
 * WorldSlice}'s compiled method table for the <em>literal</em> name {@code "getBrightness"} -- and since
 * this method overrides a vanilla interface method, Embeddium's build reobfuscates it to Forge's SRG name
 * before shipping, so no method is literally called {@code getBrightness} at runtime. That mismatch is
 * exactly what throws {@code InvalidInjectionException: ... could not find any targets matching
 * 'getBrightness(...)'}. Embeddium is now a real {@code compileOnly} dependency (see {@code build.gradle},
 * mirroring the existing Amendments/Moonlight setup) so the annotation processor can resolve {@code
 * WorldSlice} for real and generate the correct SRG mapping, the same way it already does for every other
 * mixin in this mod that targets a genuine vanilla method.</p>
 */
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
