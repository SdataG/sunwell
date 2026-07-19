package com.SdataG.sunwell.client.render;

import com.SdataG.sunwell.SunwellManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Sunwell's own lightning: one jagged bolt that leaps from the lantern's orb, forks and wisps on its
 * way down, and sheds fading ghost layers as it dies.
 *
 * <p>Replaces the look, not the entity. The bolt is still vanilla's {@link LightningBolt}, so the
 * strike keeps every real behaviour — the sky flash, thunder, fire, damage, charged creepers, copper
 * oxidising — and the orb whitens off the same {@code setSkyFlashTime} the entity fires. Only the
 * render is swapped, by {@code LightningBoltRendererMixin}. Vanilla draws a fat white pillar scaled
 * for open sky, which through a one-block hole in a ceiling reads as a bug.</p>
 *
 * <p>The bolt only knows where it landed, so the renderer asks the manager which lamp it fell from
 * ({@link SunwellManager#nearestSourceAbove}) and draws from that orb down to the strike. Because the
 * strike sits somewhere in the lamp's cone, the two ends are usually offset — so the channel arcs
 * across from the orb to its hit point rather than dropping straight.</p>
 *
 * <p><b>One bolt, not three.</b> The channel is built once — a single jagged, bowed polyline seeded
 * from the entity id, so its shape is fixed for the bolt's whole life instead of buzzing. The core
 * and its soft halo trace that <em>same</em> path, and the forks branch off it. The afterimage is not
 * extra bolts drawn alongside: it is that one path copied and pushed sideways, growing brighter and
 * wider only as the bolt fades — so a dying strike smears into ghost layers and vanishes.</p>
 */
public final class SunwellBoltRenderer {

    /** Roughly one jag point per this many blocks of length. Small = finer, twitchier lightning. */
    private static final float SEGMENT_LENGTH = 0.5F;

    /** How far mid-path jag points wander off the channel. */
    private static final float JAG = 0.20F;

    /** Arc bow sideways, as a fraction of the orb-to-strike horizontal offset (with a floor). */
    private static final float BOW_FRACTION = 0.28F;
    private static final float BOW_MIN = 0.35F;

    /** Where the orb sits above the lamp block origin. */
    private static final float ORB_HEIGHT = 0.55F;

    /** Thin bright core, soft glow, and a wide luminous bloom (the photo look). */
    private static final float CORE_WIDTH = 0.035F;
    /** Inner hyper-bright filament, thinner than the core -- fakes a bloom right at the white-hot centre. */
    private static final float CORE_HOT_WIDTH = 0.018F;
    // HALO and BLOOM are the fake-bloom-via-geometry layers: nested wide, soft, dim quads around the
    // core. This is what makes the bolt look blown-out/glowing in PLAIN VANILLA with no post-process
    // bloom pass to lean on -- a shaderpack's own bloom stacks on top of these and looks even softer,
    // but they're sized and lit to already read as a glow on their own.
    private static final float HALO_WIDTH = 0.13F;
    private static final float BLOOM_WIDTH = 0.42F;


    /** Whole VFX length in ticks (~half a second at 20 tps). */
    private static final float LIFE_TICKS = 10.0F;

    /** Beat boundaries over the 10-tick life: spread (leader) ticks 0-5, strike (return) 5-6.8, fade after. */
    private static final float LEADER_END = 0.5F;
    // Slightly longer than a single tick (was 0.6) so the climbing return-stroke pulse has enough frames
    // to visibly travel the FULL distance from strike to lamp, instead of snapping through almost
    // instantly. Still snappy -- under 2 ticks total.
    private static final float RETURN_END = 0.68F;

    /** The tick the return stroke lands on — when the thunder/impact sound should play. */
    public static final int STRIKE_TICK = Math.round(LEADER_END * LIFE_TICKS);

    /** How much further the leader must descend for a branch to grow to full length. */
    private static final float BRANCH_GROWTH = 0.35F;

    private SunwellBoltRenderer() {
    }

    /**
     * Draw our bolt instead of vanilla's, if this strike belongs to a sunwell.
     *
     * @return true if we handled it and vanilla's render should be cancelled.
     */
    public static boolean tryRender(LightningBolt bolt, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        Level level = bolt.level();
        SunwellManager manager = SunwellManager.get(level);
        if (manager == null) {
            return false;
        }
        BlockPos strike = bolt.blockPosition();
        // Ours if it came from a sunwell lamp. Check the lamp first: a rod-caught bolt lands ON the rod
        // block (unlit), so keying only on lit air would hand rod strikes back to vanilla's fat pillar.
        BlockPos source = manager.nearestSourceAbove(strike);
        if (source == null && manager.baseSkyAt(strike) <= 0) {
            return false;
        }

        // Top anchor: the orb of the lamp this bolt fell from. Fall back to straight up the column.
        Vector3f top;
        if (source != null) {
            top = new Vector3f(
                    (float) (source.getX() + 0.5D - bolt.getX()),
                    (float) (source.getY() + ORB_HEIGHT - bolt.getY()),
                    (float) (source.getZ() + 0.5D - bolt.getZ()));
        } else {
            float h = 1.0F;
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int up = 1; up <= 64; up++) {
                probe.set(strike.getX(), strike.getY() + up, strike.getZ());
                if (manager.baseSkyAt(probe) <= 0) {
                    break;
                }
                h = up;
            }
            top = new Vector3f(0.0F, h, 0.0F);
        }
        Vector3f bottom = new Vector3f(0.0F, 0.0F, 0.0F); // strike = bolt's own position (local origin)

        float age = bolt.tickCount + partialTick;
        float t = age / LIFE_TICKS;
        if (t >= 1.0F) {
            LightningFlashLight.clear(bolt.getId()); // spent -- release its in-world light immediately
            return true; // ours, but spent -- draw nothing rather than let vanilla back in
        }
        float flick = 0.7F + 0.3F * Mth.abs(Mth.sin(age * 15.0F));

        // Three beats. LEADER: a dim channel creeps down from the orb, searching for the floor.
        // RETURN STROKE: the instant it connects, a brilliant pulse sweeps back UP from the hit point
        // to the lamp -- the bright flash real lightning fires along the leader once it grounds. FADE.
        //   reach  = how far down the leader has grown (fraction of the path).
        //   pulseC = where the bright return-stroke band currently sits along the path.
        float reach;
        float baseA;
        float branchBright;
        float lampGlow;
        float impactLight; // strike-point flash: 0 until it lands, peaks on the return stroke, fades after
        float flashRadius; // how far the in-world BLOCK light reaches -- room-wide on the strike tick itself
        // 0 until the fade beat starts, then ramps 0->1 over the fade -- how far the "close" has swept up
        // from the strike end. Fed into the trunk's own tipFade, which already fades/narrows toward
        // frac=1 (the strike/bottom end), so the bolt visibly closes from the bottom up as it dies.
        float closeFrac;
        float pulseC = 0.0F;
        float pulseW = 0.16F;  // narrow enough to read as a distinct travelling band, not a wide wash
        float pulseA = 0.0F;
        if (t < LEADER_END) {
            reach = Mth.clamp(t / LEADER_END, 0.0F, 1.0F);
            baseA = 0.30F * flick;
            branchBright = baseA * 0.9F;      // spread: branches clearly visible while they grow out
            lampGlow = 0.12F + reach * 0.55F; // and the lamp charges brighter as the leader reaches down
            impactLight = 0.0F;               // hasn't struck yet -- nothing to flash
            flashRadius = 8.0F;
            closeFrac = 0.0F;
        } else if (t < RETURN_END) {
            float u = (t - LEADER_END) / (RETURN_END - LEADER_END);
            reach = 1.0F;
            baseA = 0.5F;
            pulseC = 1.0F - u; // frac 1 = strike, frac 0 = orb: the band runs hit -> lamp
            pulseA = 2.2F;     // the climbing return stroke -- a lot of bloom, not a subtle highlight
            branchBright = 0.0F;              // strike: the return stroke washes the branches out --
                                               // a clean flash, branches return in the fade
            lampGlow = 1.0F;                  // lamp flares at the strike
            impactLight = 1.0F;               // the flash: peak brightness the instant it grounds
            flashRadius = 24.0F;              // the strike tick itself: brighten the WHOLE room, not just
                                               // a local falloff near the impact point
            closeFrac = 0.0F;
        } else {
            float f = 1.0F - (t - RETURN_END) / (1.0F - RETURN_END);
            reach = 1.0F;
            baseA = 0.6F * f * flick;
            branchBright = baseA * 0.85F;     // fade: branches brighter in the afterglow, more seeable
            lampGlow = baseA;
            impactLight = f;                  // afterglow: the impact light fades out with the bolt
            closeFrac = 1.0F - f;             // 0 at the start of the fade, 1 once it's fully closed
            flashRadius = 8.0F;                // contracts back down to a local glow as it fades
        }

        // Register the actual in-world BLOCK light this bolt casts at its strike point (read back by
        // LightEngineMixin), and a matching additive bloom quad drawn below at the strike end of the
        // channel -- so the strike isn't just glowing geometry, it visibly lights the room around it.
        LightningFlashLight.update(bolt.getId(), strike, impactLight, flashRadius);

        RandomSource random = RandomSource.create(bolt.getId() * 31L);
        VertexConsumer buffer = buffers.getBuffer(GlowRenderType.ORB_GLOW);
        Matrix4f matrix = poseStack.last().pose();
        Vector3f camera = new Vector3f(
                (float) (Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().x - bolt.getX()),
                (float) (Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y - bolt.getY()),
                (float) (Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().z - bolt.getZ()));

        // Build the ONE channel. Everything below traces this same path -- no offset copies, so the
        // afterimage stays put instead of spreading.
        Vector3f[] path = buildPath(random, top, bottom);

        // One billboard side vector per path point (shared by the quads on either side of it), so the
        // ribbon is continuous and the jag corners don't split into visible flat panels.
        Vector3f[] sides = computeSides(path, camera);

        // Bloom, halo, then the thin white core -- each grown to `reach` and lit by the moving pulse, and
        // (via closeFrac, reusing the same tip-fade/taper the branches use toward frac=1) fading AND
        // narrowing from the strike/bottom end upward once the bolt starts to die -- the bolt visibly
        // closes from the ground up instead of dimming everywhere at once.
        drawChannel(buffer, matrix, path, sides, BLOOM_WIDTH, 0.6F, 0.72F, 1.0F, reach, 0.14F * baseA, pulseC, pulseW, 1.1F * pulseA, closeFrac);
        drawChannel(buffer, matrix, path, sides, HALO_WIDTH, 0.7F, 0.82F, 1.0F, reach, 0.32F * baseA, pulseC, pulseW, 1.6F * pulseA, closeFrac);
        drawChannel(buffer, matrix, path, sides, CORE_WIDTH, 0.97F, 0.98F, 1.0F, reach, baseA * 1.3F, pulseC, pulseW, pulseA * 1.8F, closeFrac);
        // Extra hyper-bright filament INSIDE the core -- an over-bright additive pass (the render type is
        // additive, so this stacks on top of the core rather than replacing it) that fakes a bloom right
        // at the white-hot centre, the way a photographed bolt blows out to pure white at its middle.
        drawChannel(buffer, matrix, path, sides, CORE_HOT_WIDTH, 1.0F, 1.0F, 1.0F, reach, baseA * 1.7F, pulseC, pulseW, pulseA * 2.2F, closeFrac);

        // A big round bloom that RIDES the return-stroke pulse up the channel -- the "climbing" part of
        // the strike, made unmistakably bright instead of leaning only on the channel's own width. This
        // is on top of the widened/brighter pulse bands above, not a replacement for them.
        if (pulseA > 0.0F) {
            int pulseIdx = Mth.clamp(Math.round(pulseC * (path.length - 1)), 0, path.length - 1);
            Vector3f pulsePos = path[pulseIdx];
            drawGlow(buffer, matrix, camera, pulsePos, 1.7F, 0.55F, 0.85F, 0.92F, 1.0F);
            drawGlow(buffer, matrix, camera, pulsePos, 0.75F, 1.0F, 1.0F, 1.0F, 1.0F);
        }

        // Branches during the spread and the fade, but not the strike flash (see phase block above).
        if (branchBright > 0.05F) {
            Vector3f boltWorldPos = new Vector3f((float) bolt.getX(), (float) bolt.getY(), (float) bolt.getZ());
            drawBranches(buffer, matrix, camera, random, path, branchBright, reach, level, boltWorldPos, closeFrac);
        }

        // The lamp itself brightens as the leader charges down, flares at the strike, then fades -- a
        // soft bloom at the orb end of the channel (path[0]).
        drawGlow(buffer, matrix, camera, path[0], 0.55F, lampGlow * 0.45F, 0.7F, 0.82F, 1.0F);
        drawGlow(buffer, matrix, camera, path[0], 0.26F, lampGlow, 0.96F, 0.98F, 1.0F);

        // Impact flash: a bright pop at the STRIKE end of the channel (path[last]), separate from the
        // lamp glow above -- the visual half of the strike's lighting impact, paired with the real
        // BLOCK-light flare registered into LightningFlashLight just above. Three nested layers (wide
        // dim -> medium -> tight hyper-bright), the same fake-bloom-via-geometry trick as the core, so it
        // reads as an unmistakable flash even without a shaderpack's own bloom pass on top.
        if (impactLight > 0.02F) {
            Vector3f strikePoint = path[path.length - 1];
            drawGlow(buffer, matrix, camera, strikePoint, 2.2F, impactLight * 0.35F, 0.65F, 0.78F, 1.0F);
            drawGlow(buffer, matrix, camera, strikePoint, 1.1F, impactLight * 0.8F, 0.8F, 0.88F, 1.0F);
            drawGlow(buffer, matrix, camera, strikePoint, 0.45F, impactLight * 1.4F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    /**
     * The single channel, as a jagged, bowed polyline from the orb ({@code path[0]}) to the strike
     * ({@code path[last]}). Both ends are exact; the wander in between is fixed by {@code random}, which
     * is seeded from the entity id, so the shape holds still for the bolt's life.
     */
    private static Vector3f[] buildPath(RandomSource random, Vector3f top, Vector3f bottom) {
        Vector3f delta = new Vector3f(bottom).sub(top);
        int steps = Math.max(4, Mth.ceil(delta.length() / SEGMENT_LENGTH));
        float horizLen = new Vector3f(delta.x, 0.0F, delta.z).length();
        float reach = Math.max(BOW_MIN, horizLen * BOW_FRACTION);

        // Two control offsets bow the channel; being seeded, they're stable but different per bolt.
        Vector3f ctrlA = randomBow(random, reach);
        Vector3f ctrlB = randomBow(random, reach);

        Vector3f[] pts = new Vector3f[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            Vector3f p = new Vector3f(top).lerp(bottom, t);
            float wA = envelope(t, 0.33F);
            float wB = envelope(t, 0.66F);
            p.add(ctrlA.x * wA + ctrlB.x * wB, ctrlA.y * wA + ctrlB.y * wB, ctrlA.z * wA + ctrlB.z * wB);
            float jag = t * (1.0F - t) * 4.0F; // 0 at both anchors
            p.add((random.nextFloat() - 0.5F) * JAG * 2.0F * jag,
                    (random.nextFloat() - 0.5F) * JAG * jag,
                    (random.nextFloat() - 0.5F) * JAG * 2.0F * jag);
            pts[i] = p;
        }
        return pts;
    }

    /**
     * A billboard "side" vector at every path point, shared by the two quads meeting there. Using the
     * SMOOTHED tangent (average of the incoming and outgoing directions) means adjacent quads line up
     * edge-to-edge instead of each facing its own way — which is what turned the jag corners into flat
     * panels with gaps between them.
     */
    private static Vector3f[] computeSides(Vector3f[] pts, Vector3f camera) {
        int n = pts.length;
        Vector3f[] sides = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            Vector3f tan = new Vector3f();
            if (i > 0) {
                Vector3f d = new Vector3f(pts[i]).sub(pts[i - 1]);
                if (d.lengthSquared() > 1.0E-8F) {
                    tan.add(d.normalize());
                }
            }
            if (i < n - 1) {
                Vector3f d = new Vector3f(pts[i + 1]).sub(pts[i]);
                if (d.lengthSquared() > 1.0E-8F) {
                    tan.add(d.normalize());
                }
            }
            if (tan.lengthSquared() < 1.0E-8F) {
                tan.set(0.0F, 1.0F, 0.0F);
            }
            Vector3f toCam = new Vector3f(camera).sub(pts[i]);
            Vector3f side = new Vector3f(tan).cross(toCam);
            if (side.lengthSquared() < 1.0E-8F) {
                side.set(1.0F, 0.0F, 0.0F);
            }
            sides[i] = side.normalize();
        }
        return sides;
    }

    /**
     * Draw a continuous ribbon along {@code pts}, using the shared per-point {@code sides} so quads
     * join without seams. Spans {@code [centerOffset - halfWidth, centerOffset + halfWidth]} along the
     * side vector, so an off-centre {@code centerOffset} gives one rim of the hollow tube.
     */
    private static void drawRibbon(VertexConsumer buffer, Matrix4f matrix, Vector3f[] pts, Vector3f[] sides,
                                   float halfWidth, float centerOffset, float alpha, float r, float g, float b) {
        if (alpha <= 0.004F || pts.length < 2) {
            return;
        }
        float cr = r * alpha;
        float cg = g * alpha;
        float cb = b * alpha;
        float lo = centerOffset - halfWidth;
        float hi = centerOffset + halfWidth;
        for (int i = 1; i < pts.length; i++) {
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], lo, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], lo, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], hi, cr, cg, cb);
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], hi, cr, cg, cb);
        }
    }

    private static void vtx(VertexConsumer buffer, Matrix4f m, Vector3f p, Vector3f side, float k,
                            float cr, float cg, float cb) {
        buffer.addVertex(m, p.x + side.x * k, p.y + side.y * k, p.z + side.z * k).setColor(cr, cg, cb, 1.0F);
    }

    /** A soft camera-facing bloom at a point -- used to flare the lamp as the leader charges. */
    private static void drawGlow(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, Vector3f pos,
                                 float radius, float alpha, float r, float g, float b) {
        if (alpha <= 0.004F) {
            return;
        }
        Vector3f toCam = new Vector3f(camera).sub(pos);
        if (toCam.lengthSquared() < 1.0E-6F) {
            return;
        }
        toCam.normalize();
        Vector3f up = Math.abs(toCam.y) > 0.99F ? new Vector3f(1.0F, 0.0F, 0.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
        Vector3f rx = new Vector3f(up).cross(toCam).normalize().mul(radius);
        Vector3f ry = new Vector3f(toCam).cross(rx).normalize().mul(radius);
        float cr = r * alpha;
        float cg = g * alpha;
        float cb = b * alpha;
        // Round bloom: a fan of quads bright at the centre and black at the rim -> a soft radial
        // falloff, instead of a flat square that read as a sprite stuck on the lamp.
        int seg = 14;
        Vector3f prev = new Vector3f(pos).add(rx);
        for (int i = 1; i <= seg; i++) {
            double a = i * (Math.PI * 2.0) / seg;
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            Vector3f cur = new Vector3f(pos).add(
                    rx.x * cos + ry.x * sin, rx.y * cos + ry.y * sin, rx.z * cos + ry.z * sin);
            buffer.addVertex(matrix, pos.x, pos.y, pos.z).setColor(cr, cg, cb, 1.0F);
            buffer.addVertex(matrix, prev.x, prev.y, prev.z).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            buffer.addVertex(matrix, cur.x, cur.y, cur.z).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            buffer.addVertex(matrix, pos.x, pos.y, pos.z).setColor(cr, cg, cb, 1.0F);
            prev = cur;
        }
    }

    /**
     * The animated main channel. Draws segments only up to {@code reach} (the leader growing downward),
     * each at {@code baseAlpha} plus a Gaussian bump where the return-stroke pulse currently sits, so a
     * bright band can sweep along the bolt.
     *
     * @param reach   0..1 fraction of the path that has formed
     * @param pulseC  0..1 position of the bright band (0 = orb end, 1 = strike end)
     */
    private static void drawChannel(VertexConsumer buffer, Matrix4f matrix, Vector3f[] pts, Vector3f[] sides,
                                    float halfWidth, float r, float g, float b,
                                    float reach, float baseAlpha, float pulseC, float pulseW, float pulseAlpha) {
        drawChannel(buffer, matrix, pts, sides, halfWidth, r, g, b, reach, baseAlpha, pulseC, pulseW, pulseAlpha, 0.0F);
    }

    /** @param tipFade 0 = uniform; &gt;0 fades alpha toward the far end (branches: brighter near the shaft). */
    private static void drawChannel(VertexConsumer buffer, Matrix4f matrix, Vector3f[] pts, Vector3f[] sides,
                                    float halfWidth, float r, float g, float b,
                                    float reach, float baseAlpha, float pulseC, float pulseW, float pulseAlpha,
                                    float tipFade) {
        int last = pts.length - 1;
        if (last < 1) {
            return;
        }
        for (int i = 1; i <= last; i++) {
            float frac = (float) i / last;
            if (frac > reach) {
                break; // leader hasn't grown this far yet
            }
            float a = baseAlpha;
            if (pulseAlpha > 0.0F) {
                float d = frac - pulseC;
                a += pulseAlpha * (float) Math.exp(-(d * d) / (2.0F * pulseW * pulseW));
            }
            float hw = halfWidth;
            if (tipFade > 0.0F) {
                a *= 1.0F - tipFade * frac;   // brighter at the origin (near the shaft), fading to the tip
                hw *= 1.0F - tipFade * 0.55F * frac;   // and TAPER: branches narrow toward their tips too,
                                                        // instead of a flat uniform-width ribbon
            }
            if (a <= 0.004F) {
                continue;
            }
            float cr = r * a;
            float cg = g * a;
            float cb = b * a;
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], -hw, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], -hw, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], hw, cr, cg, cb);
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], hw, cr, cg, cb);
        }
    }

    /** How much a branch is allowed to shorten to stop right at a surface instead of clipping through it. */
    private static final float REACH_MARGIN = 0.25F;

    /** Reference lengths used to scale growth speed by target distance -- see {@link #branchGrowthWindow}. */
    private static final float BRANCH_LEN_REFERENCE = 2.0F;
    private static final float SUB_LEN_REFERENCE = 1.0F;
    private static final float BRANCH_GROWTH_MIN = 0.12F;
    private static final float BRANCH_GROWTH_MAX = 0.9F;

    /**
     * Forks off the main channel, like the reference bolt: several primary branches from the upper
     * three-quarters, each spawning its own smaller sub-branches, all angling down and out and fading.
     *
     * <p>Branches are world-aware: each primary samples a couple of nearby directions and keeps whichever
     * has the most open room ahead of it (real lightning finds the open air, not the nearest wall), then
     * its length is clipped to stop right where it actually reaches a block -- touching real geometry
     * instead of passing through it.</p>
     */
    private static void drawBranches(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                     Vector3f[] path, float bright, float reach, Level level, Vector3f worldOrigin,
                                     float closeFrac) {
        int last = path.length - 1;
        int count = 4 + random.nextInt(4); // 4-7 primaries, fixed by the seed
        for (int i = 0; i < count; i++) {
            // Origin fraction comes from the RNG INDEPENDENTLY of reach, so a branch keeps the same
            // spot every frame; reach only decides how far it has grown. The old code fed reach into
            // nextInt, so branches jumped to new positions each tick instead of growing outward.
            float originFrac = 0.12F + random.nextFloat() * 0.78F;
            int oi = Mth.clamp(Math.round(originFrac * last), 0, last);
            // Fork OUTWARD from the channel, not toward world-down. Build the direction from the
            // channel's local tangent at the origin (so the branch keeps heading the way the bolt is
            // going) plus a big sideways kick around that tangent -- that's what makes forks splay out
            // AND down on a vertical bolt instead of hugging straight down along it. The old code
            // pinned branches to world -Y, which only read as "out and down" when the bolt was already
            // horizontal. A mild world-down nudge at the end keeps them drooping. RNG use stays at
            // three floats so the rest of the deterministic shape is unchanged.
            Vector3f tan = new Vector3f(path[Math.min(oi + 1, last)]).sub(path[Math.max(oi - 1, 0)]);
            if (tan.lengthSquared() < 1.0E-6F) {
                tan.set(0.0F, -1.0F, 0.0F);
            }
            tan.normalize();
            Vector3f ref = Math.abs(tan.y) > 0.9F ? new Vector3f(1.0F, 0.0F, 0.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
            Vector3f perpA = new Vector3f(tan).cross(ref).normalize();
            Vector3f perpB = new Vector3f(tan).cross(perpA).normalize();
            float ang = random.nextFloat() * (float) (Math.PI * 2.0);
            Vector3f out = new Vector3f(perpA).mul((float) Math.cos(ang)).add(perpB.mul((float) Math.sin(ang)));
            float forward = 0.45F + random.nextFloat() * 0.4F; // how much it keeps travelling down-channel
            float spread = 0.9F + random.nextFloat() * 0.7F;   // how hard it kicks sideways/outward
            Vector3f dir = new Vector3f(tan).mul(forward).add(out.mul(spread));
            dir.add(0.0F, -0.3F, 0.0F).normalize();
            // Branches near the orb (top of the channel, low originFrac) run longer and are allowed to
            // recurse into their own small sub-branches; ones near the strike stay short and clean.
            // topFactor is 1 at the orb, 0 at the strike.
            float topFactor = 1.0F - originFrac;
            // Capped well below the last pass's range -- a long branch with only the fixed JAG amount of
            // wander reads as one near-straight diagonal line instead of a fork (exactly the "looks like a
            // single flat line" issue). See drawFork for the length-scaled jag that also fixes this.
            float len = (1.6F + random.nextFloat() * 2.4F) * (0.65F + topFactor * 1.15F);
            int branchDepth = topFactor > 0.6F ? 3 : (topFactor > 0.35F ? 2 : (topFactor > 0.15F ? 1 : 0));

            // Reach toward real space: try a couple of small jitters around the base direction and keep
            // whichever sees the most open room, then clip the length to where it actually meets a block.
            // Deterministic (same RNG stream every frame), so the shape still holds still frame to frame.
            Vector3f bestDir = dir;
            float bestClear = clearDistance(level, worldOrigin, path[oi], dir, len);
            for (int c = 0; c < 2; c++) {
                float jitterAng = ang + (random.nextFloat() - 0.5F) * 1.6F;
                Vector3f jOut = new Vector3f(perpA).mul((float) Math.cos(jitterAng)).add(perpB.mul((float) Math.sin(jitterAng)));
                Vector3f jDir = new Vector3f(tan).mul(forward).add(jOut.mul(spread));
                jDir.add(0.0F, -0.3F, 0.0F).normalize();
                float clear = clearDistance(level, worldOrigin, path[oi], jDir, len);
                if (clear > bestClear) {
                    bestClear = clear;
                    bestDir = jDir;
                }
            }
            dir = bestDir;
            // NEVER floor this to a minimum -- that was forcing a branch with almost no clearance to draw
            // past what the raycast actually found, visibly touching/entering the block. If there's no
            // room, len ends up under drawFork's own 0.35 visibility floor and the branch just doesn't
            // draw there, rather than being forced into the wall.
            len = Math.min(bestClear, len);

            // Growth speed depends on THIS branch's own target distance: a close target grows slowly (a
            // wide window relative to reach, so it lingers into being), a far target grows fast (a narrow
            // window, so it lashes out quickly once the leader passes its origin).
            float growth = Mth.clamp((reach - originFrac) / branchGrowthWindow(len), 0.0F, 1.0F);
            // A little width variance per branch (some noticeably thicker, some hair-thin) instead of
            // every primary being an identical ribbon -- reads as more organic, less like a repeated asset.
            float width = CORE_WIDTH * (0.65F + random.nextFloat() * 0.6F);
            // Same bottom-up close as the trunk: a branch near the strike end (high originFrac) fades out
            // as soon as the close starts sweeping up; one near the orb (low originFrac) keeps going a
            // while longer -- so branches stay visible but consistently fade WITH the trunk's own closing,
            // instead of sitting at a fixed brightness on a section of trunk that has already gone dark.
            float branchCloseMul = Mth.clamp(1.0F - closeFrac * originFrac * 1.4F, 0.0F, 1.0F);
            drawFork(buffer, matrix, camera, random, path[oi], dir, len, width, bright * branchCloseMul,
                    branchDepth, growth, level, worldOrigin);
        }
    }

    /**
     * One branch, grown to {@code growth} (0..1). The full jagged shape is built deterministically so
     * it never changes frame to frame; only how much of it is drawn does. Recurses into sub-branches
     * unconditionally (so the RNG stream stays identical every frame), each grown a little behind its
     * parent — that fork-off-a-fork structure is what makes lightning read as lightning. Each sub-branch
     * is also clipped to the nearest block along its own direction, same as the primaries.
     */
    private static void drawFork(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                 Vector3f from, Vector3f dir, float length, float width, float bright, int depth,
                                 float growth, Level level, Vector3f worldOrigin) {
        int steps = Math.max(2, Mth.ceil(length / SEGMENT_LENGTH));
        Vector3f end = new Vector3f(from).add(new Vector3f(dir).mul(length));
        Vector3f[] pts = new Vector3f[steps + 1];
        pts[0] = new Vector3f(from);
        // The fixed trunk JAG amount is too small relative to a long branch -- it reads as one smooth
        // near-straight diagonal instead of a jagged fork. Scale wander with the branch's OWN length
        // (bounded) so a long branch stays proportionally as jagged as a short one.
        float jag = Mth.clamp(length * 0.09F, JAG * 0.9F, JAG * 2.8F);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            Vector3f pnt = new Vector3f(from).lerp(end, t);
            pnt.add((random.nextFloat() - 0.5F) * jag * 1.5F * t,
                    (random.nextFloat() - 0.5F) * jag * t,
                    (random.nextFloat() - 0.5F) * jag * 1.5F * t);
            pts[i] = pnt;
        }
        if (growth > 0.0F && bright > 0.02F && length >= 0.35F) {
            Vector3f[] sides = computeSides(pts, camera);
            drawChannel(buffer, matrix, pts, sides, width * 1.8F, 0.5F, 0.62F, 1.0F, growth, bright * 0.3F, 0.0F, 0.1F, 0.0F, 0.55F);
            drawChannel(buffer, matrix, pts, sides, width, 0.9F, 0.94F, 1.0F, growth, bright, 0.0F, 0.1F, 0.0F, 0.55F);
        }
        if (depth > 0) {
            int subs = random.nextInt(3); // 0-2 per level: a chance of none, so not every branch forks
            for (int k = 0; k < subs; k++) {
                Vector3f origin = pts[Math.min(1 + random.nextInt(steps), steps)];
                Vector3f sub = new Vector3f(dir).add(
                        (random.nextFloat() - 0.5F) * 0.8F,
                        -0.15F - 0.2F * random.nextFloat(),
                        (random.nextFloat() - 0.5F) * 0.8F).normalize();
                float rolledSubLen = length * (0.35F + 0.3F * random.nextFloat());
                // Same rule as the primaries: clip to whatever is actually there, no forced minimum, so a
                // sub-branch with no room simply doesn't draw instead of poking into the block.
                float subLen = Math.min(clearDistance(level, worldOrigin, origin, sub, rolledSubLen), rolledSubLen);
                // And the same distance-based growth speed, scaled down since sub-branches are inherently
                // shorter: a short sub-twig lingers, a longer one lashes out quickly.
                float subWindow = Mth.clamp(0.65F * (SUB_LEN_REFERENCE / Math.max(subLen, 0.3F)),
                        BRANCH_GROWTH_MIN, BRANCH_GROWTH_MAX);
                float subGrowth = Mth.clamp((growth - (1.0F - subWindow)) / subWindow, 0.0F, 1.0F);
                drawFork(buffer, matrix, camera, random, origin, sub,
                        subLen, width * 0.62F, bright * 0.6F, depth - 1, subGrowth, level, worldOrigin);
            }
        }
    }

    /**
     * How far (in blocks) a ray from LOCAL point {@code from} along {@code dir} can travel before hitting
     * a solid block, capped at {@code max}. World-space collision check via {@code worldOrigin} (the
     * bolt's world position) + {@code from}. Fixed-step sampling -- cheap and good enough for a
     * decorative, once-per-branch query, not exact voxel traversal.
     */
    private static float clearDistance(Level level, Vector3f worldOrigin, Vector3f from, Vector3f dir, float max) {
        float step = 0.4F;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (float d = step; d <= max; d += step) {
            float x = worldOrigin.x + from.x + dir.x * d;
            float y = worldOrigin.y + from.y + dir.y * d;
            float z = worldOrigin.z + from.z + dir.z * d;
            probe.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            BlockState state = level.getBlockState(probe);
            if (!state.getCollisionShape(level, probe).isEmpty()) {
                return Math.max(0.0F, d - step - REACH_MARGIN);
            }
        }
        return max;
    }

    /**
     * How much of {@code reach}'s range a primary branch takes to fully grow, based on how far its own
     * target actually is. A close target (short {@code len}, e.g. it hit a nearby wall) gets a WIDE
     * window so it grows slowly and lingers into being; a far target (long {@code len}, open room ahead)
     * gets a NARROW window so it lashes out quickly once the leader passes its origin.
     */
    private static float branchGrowthWindow(float len) {
        return Mth.clamp(BRANCH_GROWTH * (BRANCH_LEN_REFERENCE / Math.max(len, 0.4F)),
                BRANCH_GROWTH_MIN, BRANCH_GROWTH_MAX);
    }

    /** A random, mostly-horizontal displacement of up to ~{@code reach} for a bow control point. */
    private static Vector3f randomBow(RandomSource random, float reach) {
        float angle = random.nextFloat() * ((float) Math.PI * 2.0F);
        float mag = reach * (0.4F + random.nextFloat() * 0.9F);
        return new Vector3f(Mth.cos(angle) * mag, (random.nextFloat() - 0.5F) * mag * 0.5F, Mth.sin(angle) * mag);
    }

    /** Triangular weight peaking at {@code peak}, 0 at both ends — keeps anchors pinned. */
    private static float envelope(float t, float peak) {
        return t < peak ? t / peak : (1.0F - t) / (1.0F - peak);
    }

}
