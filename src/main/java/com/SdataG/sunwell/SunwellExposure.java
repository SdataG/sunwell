package com.SdataG.sunwell;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Scales virtual sunwell by outdoor conditions: surface sky, day/night, weather,
 * and the same per-lantern flux curve used by client glow/flame animations.
 */
public final class SunwellExposure {

    private SunwellExposure() {
    }

    /** Per-block phase offset so lanterns never pulse in sync. */
    public static float phase(BlockPos pos) {
        int hash = pos.getX() * 374761393
                ^ pos.getY() * 668265263
                ^ pos.getZ() * 2147483647;
        return (hash & 1023) * 0.19F;
    }

    public static float fastPhase(BlockPos pos) {
        return phase(pos) * 1.37F;
    }

    public static float animationTime(Level level, BlockPos pos, float partialTick) {
        return level.getGameTime() + partialTick + phase(pos);
    }

    /**
     * Soft flicker multiplier (roughly 0.78–1.0) shared by virtual light and client VFX.
     */
    public static float fluxMultiplier(Level level, BlockPos pos) {
        return fluxMultiplier(level, pos, 0.0F);
    }

    public static float fluxMultiplier(Level level, BlockPos pos, float partialTick) {
        if (!SunwellConfig.lanternFlux) {
            return 1.0F;
        }
        float t = animationTime(level, pos, partialTick);
        float breathe = Mth.sin(t * 0.17F + phase(pos) * 1.91F + fastPhase(pos) * 0.43F);
        float humWave = Mth.sin(t * 0.05F + phase(pos) * 0.25F);
        float value = 0.86F + breathe * 0.08F + humWave * 0.03F;
        return Mth.clamp(value, 0.78F, 1.0F);
    }

    /** Outdoor exposure from day/night, weather, and sampled surface sky (0–1). */
    public static float environmentMultiplier(Level level, int outdoorSkyLight) {
        return dayNightMultiplier(level)
                * weatherMultiplier(level)
                * surfaceMultiplier(level, outdoorSkyLight);
    }

    public static float dayNightMultiplier(Level level) {
        if (!SunwellConfig.followDayNightCycle) {
            return 1.0F;
        }
        return (16.0F - SunwellVirtualEnvironment.skyDarken(level)) / 16.0F;
    }

    public static float weatherMultiplier(Level level) {
        if (!SunwellConfig.respondToWeather) {
            return 1.0F;
        }
        float rain = SunwellVirtualEnvironment.rainLevel(level, 1.0F);
        float thunder = SunwellVirtualEnvironment.thunderLevel(level, 1.0F);
        return Mth.clamp(1.0F - rain * 0.30F - thunder * 0.20F, 0.30F, 1.0F);
    }

    public static float surfaceMultiplier(int outdoorSkyLight) {
        return Mth.clamp(outdoorSkyLight / 15.0F, 0.0F, 1.0F);
    }

    /**
     * Surface sky sample for the column above a sunwell. Dimensions without real skylight
     * (End, backrooms limbo) always sample 0 outdoors — sunwell is the only sky there.
     */
    public static float surfaceMultiplier(Level level, int outdoorSkyLight) {
        if (!SunwellConfig.respondToSurfaceLight) {
            return 1.0F;
        }
        if (outdoorSkyLight <= 0 && !level.dimensionType().hasSkyLight()) {
            return 1.0F;
        }
        return surfaceMultiplier(outdoorSkyLight);
    }

    public static int scaleVirtualLight(Level level, BlockPos pos, int base, int outdoorSkyLight) {
        if (base <= 0) {
            return 0;
        }
        float scaled = base
                * dayNightMultiplier(level)
                * weatherMultiplier(level)
                * surfaceMultiplier(level, outdoorSkyLight)
                * fluxMultiplier(level, pos);
        return Mth.clamp(Math.round(scaled), 0, SunwellConfig.skyLevel);
    }

    /**
     * Raw vanilla sky light at the open-air column above {@code x}/{@code z}.
     * Sunwell boost is suppressed while sampling so we never read our own injection.
     */
    public static int sampleOutdoorSkyLight(Level level, int x, int z) {
        BlockPos probe = new BlockPos(x, level.getMinBuildHeight(), z);
        if (!level.hasChunkAt(probe)) {
            return 0;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        surfaceY = Mth.clamp(surfaceY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        BlockPos surface = new BlockPos(x, surfaceY, z);
        SunwellManager.setBoostSuppressed(true);
        try {
            return level.getChunkSource().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(surface);
        } finally {
            SunwellManager.setBoostSuppressed(false);
        }
    }

    public static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }
}
