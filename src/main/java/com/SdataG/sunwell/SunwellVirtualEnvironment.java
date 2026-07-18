package com.SdataG.sunwell;

import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

/**
 * Independent day/night and weather for sunwell when the dimension freezes vanilla
 * time ({@code fixed_time}) or disables daylight/weather gamerules — common in
 * sealed backrooms and Liminal overworld presets.
 */
public final class SunwellVirtualEnvironment {

    private static final long TICKS_PER_DAY = 24000L;
    private static final long WEATHER_CYCLE = 72000L;
    private static final long RAIN_DURATION = 12000L;
    private static final long RAIN_START = 18000L;
    private static final long WEATHER_FADE = 800L;

    private SunwellVirtualEnvironment() {
    }

    public static boolean usesVirtualDayNight(Level level) {
        if (!SunwellConfig.followDayNightCycle) {
            return false;
        }
        return level.dimensionType().fixedTime().isPresent()
                || !level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    public static boolean usesVirtualWeather(Level level) {
        if (!SunwellConfig.respondToWeather && !SunwellConfig.allowRainThrough) {
            return false;
        }
        if (level.dimensionType().fixedTime().isPresent()) {
            return true;
        }
        return !level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
    }

    public static int skyDarken(Level level) {
        return skyDarken(level, 0.0F);
    }

    public static int skyDarken(Level level, float partialTick) {
        if (!usesVirtualDayNight(level)) {
            return level.getSkyDarken();
        }
        float dayTicks = (level.getGameTime() % TICKS_PER_DAY) + partialTick;
        float skyAngle = dayTicks / TICKS_PER_DAY - 0.25F;
        float darken = 1.0F - (Mth.cos(skyAngle * ((float) Math.PI * 2F)) * 2.0F + 0.2F);
        return (int) (Mth.clamp(darken, 0.0F, 1.0F) * 11.0F);
    }

    public static float rainLevel(Level level, float partialTick) {
        if (!usesVirtualWeather(level)) {
            return level.getRainLevel(partialTick);
        }
        return virtualRainLevel(level.getGameTime(), partialTick);
    }

    public static float thunderLevel(Level level, float partialTick) {
        if (!usesVirtualWeather(level)) {
            return level.getThunderLevel(partialTick);
        }
        return virtualThunderLevel(level.getGameTime(), partialTick);
    }

    public static boolean isRaining(Level level) {
        if (!usesVirtualWeather(level)) {
            return level.isRaining();
        }
        return rainLevel(level, 1.0F) > 0.02F;
    }

    public static boolean isThundering(Level level) {
        if (!usesVirtualWeather(level)) {
            return level.isThundering();
        }
        return thunderLevel(level, 1.0F) > 0.02F;
    }

    private static float virtualRainLevel(long gameTime, float partialTick) {
        long tick = gameTime + (long) partialTick;
        long phase = tick % WEATHER_CYCLE;
        long rainEnd = RAIN_START + RAIN_DURATION;
        if (phase < RAIN_START || phase >= rainEnd) {
            return 0.0F;
        }
        if (phase < RAIN_START + WEATHER_FADE) {
            return (phase - RAIN_START) / (float) WEATHER_FADE;
        }
        if (phase >= rainEnd - WEATHER_FADE) {
            return (rainEnd - phase) / (float) WEATHER_FADE;
        }
        return 1.0F;
    }

    private static float virtualThunderLevel(long gameTime, float partialTick) {
        float rain = virtualRainLevel(gameTime, partialTick);
        if (rain < 0.35F) {
            return 0.0F;
        }
        long cycle = (gameTime + (long) partialTick) / WEATHER_CYCLE;
        if ((cycle & 3L) != 2L) {
            return 0.0F;
        }
        long phase = (gameTime + (long) partialTick) % WEATHER_CYCLE;
        long peak = RAIN_START + RAIN_DURATION / 2;
        long dist = Math.abs(phase - peak);
        if (dist > 2400L) {
            return 0.0F;
        }
        return Mth.clamp(1.0F - dist / 2400.0F, 0.0F, 1.0F) * rain;
    }
}
