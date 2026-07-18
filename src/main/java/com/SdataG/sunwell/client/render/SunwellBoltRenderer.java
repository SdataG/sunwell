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
    private static final float HALO_WIDTH = 0.11F;
    private static final float BLOOM_WIDTH = 0.34F;


    /** Whole VFX length in ticks (~half a second at 20 tps). */
    private static final float LIFE_TICKS = 10.0F;

    /** Beat boundaries over the 10-tick life: spread (leader) ticks 0-5, strike (return) 5-7, fade 7-10. */
    private static final float LEADER_END = 0.5F;
    private static final float RETURN_END = 0.6F;

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
        float pulseC = 0.0F;
        float pulseW = 0.14F;
        float pulseA = 0.0F;
        if (t < LEADER_END) {
            reach = Mth.clamp(t / LEADER_END, 0.0F, 1.0F);
            baseA = 0.30F * flick;
            branchBright = baseA * 0.3F;      // spread: branches at ~30% opacity while they grow out
            lampGlow = 0.12F + reach * 0.55F; // and the lamp charges brighter as the leader reaches down
        } else if (t < RETURN_END) {
            float u = (t - LEADER_END) / (RETURN_END - LEADER_END);
            reach = 1.0F;
            baseA = 0.5F;
            pulseC = 1.0F - u; // frac 1 = strike, frac 0 = orb: the band runs hit -> lamp
            pulseA = 1.3F;
            branchBright = 0.0F;              // strike: the return stroke washes the branches out
            lampGlow = 1.0F;                  // lamp flares at the strike
        } else {
            float f = 1.0F - (t - RETURN_END) / (1.0F - RETURN_END);
            reach = 1.0F;
            baseA = 0.6F * f * flick;
            branchBright = baseA * 0.6F;      // fade: branches return at 60% afterglow opacity
            lampGlow = baseA;
        }

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

        // Bloom, halo, then the thin white core -- each grown to `reach` and lit by the moving pulse.
        drawChannel(buffer, matrix, path, sides, BLOOM_WIDTH, 0.45F, 0.58F, 1.0F, reach, 0.10F * baseA, pulseC, pulseW, 0.45F * pulseA);
        drawChannel(buffer, matrix, path, sides, HALO_WIDTH, 0.55F, 0.68F, 1.0F, reach, 0.28F * baseA, pulseC, pulseW, 0.7F * pulseA);
        drawChannel(buffer, matrix, path, sides, CORE_WIDTH, 0.97F, 0.98F, 1.0F, reach, baseA, pulseC, pulseW, pulseA);

        // Branches during the spread and the fade, but not the strike flash (see phase block above).
        if (branchBright > 0.05F) {
            drawBranches(buffer, matrix, camera, random, path, branchBright, reach);
        }

        // The lamp itself brightens as the leader charges down, flares at the strike, then fades -- a
        // soft bloom at the orb end of the channel (path[0]).
        drawGlow(buffer, matrix, camera, path[0], 0.55F, lampGlow * 0.45F, 0.7F, 0.82F, 1.0F);
        drawGlow(buffer, matrix, camera, path[0], 0.26F, lampGlow, 0.96F, 0.98F, 1.0F);
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
            if (a <= 0.004F) {
                continue;
            }
            float cr = r * a;
            float cg = g * a;
            float cb = b * a;
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], -halfWidth, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], -halfWidth, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], halfWidth, cr, cg, cb);
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], halfWidth, cr, cg, cb);
        }
    }

    /**
     * Forks off the main channel, like the reference bolt: several primary branches from the upper
     * three-quarters, each spawning its own smaller sub-branches, all angling down and out and fading.
     */
    private static void drawBranches(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                     Vector3f[] path, float bright, float reach) {
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
            float len = (1.0F + random.nextFloat() * 1.8F) * (0.7F + topFactor * 1.5F);
            int branchDepth = topFactor > 0.6F ? 3 : (topFactor > 0.35F ? 2 : (topFactor > 0.15F ? 1 : 0));
            // A branch forms as the leader passes its origin, then grows out over BRANCH_GROWTH.
            float growth = Mth.clamp((reach - originFrac) / BRANCH_GROWTH, 0.0F, 1.0F);
            drawFork(buffer, matrix, camera, random, path[oi], dir, len, CORE_WIDTH * 0.7F, bright * 0.85F, branchDepth, growth);
        }
    }

    /**
     * One branch, grown to {@code growth} (0..1). The full jagged shape is built deterministically so
     * it never changes frame to frame; only how much of it is drawn does. Recurses into sub-branches
     * unconditionally (so the RNG stream stays identical every frame), each grown a little behind its
     * parent — that fork-off-a-fork structure is what makes lightning read as lightning.
     */
    private static void drawFork(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                 Vector3f from, Vector3f dir, float length, float width, float bright, int depth,
                                 float growth) {
        int steps = Math.max(2, Mth.ceil(length / SEGMENT_LENGTH));
        Vector3f end = new Vector3f(from).add(new Vector3f(dir).mul(length));
        Vector3f[] pts = new Vector3f[steps + 1];
        pts[0] = new Vector3f(from);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            Vector3f pnt = new Vector3f(from).lerp(end, t);
            pnt.add((random.nextFloat() - 0.5F) * JAG * 1.5F * t,
                    (random.nextFloat() - 0.5F) * JAG * t,
                    (random.nextFloat() - 0.5F) * JAG * 1.5F * t);
            pts[i] = pnt;
        }
        if (growth > 0.0F && bright > 0.02F && length >= 0.35F) {
            Vector3f[] sides = computeSides(pts, camera);
            drawChannel(buffer, matrix, pts, sides, width * 1.8F, 0.5F, 0.62F, 1.0F, growth, bright * 0.3F, 0.0F, 0.1F, 0.0F);
            drawChannel(buffer, matrix, pts, sides, width, 0.9F, 0.94F, 1.0F, growth, bright, 0.0F, 0.1F, 0.0F);
        }
        if (depth > 0) {
            int subs = random.nextInt(3); // 0-2 per level: a chance of none, so not every branch forks
            for (int k = 0; k < subs; k++) {
                Vector3f origin = pts[Math.min(1 + random.nextInt(steps), steps)];
                Vector3f sub = new Vector3f(dir).add(
                        (random.nextFloat() - 0.5F) * 0.8F,
                        -0.15F - 0.2F * random.nextFloat(),
                        (random.nextFloat() - 0.5F) * 0.8F).normalize();
                float subGrowth = Mth.clamp((growth - 0.35F) / 0.65F, 0.0F, 1.0F);
                drawFork(buffer, matrix, camera, random, origin, sub,
                        length * (0.35F + 0.3F * random.nextFloat()), width * 0.62F, bright * 0.6F, depth - 1, subGrowth);
            }
        }
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
