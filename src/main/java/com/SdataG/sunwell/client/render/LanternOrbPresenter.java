package com.SdataG.sunwell.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared orb draw path for block-entity (placed) and item (hand) renderers.
 *
 * <p>The orb presents the sky it stands in for: a warm sun with radiant shafts in clear daylight, a
 * pale dim moon with no shafts at night, and a flat desaturated overcast with no shafts under rain or
 * thunder. A lightning sky-flash momentarily snaps it to white.</p>
 */
public final class LanternOrbPresenter {

    /** How much the sky tint colours the vertex vs leaving atlas pixels visible. */
    private static final float TEXTURE_PRESERVE = 0.72F;

    /**
     * Pose at the midpoint of the orb -> cloud transformation. Baked models can't be vertex-morphed,
     * so the orb squashes flat and spreads wide, and the cloud un-squashes out of that same pose —
     * the model swap happens at the flattest instant, which reads as one continuous shape change.
     */
    private static final float MORPH_FLAT_Y = 0.35F;
    private static final float MORPH_SPREAD_XZ = 1.15F;

    private LanternOrbPresenter() {
    }

    public static void render(
            Level level,
            BlockPos effectPos,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            BlockState state,
            boolean hanging,
            float partialTick,
            int combinedOverlay,
            boolean viewSpace
    ) {
        float exposure = LanternOrbEffects.exposure(level, effectPos, partialTick);
        float flicker = LanternOrbEffects.textureFlicker(level, effectPos, partialTick);
        float bobOffset = LanternOrbEffects.bobOffset(level, effectPos, partialTick);
        float glowRadius = LanternOrbEffects.glowRadiusMultiplier(level, effectPos, partialTick);
        float glowAlpha = LanternOrbEffects.glowAlphaMultiplier(level, effectPos, partialTick);
        float rayPhase = LanternOrbEffects.rayPhase(level, effectPos, partialTick);

        // What sky is this orb standing in for? All three ride Minecraft's own curves: the vanilla
        // dusk ramp for night, the vanilla 0.01/tick weather ramps for rain and thunder. So the orb
        // changes exactly when and as fast as the real sky does — no timers of our own to drift.
        float night = LanternOrbEffects.nightFactor(level);
        float rain = LanternOrbEffects.rainFactor(level, partialTick);
        float thunder = LanternOrbEffects.thunderFactor(level, partialTick);
        float flash = LanternOrbEffects.skyFlash(level);

        // One mix drives the artwork and the light both, so they cannot disagree.
        LanternOrbEffects.SkyMix mix = LanternOrbEffects.skyMix(night, rain, thunder);
        LanternOrbEffects.OrbState orbState = LanternOrbEffects.orbState(level, night, rain, thunder);
        LanternOrbEffects.SkyLight sky = LanternOrbEffects.skyLight(mix, night);
        float brightness = Mth.clamp(sky.brightness() * flicker, 0.05F, 1.0F);
        float rays = sky.rays();
        BakedModel toModel = SunwellOrbModels.resolveOrb(orbState.to(), hanging);
        if (!SunwellOrbModels.isRenderable(toModel)) {
            return;
        }
        BakedModel fromModel = orbState.settled()
                ? null
                : SunwellOrbModels.resolveOrb(orbState.from(), hanging);

        // The squash sells the orb *becoming a cloud*: it flattens and spreads so the model swap lands
        // at the flattest instant. It keys off how cloud-shaped the sky is, so it peaks half way
        // through orb->cloud and is simply absent for sun->moon (one round orb becoming another round
        // orb of the same size — squashing that just made the moon look like it was shrinking).
        float cloudiness = orbState.cloudiness();
        float weight = 1.0F - 4.0F * cloudiness * (1.0F - cloudiness);   // 1 at either end, 0 mid
        float squashY = Mth.lerp(weight, MORPH_FLAT_Y, 1.0F);
        float spreadXZ = Mth.lerp(weight, MORPH_SPREAD_XZ, 1.0F);

        // Centre the halo/shafts on the orb model's real geometry, so re-modelled orbs just work.
        float pivotY = SunwellOrbModels.centerY(toModel, LanternOrbEffects.orbPivotY(hanging));

        poseStack.pushPose();
        poseStack.translate(0.0D, bobOffset, 0.0D);

        // Halo takes the same sky colour; a lightning flash whitens and swells it. Drawn outside the
        // morph squash so the glow stays round while the orb flattens.
        OrbGlowOverlay.render(
                poseStack,
                bufferSource,
                0.5F,
                pivotY,
                0.5F,
                Mth.lerp(flash, sky.red(), 1.0F),
                Mth.lerp(flash, sky.green(), 1.0F),
                Mth.lerp(flash, sky.blue(), 1.0F),
                exposure * brightness,
                glowRadius * (1.0F + 0.35F * flash),
                // Multiplying the alpha multiplier scales the whole halo, base floor included.
                glowAlpha * sky.glow(),
                rayPhase,
                rays,
                flash,
                viewSpace
        );

        // God-ray shafts down the cone -- shader-only (they rely on the pack's bloom), ceiling lanterns
        // only (a floor lamp has no room below), and sun-only via `rays` so a moon/overcast casts none.
        if (hanging && !viewSpace && rays > 0.01F && com.SdataG.sunwell.ShaderCompat.shadersActive()) {
            OrbGlowOverlay.renderGodRays(poseStack, bufferSource, 0.5F, pivotY, 0.5F,
                    sky.red(), sky.green(), sky.blue(), rays * exposure * brightness, rayPhase);
        }

        // Squash/spread the orb about its own centre for the transformation.
        poseStack.pushPose();
        poseStack.translate(0.5D, pivotY, 0.5D);
        poseStack.scale(spreadXZ, squashY, spreadXZ);
        poseStack.translate(-0.5D, -pivotY, -0.5D);

        if (orbState.settled()) {
            // Settled: CUTOUT. Alpha-tested with no sorting, so the overlapping orb boxes can't
            // shimmer. This is the state it sits in ~all the time.
            drawOrbModel(poseStack, bufferSource, state, toModel, sky, brightness, flash,
                    combinedOverlay, 1.0F, false);
        } else {
            // Dissolving: TRANSLUCENT so alpha actually blends. Any sorting wobble here is hidden by
            // the fade itself, and it only lasts a few seconds.
            drawOrbModel(poseStack, bufferSource, state, fromModel, sky, brightness, flash,
                    combinedOverlay, 1.0F - orbState.fade(), true);
            drawOrbModel(poseStack, bufferSource, state, toModel, sky, brightness, flash,
                    combinedOverlay, orbState.fade(), true);
        }

        poseStack.popPose();
        poseStack.popPose();
    }

