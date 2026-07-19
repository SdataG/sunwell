package com.SdataG.sunwell.client.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/**
 * Soft radial glow halo plus the "radiant" sun shafts for the orb.
 *
 * <p>All geometry is camera-facing and drawn additively with {@link GlowRenderType#ORB_GLOW}
 * (depth-test, no depth-write) so nothing z-fights/tears. Center vertices carry premultiplied color
 * and rim/tip vertices are black (zero additive contribution), giving smooth gradients.</p>
 *
 * <p>The shafts are a <em>sunbeam</em>: they follow {@code rayStrength}, which is full in clear
 * daylight and fades to nothing at night or under rain/thunder — a moon or an overcast sky casts no
 * god rays. A lightning sky-flash momentarily swells and whitens the halo.</p>
 */
public final class OrbGlowOverlay {

    private static final int DISC_SEGMENTS = 24;
    private static final int RAY_COUNT = 8;
    private static final float RADIUS = 0.55F;
    private static final float BASE_ALPHA = 0.17F;
    private static final float EXPOSURE_ALPHA = 0.34F;

    private OrbGlowOverlay() {
    }

    public static void render(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            float centerX,
            float centerY,
            float centerZ,
            float red,
            float green,
            float blue,
            float alphaScale,
            float glowRadiusMultiplier,
            float glowAlphaMultiplier,
            float rayPhase,
            float rayStrength,
            float flash,
            boolean viewSpace
    ) {
        float centerAlpha = (BASE_ALPHA + EXPOSURE_ALPHA * Mth.clamp(alphaScale, 0.0F, 1.0F)) * glowAlphaMultiplier;
        // Swell the halo brightly in sync with a lightning sky-flash.
        centerAlpha = Mth.lerp(flash, centerAlpha, Math.max(centerAlpha, 0.7F));
        if (centerAlpha <= 0.002F) {
            return;
        }
        float radius = RADIUS * glowRadiusMultiplier;

        VertexConsumer consumer = bufferSource.getBuffer(GlowRenderType.ORB_GLOW);
        Quaternionf cameraRotation = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();

        poseStack.pushPose();
        poseStack.translate(centerX, centerY, centerZ);
        // World lanterns billboard the halo/shafts to the camera. A HELD item is already rendered in
        // view space, so applying the world camera rotation there makes the shafts swing as the player
        // turns -- skip it and draw in the item's own frame so the rays stay put and turn with you.
        if (!viewSpace) {
            poseStack.mulPose(cameraRotation);
        }
        Matrix4f matrix = poseStack.last().pose();

        drawDisc(consumer, matrix, radius, red * centerAlpha, green * centerAlpha, blue * centerAlpha);

        // Sun shafts only — absent at night/weather, and NOT on a held item (they read as jank in hand).
        if (!viewSpace && rayStrength > 0.01F) {
            float rayAlpha = centerAlpha * rayStrength;
            if (rayAlpha > 0.002F) {
                float rayLength = radius * (1.2F + 0.6F * rayStrength);
                drawRays(consumer, matrix, red * rayAlpha, green * rayAlpha, blue * rayAlpha,
                        radius, rayLength, rayPhase, rayStrength);
            }
        }

        poseStack.popPose();
    }

    /** Filled disc: bright center fading to a transparent rim (real round bloom). */
    private static void drawDisc(VertexConsumer c, Matrix4f m, float radius, float cr, float cg, float cb) {
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / DISC_SEGMENTS);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / DISC_SEGMENTS);
            float x0 = Mth.cos(a0) * radius;
            float y0 = Mth.sin(a0) * radius;
            float x1 = Mth.cos(a1) * radius;
            float y1 = Mth.sin(a1) * radius;
            c.vertex(m, 0.0F, 0.0F, 0.0F).color(cr, cg, cb, 1.0F).endVertex();
            c.vertex(m, x0, y0, 0.0F).color(0.0F, 0.0F, 0.0F, 1.0F).endVertex();
            c.vertex(m, x1, y1, 0.0F).color(0.0F, 0.0F, 0.0F, 1.0F).endVertex();
            c.vertex(m, 0.0F, 0.0F, 0.0F).color(cr, cg, cb, 1.0F).endVertex();
        }
    }

    /** Tapering additive shafts radiating from the orb, slowly rotating with per-ray shimmer. */
    private static void drawRays(VertexConsumer c, Matrix4f m, float br, float bg, float bb,
                                 float radius, float length, float phase, float rayStrength) {
        float halfWidth = radius * 0.09F;
        for (int i = 0; i < RAY_COUNT; i++) {
            float angle = phase + (float) (i * 2.0 * Math.PI / RAY_COUNT);
            float shimmer = 0.75F + 0.25F * Mth.sin(phase * 6.0F + i * 1.7F) * rayStrength;
            float len = length * shimmer;
            float dx = Mth.cos(angle);
            float dy = Mth.sin(angle);
            float px = -dy * halfWidth;
            float py = dx * halfWidth;
            float tipX = dx * len;
            float tipY = dy * len;
            c.vertex(m, px, py, 0.0F).color(br, bg, bb, 1.0F).endVertex();
            c.vertex(m, tipX + px * 0.25F, tipY + py * 0.25F, 0.0F).color(0.0F, 0.0F, 0.0F, 1.0F).endVertex();
            c.vertex(m, tipX - px * 0.25F, tipY - py * 0.25F, 0.0F).color(0.0F, 0.0F, 0.0F, 1.0F).endVertex();
            c.vertex(m, -px, -py, 0.0F).color(br, bg, bb, 1.0F).endVertex();
        }
    }
}
