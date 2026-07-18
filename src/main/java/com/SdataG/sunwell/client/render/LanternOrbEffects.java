package com.SdataG.sunwell.client.render;

import com.SdataG.sunwell.SunwellConfig;
import com.SdataG.sunwell.SunwellExposure;
import com.SdataG.sunwell.SunwellVirtualEnvironment;
import com.SdataG.sunwell.client.LanternModelBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Client orb animation and color math — mirrors {@link SunwellExposure} curves used by virtual sky.
 */
public final class LanternOrbEffects {

    private static final float BOB_HEIGHT = 0.038F;
    private static final float BOB_WOBBLE = 0.008F;
    private static final float MIN_EXPOSURE = 0.22F;

    /** Shared clock for bob, glow, and flicker. */
    private static final float ANIM_SPEED = 0.17F;

    /** Halo breathe amplitudes — subtle so the glow reads as a steady bloom, not a strobe. */
    private static final float GLOW_BREATHE_RADIUS = 0.03F;
    private static final float GLOW_BREATHE_ALPHA = 0.05F;

    private static final float SUN_R = 1.00F;
    private static final float SUN_G = 0.88F;
    private static final float SUN_B = 0.54F;

    /** Pale, cool moonlight the orb fades to at night. */
    private static final float MOON_R = 0.58F;
    private static final float MOON_G = 0.70F;
    private static final float MOON_B = 1.00F;

    /** Flat, desaturated overcast the orb fades to under rain. */
    private static final float OVERCAST_R = 0.55F;
    private static final float OVERCAST_G = 0.60F;
    private static final float OVERCAST_B = 0.67F;

    /** Darker, colder still for a thunderhead. */
    private static final float STORM_TR = 0.40F;
    private static final float STORM_TG = 0.44F;
    private static final float STORM_TB = 0.52F;

    private LanternOrbEffects() {
    }

    /**
     * 0 = full daylight, 1 = night, ramping across dusk/dawn. Taken from the <em>sun's actual angle</em>.
     *
     * <p>Deliberately not {@code SunwellVirtualEnvironment.skyDarken}: that consults the daylight
     * gamerule, and the client keeps its own local copy of gamerules ({@code ClientLevelData} builds a
     * fresh {@code new GameRules()}), so it could divert to a {@code getGameTime()}-based virtual
     * cycle. getGameTime is total world ticks and has nothing to do with {@code /time set} — the orb
     * ended up reading a different time of day than the sky overhead. That virtual cycle is for
     * gameplay in fixed-time dimensions; the orb's <em>look</em> must follow the real sun.</p>
     *
     * <p>Also not {@code getSkyDarken()}, which rises with rain — that would make a daytime storm
     * read as "night" and drag the tint toward the moon.</p>
     */
    public static float nightFactor(Level level) {
        float cos = Mth.cos(level.getTimeOfDay(1.0F) * ((float) Math.PI * 2F)); // 1 = noon, -1 = midnight
        // Vanilla's own dusk/dawn ramp, lifted verbatim from Level.getSkyDarken():
        //     g = 1 - (cos * 2 + 0.2), clamped
        // which is 0 while cos > 0.4 (day), reaching 1 by cos = -0.1 (night). Using Minecraft's exact
        // curve means the orb darkens on precisely the same schedule the world does — dusk and dawn
        // take as long as they take, stretched across the real ~1.5 minutes of in-game twilight,
        // instead of a made-up fade timer that ran on its own clock.
        return Mth.clamp(0.8F - 2.0F * cos, 0.0F, 1.0F);
    }

    /** Cloud cover, straight off vanilla's rain ramp (it moves 0.01/tick — a natural ~5s roll-in). */
    public static float rainFactor(Level level, float partialTick) {
        if (!SunwellConfig.respondToWeather) {
            return 0.0F;
        }
        return Mth.clamp(SunwellVirtualEnvironment.rainLevel(level, partialTick), 0.0F, 1.0F);
    }