    private static void drawOrbModel(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            BlockState state,
            BakedModel orbModel,
            LanternOrbEffects.SkyLight sky,
            float brightness,
            float flash,
            int combinedOverlay,
            float alpha,
            boolean dissolving
    ) {
        if (orbModel == null || alpha <= 0.01F) {
            return;
        }
        // Each sky now has its own artwork (sun / moon / rain / storm atlases), so the tint only has
        // to nudge the art — it no longer has to invent the moon's colour out of the sun texture.
        // It used to be applied twice (once via this lerp, once as a second multiply), which stacked
        // down to ~23% brightness at night and buried the orb. Once is correct.
        float red = Mth.lerp(TEXTURE_PRESERVE, sky.red(), 1.0F) * brightness;
        float green = Mth.lerp(TEXTURE_PRESERVE, sky.green(), 1.0F) * brightness;
        float blue = Mth.lerp(TEXTURE_PRESERVE, sky.blue(), 1.0F) * brightness;

        // Snap to full white in sync with a lightning sky-flash.
        red = Mth.lerp(flash, red, 1.0F);
        green = Mth.lerp(flash, green, 1.0F);
        blue = Mth.lerp(flash, blue, 1.0F);

        // Under a shaderpack, give the orb a full-bright lightmap so the pack treats it as a light
        // source and blooms it. The RGB above still carries the day/night/weather dimming, so a night
        // moon blooms softly while a day sun blooms hard. Vanilla keeps the shaded lightmap unchanged.
        int light = (flash > 0.5F || com.SdataG.sunwell.ShaderCompat.shadersActive())
                ? LightTexture.FULL_BRIGHT
                : LanternOrbEffects.shadedLight(brightness, 1.0F, 1.0F);

        // Cutout while settled: alpha-tested, no sorting, so the overlapping orb boxes can't shimmer.
        // Translucent only while dissolving, because cutout's alpha test can't express a partial fade.
        // renderModel takes no alpha, so AlphaVertexConsumer scales it on the way to the buffer.
        VertexConsumer buffer = bufferSource.getBuffer(dissolving ? RenderType.translucent() : RenderType.cutout());
        if (dissolving) {
            buffer = new AlphaVertexConsumer(buffer, alpha);
        }

        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                poseStack.last(),
                buffer,
                state,
                orbModel,
                red,
                green,
                blue,
                light,
                combinedOverlay
        );
    }
}
