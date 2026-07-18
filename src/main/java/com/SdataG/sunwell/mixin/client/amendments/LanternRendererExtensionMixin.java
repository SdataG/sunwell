package com.SdataG.sunwell.mixin.client.amendments;

import com.SdataG.sunwell.client.render.LanternOrbPresenter;
import com.SdataG.sunwell.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the animated Sunwell orb into the held/worn lantern cage that Amendments renders.
 *
 * <p><b>The orb must be drawn in item space, not hand space.</b> Amendments' {@code renderLanternModel}
 * doesn't touch the pose itself — it hands off to
 * {@code ItemRenderer.render(stack, ItemDisplayContext.NONE, leftHand, pose, ..., blockModel)}, and
 * <em>that</em> is what moves into the model's space:</p>
 *
 * <pre>
 * poseStack.pushPose();
 * model.applyTransform(NONE, poseStack, leftHand);   // identity for NONE
 * poseStack.translate(-0.5F, -0.5F, -0.5F);          // block model 0..1 -> centred -0.5..0.5
 * ...draw...
 * poseStack.popPose();                               // undone before our @At("RETURN") runs
 * </pre>
 *
 * <p>So by the time this injector fires, that half-block shift has been unwound. Drawing the orb's
 * block-space geometry straight into the raw hand pose put it a half block off at full block scale —
 * the pale slab floating over the lantern. Re-applying the same {@code -0.5} translate lands the orb
 * exactly where the cage was drawn.</p>
 */
@Mixin(value = net.mehvahdjukaar.amendments.client.renderers.LanternRendererExtension.class, remap = false)
public final class LanternRendererExtensionMixin {

    private LanternRendererExtensionMixin() {
    }

    @Inject(method = "renderLanternModel", at = @At("RETURN"), remap = false)
    private static void sunwell$overlayHandOrb(
            LivingEntity entity,
            ItemStack stack,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int combinedLight,
            boolean leftHand,
            CallbackInfo callback
    ) {
        if (stack.isEmpty()) {
            return;
        }

        if (!stack.is(ModBlocks.SUNWELL_LANTERN_ITEM.get())) {
            return;
        }

        Level level = entity.level();
        if (level == null) {
            return;
        }

        // Amendments forces HANGING=false and renders the floor cage, so the floor orb is the match.
        BlockState state = ModBlocks.SUNWELL_LANTERN.get().defaultBlockState();
        if (state.hasProperty(LanternBlock.HANGING)) {
            state = state.setValue(LanternBlock.HANGING, false);
        }
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        BlockPos effectPos = entity.blockPosition();

        poseStack.pushPose();
        // Re-enter the item space ItemRenderer.render used and then popped. See the class javadoc.
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        LanternOrbPresenter.render(
                level,
                effectPos,
                poseStack,
                bufferSource,
                state,
                false,
                partialTick,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                false
        );
        poseStack.popPose();
    }
}