    /** How much of the cloud cover is thunderhead rather than plain rain. Vanilla's own ramp again. */
    public static float thunderFactor(Level level, float partialTick) {
        if (!SunwellConfig.respondToWeather) {
            return 0.0F;
        }
        return Mth.clamp(SunwellVirtualEnvironment.thunderLevel(level, partialTick), 0.0F, 1.0F);
    }

    /**
     * The light one sky casts: what colour, how bright the orb burns, how strong the halo, and
     * whether there are sun shafts.
     *
     * @param rays shafts are a sunbeam — only the sun has them; a moon or overcast sky casts none.
     */
    public record SkyLight(float red, float green, float blue, float brightness, float glow, float rays) {
    }

    private static final SkyLight SUN_LIGHT = new SkyLight(SUN_R, SUN_G, SUN_B, 1.00F, 1.00F, 1.00F);
    private static final SkyLight MOON_LIGHT = new SkyLight(MOON_R, MOON_G, MOON_B, 0.58F, 0.48F, 0.0F);
    private static final SkyLight CLOUDY_LIGHT = new SkyLight(OVERCAST_R, OVERCAST_G, OVERCAST_B, 0.70F, 0.34F, 0.0F);
    private static final SkyLight STORM_LIGHT = new SkyLight(STORM_TR, STORM_TG, STORM_TB, 0.50F, 0.18F, 0.0F);

    /**
     * The light for one sky. Sun and moon already <em>are</em> a time of day, so they carry their own
     * brightness; the clouds hide the sky rather than replace it, so night still dims them further —
     * a storm at midnight is darker than the same storm at noon.
     */
    private static SkyLight lightFor(SkyOrb orb, float night) {
        return switch (orb) {
            case SUN -> SUN_LIGHT;
            case MOON -> MOON_LIGHT;
            case CLOUDY -> nightDimmed(CLOUDY_LIGHT, night);
            case STORM -> nightDimmed(STORM_LIGHT, night);
        };
    }

    private static SkyLight nightDimmed(SkyLight base, float night) {
        return new SkyLight(
                base.red(), base.green(), base.blue(),
                base.brightness() * (1.0F - 0.35F * night),
                base.glow() * (1.0F - 0.52F * night),
                base.rays());
    }

    /**
     * The light on screen right now: each sky's light weighted by how much of that sky is showing.
     *
     * <p>Colour, brightness, halo and shafts are the <em>same function of the same mix</em> that
     * chooses the artwork, so they cannot drift apart. Blending all four (rather than just the two
     * being drawn) costs nothing and keeps the light exact through a storm arriving at dusk.</p>
     */
    public static SkyLight skyLight(SkyMix mix, float night) {
        float red = 0.0F, green = 0.0F, blue = 0.0F;
        float brightness = 0.0F, glow = 0.0F, rays = 0.0F;
        for (SkyOrb orb : SkyOrb.values()) {
            float w = mix.weightOf(orb);
            if (w <= 0.0F) {
                continue;
            }
            SkyLight l = lightFor(orb, night);
            red += l.red() * w;
            green += l.green() * w;
            blue += l.blue() * w;
            brightness += l.brightness() * w;
            glow += l.glow() * w;
            rays += l.rays() * w;
        }
        return new SkyLight(red, green, blue, brightness, glow, rays);
    }

    /** Rate-limits the debugSkyState trace to one line every 2 seconds. */
    private static long debugTick = Long.MIN_VALUE;

    /**
     * How much of each sky is showing right now. Weights always sum to 1.
     *
     * <p>There is no state machine and no fade timer here any more. Every previous version picked a
     * target orb on a threshold and then dissolved toward it at a rate I invented (8s, 2.5s, 15s) —
     * which is why transitions felt like they took forever and never lined up with the sky. The mix
     * IS the sky: rain draws the clouds in over vanilla's own 0.01/tick ramp (~5s), and night trades
     * sun for moon along vanilla's own dusk curve. Nothing to keep in sync, because there is only one
     * clock.</p>
     */
    public static SkyMix skyMix(float night, float rain, float thunder) {
        float clear = 1.0F - rain;
        return new SkyMix(
                clear * (1.0F - night),   // sun
                clear * night,            // moon
                rain * (1.0F - thunder),  // cloudy
                rain * thunder);          // storm
    }

