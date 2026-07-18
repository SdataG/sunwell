package com.SdataG.sunwell.mixin.client;

import com.SdataG.sunwell.client.render.SunwellBoltRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps vanilla's lightning render for Sunwell's, but only for bolts inside a sunwell region.
 *
 * <p>The entity stays vanilla's, so the strike keeps every real behaviour — fire, damage, charged
 * creepers, copper oxidising. Only the look changes: vanilla draws a fat white pillar scaled for open
 * sky, which through a one-block hole in a ceiling reads as a bug rather than a bolt.</p>
 *
 * <p>{@link SunwellBoltRenderer#tryRender} decides ownership by asking whether the bolt is standing in
 * lit sunwell air, and returns false for anything else — a real storm outside stays vanilla's.</p>
 */
@Mixin(LightningBoltRenderer.class)
public final class LightningBoltRendererMixin {

    private LightningBoltRendererMixin() {
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LightningBolt;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void sunwell$replaceBolt(
            LightningBolt bolt,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            CallbackInfo callback
    ) {
        if (SunwellBoltRenderer.tryRender(bolt, partialTick, poseStack, buffers)) {
            callback.cancel();
        }
    }
}
