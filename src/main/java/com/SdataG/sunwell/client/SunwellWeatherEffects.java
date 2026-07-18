package com.SdataG.sunwell.client;

import com.SdataG.sunwell.SunwellConfig;
import com.SdataG.sunwell.SunwellManager;
import com.SdataG.sunwell.SunwellProfile;
import com.SdataG.sunwell.SunwellVirtualEnvironment;
import com.SdataG.sunwell.registry.ModParticles;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client rain/snow under full-profile lanterns ({@link SunwellProfile#hasWeatherPresentation}).
 * Precipitation spawns in a widening cone and stops at the floor or first solid block.
 */
public final class SunwellWeatherEffects {

    private static final int PLAYER_RADIUS = 32;
    private static final int NEAR_RADIUS = 16;
    /** Precipitation intensity: a light drizzle while it merely rains, a downpour while it storms. Each
     *  "burst" is that many droplets seeded per shaft per tick, spread across the whole lit floor disc,
     *  and the per-tick ceiling caps the total across every nearby shaft. */
    private static final int DRIZZLE_BURST = 3;
    private static final int STORM_BURST = 9;
    private static final int DRIZZLE_MAX_PARTICLES = 64;
    private static final int STORM_MAX_PARTICLES = 200;
    /** Neighboring full-profile lanterns share one precipitation shaft per tick. */
    private static final int SHAFT_CLUSTER_DISTANCE_SQ = 4;

    private SunwellWeatherEffects() {
    }

    /**
     * Per-tick tally of which gate each nearby source died at. Every {@code return} in the loop below
     * is a silent "no particles" — with eight of them, a dry cone is indistinguishable from a working
     * one that simply found nowhere to rain. Enable {@code debugWeatherParticles} to get the count.
     */
    private static final class Gates {
        int sources, cluster, far, profile, precipitation, traceFailed, spawned;
    }

    private static long debugTick = Long.MIN_VALUE;

    public static void tick(Level level, SunwellManager manager) {
        if (!SunwellConfig.weatherShaftParticles || !level.isClientSide) {
            return;
        }
        boolean raining = SunwellVirtualEnvironment.isRaining(level);
        boolean thundering = SunwellVirtualEnvironment.isThundering(level);
        if (!raining && !thundering) {
            debugGate(level, "not raining (isRaining=false, isThundering=false)");
            return;
        }
        // Thunder = storm = a lot more rain than a plain drizzle.
        int burst = thundering ? STORM_BURST : DRIZZLE_BURST;
        int maxParticles = thundering ? STORM_MAX_PARTICLES : DRIZZLE_MAX_PARTICLES;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(level instanceof ClientLevel clientLevel)) {
            return;
        }

        BlockPos playerPos = minecraft.player.blockPosition();
        RandomSource random = level.random;
        int[] spawned = {0};
        Gates gates = new Gates();
        LongOpenHashSet shaftRepresentatives = new LongOpenHashSet();

        manager.forEachSourceNear(playerPos, PLAYER_RADIUS, packed -> {
            gates.sources++;
            if (spawned[0] >= maxParticles) {
                return;
            }
            BlockPos source = unpack(packed);
            if (!claimShaftRepresentative(source, shaftRepresentatives)) {
                gates.cluster++;
                return;
            }
            int dx = source.getX() - playerPos.getX();
            int dz = source.getZ() - playerPos.getZ();
            int horizDistSq = dx * dx + dz * dz;
            if (horizDistSq > PLAYER_RADIUS * PLAYER_RADIUS) {
                gates.far++;
                return;
            }
            if (horizDistSq > NEAR_RADIUS * NEAR_RADIUS) {
                float farT = (float) (Math.sqrt(horizDistSq) - NEAR_RADIUS) / (PLAYER_RADIUS - NEAR_RADIUS);
                if (random.nextFloat() < farT * 0.85F) {
                    gates.far++;
                    return;
                }
            }
            if (!SunwellProfile.hasWeatherPresentation(manager.profileAtSource(source))) {
                gates.profile++;
                return;
            }

            Biome.Precipitation precipitation = clientLevel.getBiome(source).value().getPrecipitationAt(source);
            if (precipitation != Biome.Precipitation.RAIN && precipitation != Biome.Precipitation.SNOW) {
                if (!SunwellVirtualEnvironment.usesVirtualWeather(level)) {
                    gates.precipitation++;
                    return;
                }
                precipitation = Biome.Precipitation.RAIN;
            }

            // Seed a burst of droplets across the whole disc, so the entire lit area gets rain at an
            // intensity that matches the weather, not a single drop per shaft.
            int made = 0;
            for (int b = 0; b < burst && spawned[0] + made < maxParticles; b++) {
                made += spawnConePrecipitation(clientLevel, manager, source, precipitation, random);
            }
            if (made == 0) {
                gates.traceFailed++;
            }
            spawned[0] += made;
        });
        gates.spawned = spawned[0];
        debugGates(level, gates);
    }

    private static void debugGate(Level level, String why) {
        if (!SunwellConfig.debugWeatherParticles || !readyToLog(level)) {
            return;
        }
        com.SdataG.sunwell.Sunwell.LOGGER.info("[sunwell] rain particles: {}", why);
    }

    private static void debugGates(Level level, Gates g) {
        if (!SunwellConfig.debugWeatherParticles || !readyToLog(level)) {
            return;
        }
        com.SdataG.sunwell.Sunwell.LOGGER.info(
                "[sunwell] rain particles: sources={} -> spawned={} (rejected: cluster={} far={} "
                        + "profile/untagged={} biome-precip=NONE:{} no-lit-column={})",
                g.sources, g.spawned, g.cluster, g.far, g.profile, g.precipitation, g.traceFailed);
    }

    private static boolean readyToLog(Level level) {
        long tick = level.getGameTime();
        if (tick == debugTick || tick % 40L != 0L) {
            return false;
        }
        debugTick = tick;
        return true;
    }

    private static boolean claimShaftRepresentative(BlockPos source, LongOpenHashSet representatives) {
        long packed = source.asLong();
        for (long rep : representatives) {
            int dx = source.getX() - BlockPos.getX(rep);
            int dy = source.getY() - BlockPos.getY(rep);
            int dz = source.getZ() - BlockPos.getZ(rep);
            if (dx * dx + dy * dy + dz * dz <= SHAFT_CLUSTER_DISTANCE_SQ) {
                return false;
            }
        }
        representatives.add(packed);
        return true;
    }

    /**
     * Pick a random point in the precipitation cone below {@code source}, trace to floor/solid, spawn rain or snow.
     */
    private static int spawnConePrecipitation(
            ClientLevel level,
            SunwellManager manager,
            BlockPos source,
            Biome.Precipitation precipitation,
            RandomSource random
    ) {
        int maxDepth = SunwellConfig.maxDepth;
        int maxRadius = SunwellConfig.maxRadius;
        if (maxDepth <= 0 || maxRadius <= 0) {
            return 0;
        }

        // Rain should cover the WHOLE cone the lamp lights, not bunch up under it. So sample a point on
        // the floor disc directly — radius = the cone's width at the floor — spread by sqrt(random) so
        // points are uniform over AREA (without the sqrt they crowd the centre). Then trace that column
        // and drop the rain in. The old sampler picked a random depth first, and since the cone is now
        // narrow up high and only opens out near the floor, almost every sample landed near the middle.
        int dropHeight = SunwellManager.coneDropHeight(level, source, maxDepth);
        int floorRadius = Math.max(1, SunwellManager.litRadiusAtDepth(dropHeight, dropHeight));

        double x = 0.0D;
        double z = 0.0D;
        ColumnHit hit = null;
        for (int attempt = 0; attempt < 6 && hit == null; attempt++) {
            double dist = floorRadius * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * Math.PI * 2.0D;
            x = source.getX() + 0.5D + Math.cos(angle) * dist;
            z = source.getZ() + 0.5D + Math.sin(angle) * dist;
            hit = traceColumn(level, manager, source, Mth.floor(x), Mth.floor(z), maxDepth);
        }
        if (hit == null) {
            return 0;
        }

        int depth = Math.max(1, source.getY() - hit.topY());
        double spawnY = pickSpawnY(source, hit, depth, random);
        if (spawnY <= hit.floorY + 0.05D) {
            spawnY = hit.floorY + 0.12D + random.nextDouble() * 0.35D;
        }
        if (spawnY >= hit.topY() + 1.0D) {
            spawnY = hit.topY() + 0.85D;
        }

        if (precipitation == Biome.Precipitation.SNOW) {
            // Floatier, drifting snow: gentler fall and more sideways sway than a raindrop, so it reads
            // as snow settling rather than streaking straight down.
            level.addParticle(
                    ParticleTypes.SNOWFLAKE,
                    x, spawnY, z,
                    (random.nextDouble() - 0.5D) * 0.05D,
                    -0.022D - random.nextDouble() * 0.02D,
                    (random.nextDouble() - 0.5D) * 0.05D
            );
        } else {
            // Sunwell's own streaked rain rather than vanilla ParticleTypes.RAIN, so the cone reads
            // as proper falling rain and matches regardless of which weather mod is installed.
            // Zero velocity = "use your own terminal-velocity fall"; a launch vector is reserved for
            // the ground lantern's sprinkler arc.
            level.addParticle(
                    ModParticles.SKY_RAIN.get(),
                    x, spawnY, z,
                    0.0D,
                    0.0D,
                    0.0D
            );
            if (hit.solid && random.nextInt(3) == 0) {
                level.addParticle(
                        ParticleTypes.SPLASH,
                        x, hit.floorY + 0.1D, z,
                        0.0D, 0.0D, 0.0D
                );
            }
        }
        return 1;
    }

    /**
     * Ground lantern: fling precipitation outward and up so it arcs back down across the lit radius,
     * like a sprinkler. Launch speed scales with the configured radius so a bigger sunwell throws
     * further; the particle's own gravity brings it down.
     */
    private static double pickSpawnY(BlockPos source, ColumnHit hit, int targetDepth, RandomSource random) {
        double top = hit.topY() + 0.8D;
        double bottom = hit.solid ? hit.floorY + 1.05D : hit.topY() - targetDepth;
        if (bottom >= top) {
            return top;
        }
        return Mth.lerp(random.nextDouble(), bottom, top);
    }

    /**
     * Walk a vertical column inside the sunwell shaft; report the first blocking surface.
     *
     * <p>Starts from the <em>top of the lit column</em> rather than always one block under the lamp.
     * A ceiling lantern is capped by its own ceiling so this is still {@code source - 1}, but a lamp
     * standing on the ground lights a region that reaches up and outward — so its rain falls from
     * above the lamp, over the radius around it, instead of not spawning at all.</p>
     */
    private static ColumnHit traceColumn(
            Level level,
            SunwellManager manager,
            BlockPos source,
            int blockX,
            int blockZ,
            int maxDepth
    ) {
        int minY = Math.max(level.getMinBuildHeight(), source.getY() - maxDepth);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean sawAir = false;

        int startY = source.getY() - 1;
        int ceiling = Math.min(level.getMaxBuildHeight() - 1, source.getY() + SunwellConfig.maxRadius);
        for (int y = source.getY(); y <= ceiling; y++) {
            probe.set(blockX, y, blockZ);
            if (!isOpenShaftCell(manager, probe)) {
                break;
            }
            startY = y;
        }

        // Descend to the floor. This (x,z) may not be lit until part-way down -- the cone is narrow at
        // the lamp and only widens lower -- so DON'T bail the moment the top of the column isn't lit
        // (that bug is why rain only fell in the centre column and not across the whole lit ring). Fall
        // through any air until we enter the lit shaft, remember where it started (rain enters there),
        // then take the first solid block as the floor. A solid block hit BEFORE the shaft opens is a
        // wall/overhang above the lit region, so that column is skipped.
        int topLitY = Integer.MIN_VALUE;
        int lastAirY = Integer.MIN_VALUE;

        for (int y = startY; y >= minY; y--) {
            probe.set(blockX, y, blockZ);
            VoxelShape shape = level.getBlockState(probe).getCollisionShape(level, probe);
            if (!shape.isEmpty()) {
                if (topLitY != Integer.MIN_VALUE) {
                    return new ColumnHit(y, true, topLitY);   // floor inside the lit shaft
                }
                return null;   // wall/overhang above the lit region -- not a rain column here
            }
            if (isOpenShaftCell(manager, probe)) {
                if (topLitY == Integer.MIN_VALUE) {
                    topLitY = y;   // highest lit cell in this column: where rain enters the shaft
                }
                sawAir = true;
                lastAirY = y;
            }
        }

        if (!sawAir || topLitY == Integer.MIN_VALUE) {
            return null;
        }
        return new ColumnHit(lastAirY, false, topLitY);
    }

    private static boolean isOpenShaftCell(SunwellManager manager, BlockPos pos) {
        return manager.baseSkyAt(pos) > 0 && SunwellProfile.hasRainThrough(manager.profileAt(pos));
    }


    private static BlockPos unpack(long packed) {
        return new BlockPos(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
    }

    /** @param topY highest lit cell in this column — where rain enters the shaft. */
    private record ColumnHit(int floorY, boolean solid, int topY) {
    }
}
