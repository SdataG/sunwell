package com.SdataG.sunwell.mixin.client.amendments;

import com.SdataG.sunwell.client.render.LanternOrbPresenter;
import com.SdataG.sunwell.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.mehvahdjukaar.amendments.common.tile.WallLanternBlockTile;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Sunwell orb inside an Amendments wall lantern, in Amendments' own pose.
 *
 * <p><b>Why here and not a separate renderer.</b> This used to be a {@code RenderLevelStageEvent}
 * handler that re-derived Amendments' transform from scratch — position, facing rotation, attachment
 * offset, swing angle and axis. Every one of those was a guess that had to match their renderer
 * exactly, and each wrong guess looked identical from the outside: an orb hanging outside the cage.
 * The swing axis alone cost several attempts ({@code Axis.XP} vs the {@code Axis.ZP} their bytecode
 * actually loads).</p>
 *
 * <p>Injecting inside their method sidesteps all of it: the pose already carries everything they did.
 * The injection point sits after {@code getModel()} and <b>before the cage is drawn</b>, which matters
 * for depth, not just convenience:</p>
 *
 * <pre>
 * translate(0.5, 0.875, 0.5)
 * mulPose(RotHlpr.rot(FACING))                       // which wall, which way round
 * mulPose(Axis.ZP.rotationDegrees(animation.getAngle(pt)))  // the swing, live
 * translate(-0.5, -0.75 - getAttachmentOffset(), -0.375)
 * getModel(...)                                      // &lt;- we inject here, orb drawn first
 * RenderUtil.renderBlock(...)                        // then the cage
 * popPose()
 * </pre>
 *
 * <p><b>Order is the whole trick.</b> Drawing the orb after the cage looked correct and rendered
 * nothing: the orb sits <em>inside</em> the cage, so once the cage's front glass has written depth,
 * every orb pixel fails the depth test and the cage appears to eat it. A floor lantern works because
 * its cage is drawn by the <em>chunk</em> renderer in the translucent pass, which runs after block
 * entities — orb first, glass blended over it. Amendments draws its cage inside its own block-entity
 * renderer, inverting that. Drawing the orb before {@code renderBlock} restores the floor lantern's
 * order: orb writes depth, cage glass blends over it.</p>
 *
 * <p>So the orb is drawn in the exact space the cage was drawn in — block-model space, 0..1 — and it
 * inherits the position, rotation and swing for free. If Amendments changes any of it, the orb
 * follows automatically instead of silently drifting out of the lantern.</p>
 */
@Mixin(value = net.mehvahdjukaar.amendments.client.renderers.WallLanternBlockTileRenderer.class, remap = false)
public final class WallLanternBlockTileRendererMixin {

    private static long sunwell$debugTick = Long.MIN_VALUE;

    private WallLanternBlockTileRendererMixin() {
    }

    // Inject AFTER getModel() and BEFORE the cage is drawn. Everything here is Amendments/Moonlight,
    // so remap = false throughout. require = 0: a future rename costs the wall orb, never a crash.
    @Inject(
            method = "renderLantern",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/mehvahdjukaar/amendments/client/WallLanternModelsManager;getModel"
                            + "(Lnet/minecraft/client/renderer/block/BlockModelShaper;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)"
                            + "Lnet/minecraft/client/resources/model/BakedModel;",
                    shift = At.Shift.AFTER
            ),
            remap = false,
            require = 0
    )
    private void sunwell$drawOrbInCage(
            WallLanternBlockTile tile,
            BlockState held,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            int overlay,
            boolean fancy,
            CallbackInfo callback
    ) {
        if (held == null || !held.is(ModBlocks.SUNWELL_LANTERN.get())) {
            return;
        }
        Level level = tile.getLevel();
        if (level == null) {
            return;
        }
        // Match the orb model to whichever cage model Amendments just drew.
        boolean hanging = held.hasProperty(LanternBlock.HANGING) && held.getValue(LanternBlock.HANGING);

        if (com.SdataG.sunwell.SunwellConfig.debugSkyState) {
            long tick = level.getGameTime();
            if (tick != sunwell$debugTick && tick % 40L == 0L) {
                sunwell$debugTick = tick;
                com.SdataG.sunwell.client.render.LanternOrbEffects.SkyOrb orb =
                        com.SdataG.sunwell.client.render.LanternOrbEffects.orbState(
                                level,
                                com.SdataG.sunwell.client.render.LanternOrbEffects.nightFactor(level),
                                com.SdataG.sunwell.client.render.LanternOrbEffects.rainFactor(level, partialTick),
                                com.SdataG.sunwell.client.render.LanternOrbEffects.thunderFactor(level, partialTick)).to();
                com.SdataG.sunwell.client.render.LanternOrbEffects.SkyOrb dummy = orb;
                com.SdataG.sunwell.Sunwell.LOGGER.info(
                        "[sunwell] wall orb: fired pos={} hanging={} orb={} model={} buffers={}",
                        tile.getBlockPos(), hanging, dummy,
                        com.SdataG.sunwell.client.render.SunwellOrbModels.resolveOrb(dummy, hanging),
                        buffers.getClass().getSimpleName());
            }
        }
        LanternOrbPresenter.render(
                level,
                tile.getBlockPos(),
                poseStack,
                buffers,
                held,
                hanging,
                partialTick,
                OverlayTexture.NO_OVERLAY,
                false
        );
    }
}
