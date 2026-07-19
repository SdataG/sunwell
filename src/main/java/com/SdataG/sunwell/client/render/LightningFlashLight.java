package com.SdataG.sunwell.client.render;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-world lighting impact of an active sunwell lightning bolt: nearby BLOCK light flares at the
 * strike point during the flash and fades with the bolt's afterglow, instead of the strike being purely
 * additive geometry with no actual light of its own.
 *
 * <p>Radius varies with the moment: on the exact return-stroke tick it's large -- the whole room actually
 * brightens for that tick, the way a real strike lights up everything around you -- then it contracts
 * back down to a smaller, more local falloff for the fading afterglow.</p>
 *
 * <p>Single writer (the render thread, once per bolt per frame via {@link SunwellBoltRenderer}); read
 * from any thread (chunk mesh workers) via {@code LightEngineMixin}, so a plain {@link
 * ConcurrentHashMap} is enough -- entries are always 0-2 and we never need a consistent snapshot, only
 * no corruption. Entries self-expire (a bolt that vanishes mid-render without a final update call would
 * otherwise leave a permanent stuck light).</p>
 */
public final class LightningFlashLight {

    private static final long EXPIRY_NANOS = 500_000_000L; // 0.5s -- generous past the bolt's ~0.5s life

    private record Flash(int x, int y, int z, float intensity, float radius, long expiresAtNanos) {
    }

    private static final ConcurrentHashMap<Integer, Flash> ACTIVE = new ConcurrentHashMap<>();

    private LightningFlashLight() {
    }

    /** Called once per rendered frame for each live sunwell bolt, keyed by its entity id. */
    public static void update(int boltId, BlockPos strike, float intensity, float radius) {
        if (intensity <= 0.01F) {
            ACTIVE.remove(boltId);
            return;
        }
        ACTIVE.put(boltId, new Flash(strike.getX(), strike.getY(), strike.getZ(), intensity, radius,
                System.nanoTime() + EXPIRY_NANOS));
    }

    public static void clear(int boltId) {
        ACTIVE.remove(boltId);
    }

    /** Extra block light this position should read from every active flash nearby (0 if none). */
    public static int extraLightAt(int x, int y, int z) {
        if (ACTIVE.isEmpty()) {
            return 0;
        }
        long now = System.nanoTime();
        int best = 0;
        for (Flash f : ACTIVE.values()) {
            if (now > f.expiresAtNanos()) {
                continue; // stale; self-heals without needing an explicit sweep
            }
            float radius = f.radius();
            int dx = x - f.x();
            int dy = y - f.y();
            int dz = z - f.z();
            int distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radius * radius) {
                continue;
            }
            float dist = (float) Math.sqrt(distSq);
            float falloff = 1.0F - dist / radius;
            int level = Math.round(15.0F * f.intensity() * falloff);
            if (level > best) {
                best = level;
            }
        }
        return best;
    }
}
