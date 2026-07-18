package com.SdataG.sunwell;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-source behavior flags resolved from block tags.
 * Blocks in {@link Sunwell#SUNWELL_SOURCE} with no extra tags are
 * static grow lights (always-on virtual sky, no burn, no weather flux).
 */
public final class SunwellProfile {

    /** Weather, surface, day/night, and lantern flux affect virtual sky level. */
    public static final byte DYNAMIC_EXPOSURE = 1;
    /** Undead can sun-burn inside the lit region during the day. */
    public static final byte UNDEAD_BURNING = 2;
    /** Rain and snow fall through the lit region during weather. */
    public static final byte RAIN_THROUGH = 4;

    private SunwellProfile() {
    }

    public static byte fromState(BlockState state) {
        byte profile = 0;
        if (state.is(Sunwell.DYNAMIC_EXPOSURE)) {
            profile |= DYNAMIC_EXPOSURE;
        }
        if (state.is(Sunwell.UNDEAD_BURNING)) {
            profile |= UNDEAD_BURNING;
        }
        if (state.is(Sunwell.RAIN_THROUGH)) {
            profile |= RAIN_THROUGH;
        }
        return profile;
    }

    public static boolean hasDynamicExposure(byte profile) {
        return (profile & DYNAMIC_EXPOSURE) != 0;
    }

    public static boolean hasUndeadBurning(byte profile) {
        return (profile & UNDEAD_BURNING) != 0;
    }

    public static boolean hasRainThrough(byte profile) {
        return (profile & RAIN_THROUGH) != 0;
    }

    /**
     * Full outdoor lantern profile: dynamic exposure + rain-through.
     * The Sunwell Lantern ships with both; pack blocks need explicit tags.
     */
    public static boolean hasWeatherPresentation(byte profile) {
        return hasDynamicExposure(profile) && hasRainThrough(profile);
    }

    /** Lower rank wins when two sources contribute the same base light level. */
    public static int sideEffectRank(byte profile) {
        return Integer.bitCount(profile & 0xFF);
    }
}