    /** @param sun/moon/cloudy/storm visible fraction of each sky; sums to 1. */
    public record SkyMix(float sun, float moon, float cloudy, float storm) {

        float weightOf(SkyOrb orb) {
            return switch (orb) {
                case SUN -> sun;
                case MOON -> moon;
                case CLOUDY -> cloudy;
                case STORM -> storm;
            };
        }

        /** How cloud-shaped the orb should be: 0 = round sun/moon, 1 = fully a cloud. */
        public float cloudiness() {
            return Mth.clamp(cloudy + storm, 0.0F, 1.0F);
        }
    }

    /**
     * The two skies worth drawing, heaviest first.
     *
     * <p>Up to four can technically be showing at once (a thunderstorm rolling in at dusk), but only
     * ever two carry real weight, and two is what the cross-fade draw path costs. The light blends
     * across all four exactly — that is cheap — so nothing is lost where it would be visible.</p>
     */
    public static OrbState orbState(Level level, float night, float rain, float thunder) {
        SkyMix mix = skyMix(night, rain, thunder);

        SkyOrb top = SkyOrb.SUN;
        SkyOrb second = SkyOrb.MOON;
        float topW = -1.0F;
        float secondW = -1.0F;
        for (SkyOrb orb : SkyOrb.values()) {
            float w = mix.weightOf(orb);
            if (w > topW) {
                second = top;
                secondW = topW;
                top = orb;
                topW = w;
            } else if (w > secondW) {
                second = orb;
                secondW = w;
            }
        }

        // fade runs 0.5 (an even split) -> 1 (settled on `to`). When the two swap rank the pair simply
        // reverses at a 50/50 mix, which renders identically — so the crossover is seamless.
        float total = topW + secondW;
        float fade = total <= 1.0E-4F ? 1.0F : topW / total;
        OrbState state = new OrbState(second, top, fade, mix.cloudiness());

        long tick = level.getGameTime();
        if (SunwellConfig.debugSkyState && tick != debugTick && tick % 40L == 0L) {
            debugTick = tick;
            com.SdataG.sunwell.Sunwell.LOGGER.info(
                    "[sunwell] sky: night={} rain={} thunder={} -> sun={} moon={} cloudy={} storm={} "
                            + "(drawing {}->{} fade={})",
                    String.format("%.2f", night), String.format("%.2f", rain), String.format("%.2f", thunder),
                    String.format("%.2f", mix.sun()), String.format("%.2f", mix.moon()),
                    String.format("%.2f", mix.cloudy()), String.format("%.2f", mix.storm()),
                    second, top, String.format("%.2f", fade));
        }
        return state;
    }

    /**
     * @param fade       0.5 = an even split of {@code from}/{@code to}, 1 = settled on {@code to}.
     * @param cloudiness 0 = round sun/moon, 1 = fully cloud-shaped; drives the squash morph.
     */
    public record OrbState(SkyOrb from, SkyOrb to, float fade, float cloudiness) {
        public boolean settled() {
            return fade >= 0.995F || from == to;
        }
    }

    /** The sky the orb is currently standing in for. */
    public enum SkyOrb {
        SUN,
        MOON,
        CLOUDY,
        STORM;

        /** True for the cloud-shaped skies. SUN and MOON are both the round orb. */
        public boolean isCloud() {
            return this == CLOUDY || this == STORM;
        }
    }

    public static float flux(Level level, BlockPos pos, float partialTick) {
        return SunwellExposure.fluxMultiplier(level, pos, partialTick);
    }

    /** Primary breathe wave — bob, glow, and flicker share this phase family. */
    private static float breatheWave(Level level, BlockPos pos, float partialTick) {
        float time = SunwellExposure.animationTime(level, pos, partialTick);
        float phase = SunwellExposure.phase(pos) * 1.91F + SunwellExposure.fastPhase(pos) * 0.43F;
        return Mth.sin(time * ANIM_SPEED + phase);
    }

