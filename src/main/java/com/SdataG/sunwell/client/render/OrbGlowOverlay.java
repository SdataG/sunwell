package com.SdataG.sunwell.client.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    // God-ray beams (shader-only): count, how far they reach into the cone, how much they fan out, and
    // the shaft half-width at the orb vs the far end. Kept subtle -- the pack's bloom does the work.
    private static final int BEAM_COUNT = 7;
    private static final float BEAM_LENGTH = 5.0F;
    private static final float BEAM_SPREAD = 0.42F;
    private static final float BEAM_HALF_START = 0.05F;
    private static final float BEAM_HALF_END = 0.16F;
    private static final float BEAM_ALPHA = 0.12F;

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

        // Sun shafts only — absent at night and under weather.
        if (rayStrength > 0.01F) {
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

    /**
     * Downward light-shaft "god ray" beams fanning from the orb into the cone. SHADER-ONLY: these lean
     * entirely on the pack's bloom to bloom out into soft volumetric shafts. Without a pack there is no
     * bloom, so a bare additive quad just looks like jank -- the caller skips this when no shader is
     * active. The beams are drawn in the lantern's WORLD frame (not camera-facing flat) and billboarded
     * around each beam's own axis toward the camera, so they read as real downward shafts from any angle.
     * {@link GlowRenderType#ORB_GLOW} depth-tests, so a beam is naturally clipped where it meets the floor.
     */
    public static void renderGodRays(PoseStack poseStack, MultiBufferSource bufferSource,
                                     float centerX, float centerY, float centerZ,
                                     float red, float green, float blue, float strength, float phase) {
        if (strength <= 0.01F) {
            return;
        }
        VertexConsumer c = bufferSource.getBuffer(GlowRenderType.ORB_GLOW);
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, centerZ);
        Matrix4f m = poseStack.last().pose();
        // Camera position in the orb's local frame: invert the pose and map the view origin back.
        Vector3f cam = new Matrix4f(m).invert().transformPosition(new Vector3f(0.0F, 0.0F, 0.0F));

        float baseA = BEAM_ALPHA * strength;
        for (int i = 0; i < BEAM_COUNT; i++) {
            float angle = phase * 0.5F + (float) (i * 2.0 * Math.PI / BEAM_COUNT);
            float shimmer = 0.8F + 0.2F * Mth.sin(phase * 4.0F + i * 1.7F);
            float len = BEAM_LENGTH * shimmer;
            Vector3f dir = new Vector3f(Mth.cos(angle) * BEAM_SPREAD, -1.0F, Mth.sin(angle) * BEAM_SPREAD).normalize();
            Vector3f end = new Vector3f(dir).mul(len);
            drawBeam(c, m, new Vector3f(0.0F, 0.0F, 0.0F), end, cam,
                    BEAM_HALF_START, BEAM_HALF_END, red * baseA, green * baseA, blue * baseA);
        }
        poseStack.popPose();
    }

    /** One billboarded tapering shaft: bright at the orb, fading to nothing at its far end. */
    private static void drawBeam(VertexConsumer c, Matrix4f m, Vector3f start, Vector3f end, Vector3f cam,
                                 float halfStart, float halfEnd, float cr, float cg, float cb) {
        Vector3f axis = new Vector3f(end).sub(start);
        if (axis.lengthSquared() < 1.0E-6F) {
            return;
        }
        axis.normalize();
        Vector3f sideStart = new Vector3f(axis).cross(new Vector3f(cam).sub(start));
        Vector3f sideEnd = new Vector3f(axis).cross(new Vector3f(cam).sub(end));
        if (sideStart.lengthSquared() < 1.0E-6F || sideEnd.lengthSquared() < 1.0E-6F) {
            return;
        }
        sideStart.normalize().mul(halfStart);
        sideEnd.normalize().mul(halfEnd);
        beamVtx(c, m, start, sideStart, 1.0F, cr, cg, cb);
        beamVtx(c, m, end, sideEnd, 1.0F, 0.0F, 0.0F, 0.0F);
        beamVtx(c, m, end, sideEnd, -1.0F, 0.0F, 0.0F, 0.0F);
        beamVtx(c, m, start, sideStart, -1.0F, cr, cg, cb);
    }

    private static void beamVtx(VertexConsumer c, Matrix4f m, Vector3f p, Vector3f side, float k,
                                float cr, float cg, float cb) {
        c.vertex(m, p.x + side.x * k, p.y + side.y * k, p.z + side.z * k).color(cr, cg, cb, 1.0F).endVertex();
    }
}