    public static float bobOffset(Level level, BlockPos pos, float partialTick) {
        float wave = breatheWave(level, pos, partialTick);
        float time = SunwellExposure.animationTime(level, pos, partialTick);
        float drift = Mth.sin(time * ANIM_SPEED * 0.53F + SunwellExposure.phase(pos) * 0.31F) * BOB_WOBBLE;
        return wave * BOB_HEIGHT + drift;
    }

    /** Model geometry scale — fixed; subtle breathe is glow-only. */
    public static float scalePulse(Level level, BlockPos pos, float partialTick) {
        return 1.0F;
    }

    /** Subtle halo radius oscillation (±3% peak). */
    public static float glowRadiusMultiplier(Level level, BlockPos pos, float partialTick) {
        return 1.0F + breatheWave(level, pos, partialTick) * GLOW_BREATHE_RADIUS;
    }

    /** Subtle halo alpha oscillation (±5% peak). */
    public static float glowAlphaMultiplier(Level level, BlockPos pos, float partialTick) {
        return 1.0F + breatheWave(level, pos, partialTick) * GLOW_BREATHE_ALPHA;
    }

    /**
     * Brightness breathe. Calm weather: a barely-there ambient pulse (0.97–1.0). During rain and
     * especially thunderstorms the orb picks up a faster, deeper flicker so it visibly "reads" the
     * storm — down to ~0.70 in a full thunderstorm. No flicker at all when lanternFlux is off.
     */
    public static float textureFlicker(Level level, BlockPos pos, float partialTick) {
        if (!SunwellConfig.lanternFlux) {
            return 1.0F;
        }
        // A single slow breathe and nothing else. The fast/crackle storm terms that used to ride on
        // top read as the orb texture strobing; the storm is already conveyed by the overcast tint
        // and the dimming, so the texture itself stays steady.
        float wave = breatheWave(level, pos, partialTick);
        return Mth.clamp(0.99F + wave * 0.01F, 0.98F, 1.0F);
    }

    /** Slow rotation angle (radians) for the radiant shafts, per-lantern phase so they don't spin in sync. */
    public static float rayPhase(Level level, BlockPos pos, float partialTick) {
        return SunwellExposure.animationTime(level, pos, partialTick) * 0.02F + SunwellExposure.phase(pos);
    }

    /**
     * 1 the moment a lightning bolt lights up the sky, fading over the same ~2 ticks the vanilla sky
     * flash lasts, else 0. Lets the orb flash white in sync with real thunderstorm lightning.
     */
    public static float skyFlash(Level level) {
        if (level instanceof net.minecraft.client.multiplayer.ClientLevel client) {
            return Mth.clamp(client.getSkyFlashTime() / 2.0F, 0.0F, 1.0F);
        }
        return 0.0F;
    }

    public static float orbPivotY(boolean hanging) {
        LanternModelBounds.FlameBox orb = LanternModelBounds.orbBox(hanging);
        return (orb.minY() + orb.maxY()) * 0.5F / 16.0F;
    }

    public static float exposure(Level level, BlockPos pos, float partialTick) {
        float dayNight = clientDayNightMultiplier(level);
        float weather = clientWeatherMultiplier(level);
        float flux = flux(level, pos, partialTick);

        int outdoorSky = SunwellExposure.sampleOutdoorSkyLight(level, pos.getX(), pos.getZ());
        float surface = SunwellExposure.surfaceMultiplier(level, outdoorSky);
        float environment = dayNight * weather * surface;

        return Mth.clamp(environment * flux, MIN_EXPOSURE, 1.0F);
    }

    /** Shade level for the orb texture — day, weather, and flicker all dim the quads. */
    public static int shadedLight(float exposure, float flux, float flicker) {
        float shade = Mth.clamp(exposure * flux * flicker, 0.30F, 1.0F);
        int level = Mth.floor(4.0F + shade * 11.0F);
        return net.minecraft.client.renderer.LightTexture.pack(level, level);
    }

    private static float clientWeatherMultiplier(Level level) {
        return SunwellExposure.weatherMultiplier(level);
    }

    private static float clientDayNightMultiplier(Level level) {
        return SunwellExposure.dayNightMultiplier(level);
    }

}
