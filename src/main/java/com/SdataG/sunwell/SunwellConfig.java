package com.SdataG.sunwell;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server config plus a hot, plain-field cache so the brightness mixin never has
 * to touch the ForgeConfigSpec accessors on the hot path.
 */
public final class SunwellConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue MAX_RADIUS;
    private static final ForgeConfigSpec.IntValue MAX_DEPTH;
    private static final ForgeConfigSpec.IntValue CONE_SPREAD;
    private static final ForgeConfigSpec.IntValue SKY_LEVEL;
    private static final ForgeConfigSpec.BooleanValue ATTENUATE_BY_DEPTH;
    private static final ForgeConfigSpec.BooleanValue ENABLE_UNDEAD_BURNING;
    private static final ForgeConfigSpec.BooleanValue BLOCK_HOSTILE_SPAWNS;
    private static final ForgeConfigSpec.BooleanValue FOLLOW_DAY_NIGHT_CYCLE;
    private static final ForgeConfigSpec.BooleanValue RESPOND_TO_WEATHER;
    private static final ForgeConfigSpec.BooleanValue RESPOND_TO_SURFACE_LIGHT;
    private static final ForgeConfigSpec.BooleanValue LANTERN_FLUX;
    private static final ForgeConfigSpec.BooleanValue ALLOW_RAIN_THROUGH;
    private static final ForgeConfigSpec.BooleanValue SNOW_ACCUMULATION;
    private static final ForgeConfigSpec.IntValue SNOW_ACCUMULATE_ODDS;
    private static final ForgeConfigSpec.IntValue SNOW_MAX_LAYERS;
    private static final ForgeConfigSpec.BooleanValue WEATHER_SHAFT_PARTICLES;
    private static final ForgeConfigSpec.IntValue LIGHTNING_THROUGH_ODDS;
    private static final ForgeConfigSpec.IntValue LIGHTNING_ROD_BOOST;
    private static final ForgeConfigSpec.BooleanValue LIGHTNING_VISUAL_ONLY;
    private static final ForgeConfigSpec.IntValue NODE_BUDGET_PER_TICK;
    private static final ForgeConfigSpec.IntValue CHUNK_BUDGET_PER_TICK;
    private static final ForgeConfigSpec.BooleanValue DEBUG_OPACITY_CHURN;
    private static final ForgeConfigSpec.BooleanValue DEBUG_SKY_STATE;
    private static final ForgeConfigSpec.BooleanValue DEBUG_WEATHER_PARTICLES;
    private static final ForgeConfigSpec.BooleanValue DEBUG_LIGHTNING;

    // Cached values (defaults match the spec defaults until the config loads).
    public static volatile int maxRadius = 12;
    public static volatile int maxDepth = 40;
    public static volatile int coneSpread = 1;
    public static volatile int skyLevel = 13;
    public static volatile boolean attenuateByDepth = false;
    public static volatile boolean enableUndeadBurning = true;
    public static volatile boolean blockHostileSpawns = true;
    public static volatile boolean followDayNightCycle = true;
    public static volatile boolean respondToWeather = true;
    public static volatile boolean respondToSurfaceLight = true;
    public static volatile boolean lanternFlux = true;
    public static volatile boolean allowRainThrough = true;
    public static volatile boolean snowAccumulation = true;
    public static volatile int snowAccumulateOdds = 12;
    public static volatile int snowMaxLayers = 8;
    public static volatile boolean weatherShaftParticles = true;
    public static volatile int lightningThroughOdds = 80000;
    public static volatile int lightningRodBoost = 40;
    public static volatile boolean lightningVisualOnly = false;
    public static volatile int nodeBudgetPerTick = 80000;
    public static volatile int chunkBudgetPerTick = 24;
    public static volatile boolean debugOpacityChurn = false;
    public static volatile boolean debugSkyState = false;
    public static volatile boolean debugWeatherParticles = false;
    public static volatile boolean debugLightning = false;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("How far the sunwell floods out from each source block.").push("region");
        MAX_RADIUS = b.comment("Maximum horizontal radius (in blocks) the sunwell floods from a source.")
                .defineInRange("maxRadius", 12, 1, 64);
        MAX_DEPTH = b.comment("Maximum vertical depth (in blocks) below a source the sunwell floods.")
                .defineInRange("maxDepth", 40, 1, 256);
        CONE_SPREAD = b.comment("Extra horizontal radius gained per block of descent, so a higher ceiling lamp",
                        "lights a wider area on the floor below (a cone / 'small sun'). 0 = a straight",
                        "cylinder. Higher values widen the cone but enlarge the lit volume (more work).")
                .defineInRange("coneSpread", 1, 0, 8);
        b.pop();

        b.comment("The virtual sky light that growth code sees inside a region.").push("light");
        SKY_LEVEL = b.comment("Virtual SKY-light level applied inside a region. Dynamic Trees needs >=12 here.",
                        "Keep at or below 14: canSeeSky() requires exactly 15, so 14 grows everything",
                        "(crops, saplings, Dynamic Trees) without making mobs think they're under open sky.")
                .defineInRange("skyLevel", 13, 1, 14);
        ATTENUATE_BY_DEPTH = b.comment("If true, the virtual sky level falls off with distance from the source.",
                        "Leave false so plants everywhere in range keep enough light to grow.")
                .define("attenuateByDepth", false);
        b.pop();

        b.comment("Side effects of treating an area as open sky.").push("behavior");
        ENABLE_UNDEAD_BURNING = b.comment("If true, undead sun-burn inside sunwell regions during the day.",
                        "Works at the default sky level of 13 via MobMixin (no need to raise skyLevel to 15).",
                        "Replaces the old preventBurning option — invert the value when migrating an existing config.")
                .define("enableUndeadBurning", true);
        BLOCK_HOSTILE_SPAWNS = b.comment("If true, monsters are blocked from spawning inside a sunwell region,",
                        "just like daylight would. (Raised light already suppresses most spawns.)")
                .define("blockHostileSpawns", true);
        FOLLOW_DAY_NIGHT_CYCLE = b.comment("If true, virtual sunwell follows the dimension day/night cycle",
                        "like a real hole in the ceiling (no sunwell at night).",
                        "Set false for always-on grow lights in sealed bases.",
                        "Liminal Industries backrooms preset: false for ceiling farms that must grow at night.")
                .define("followDayNightCycle", true);
        RESPOND_TO_WEATHER = b.comment("If true, rain and thunderstorms dim sunwell like overcast outdoor light.")
                .define("respondToWeather", true);
        RESPOND_TO_SURFACE_LIGHT = b.comment("If true, sunwell strength follows the real outdoor sky level",
                        "at the surface column above each block (night, caves open to sky, etc.).")
                .define("respondToSurfaceLight", true);
        LANTERN_FLUX = b.comment("If true, virtual sunwell gently flickers with the same curve as lantern glow VFX.")
                .define("lanternFlux", true);
        ALLOW_RAIN_THROUGH = b.comment("If true, rain and snow can fall through sunwell regions while the",
                        "dimension has active weather, like an open hole in the ceiling.",
                        "Requires #sunwell:rain_through on the source block (default lanterns only unless tagged).",
                        "Independent of undead sun-burn (controlled by enableUndeadBurning).")
                .define("allowRainThrough", true);
        WEATHER_SHAFT_PARTICLES = b.comment("Client-only glow shafts and weather particles under sources that have",
                        "both #sunwell:dynamic_exposure and #sunwell:rain_through (default lanterns).",
                        "Static grow lamps (#sunwell:sunwell_source only) never spawn these.")
                .define("weatherShaftParticles", true);
        LIGHTNING_THROUGH_ODDS = b.comment("During thunderstorms, 1-in-N chance per server tick to strike a random",
                        "full-profile lantern column near a player. 0 disables. Default is very rare.")
                .defineInRange("lightningThroughOdds", 80000, 0, 1_000_000);
        LIGHTNING_ROD_BOOST = b.comment("Multiplier making sunwell lightning far more frequent on a column with a",
                        "vanilla lightning_rod within 4 blocks (effective odds = lightningThroughOdds / this).",
                        "1 disables the rod bonus.")
                .defineInRange("lightningRodBoost", 40, 1, 100_000);
        LIGHTNING_VISUAL_ONLY = b.comment("If true, sunwell lightning is visual-only (flash + sound, no fire). Default false: a",
                        "real strike is rare (see lightningThroughOdds), so it lights fires and does damage",
                        "like true weather. A lightning rod in range still catches it harmlessly.")
                .define("lightningVisualOnly", false);
        SNOW_ACCUMULATION = b.comment("If true, snow layers gradually build up on the floor under a sunwell while it snows",
                        "(cold biome + active weather). Vanilla can't accumulate snow here because the room has no",
                        "real sky, so the sunwell places it to match the falling-snow particles.")
                .define("snowAccumulation", true);
        SNOW_ACCUMULATE_ODDS = b.comment("1-in-N chance per server tick to add snow somewhere in a snowing sunwell's cone.",
                        "Lower = faster buildup. Only fires near players, in snow biomes.")
                .defineInRange("snowAccumulateOdds", 12, 1, 10000);
        SNOW_MAX_LAYERS = b.comment("How deep sunwell snow may pile (1-8 vanilla snow layers; 8 = a full block).")
                .defineInRange("snowMaxLayers", 8, 1, 8);
        b.pop();

        b.comment("Performance budgets. Recomputes are spread over ticks and never exceed these.").push("performance");
        NODE_BUDGET_PER_TICK = b.comment("Soft cap on flood-fill cells processed per tick. Higher = faster light updates,",
                        "lower = smoother ticks. Deeper reach + a wide cone need a bigger budget.")
                .defineInRange("nodeBudgetPerTick", 80000, 1000, 1000000);
        CHUNK_BUDGET_PER_TICK = b.comment("Maximum chunks whose virtual light may be rebuilt in a single tick.")
                .defineInRange("chunkBudgetPerTick", 24, 1, 256);
        DEBUG_OPACITY_CHURN = b.comment("If true, log each opacity-driven sunwell invalidation (debug only).")
                .define("debugOpacityChurn", false);
        DEBUG_SKY_STATE = b.comment("If true, log the client sky state (night/storm/thunder -> which orb)",
                        "every 2 seconds. Use to diagnose the orb not matching the sky.")
                .define("debugSkyState", false);
        DEBUG_WEATHER_PARTICLES = b.comment("If true, log why lantern rain/snow particles are or aren't spawning",
                        "every 2 seconds (which gate rejected each source). Use to diagnose a dry cone.")
                .define("debugWeatherParticles", false);
        DEBUG_LIGHTNING = b.comment("If true, log where each sunwell lightning strike lands and why -- the source,",
                        "how many entity/floor candidates were found, which gates rejected samples, and the",
                        "chosen spot. Use to diagnose strikes hitting empty air near the lamp.")
                .define("debugLightning", false);
        b.pop();

        SPEC = b.build();
    }

    public static void bake() {
        maxRadius = MAX_RADIUS.get();
        maxDepth = MAX_DEPTH.get();
        coneSpread = CONE_SPREAD.get();
        skyLevel = SKY_LEVEL.get();
        attenuateByDepth = ATTENUATE_BY_DEPTH.get();
        enableUndeadBurning = ENABLE_UNDEAD_BURNING.get();
        blockHostileSpawns = BLOCK_HOSTILE_SPAWNS.get();
        followDayNightCycle = FOLLOW_DAY_NIGHT_CYCLE.get();
        respondToWeather = RESPOND_TO_WEATHER.get();
        respondToSurfaceLight = RESPOND_TO_SURFACE_LIGHT.get();
        lanternFlux = LANTERN_FLUX.get();
        allowRainThrough = ALLOW_RAIN_THROUGH.get();
        weatherShaftParticles = WEATHER_SHAFT_PARTICLES.get();
        lightningThroughOdds = LIGHTNING_THROUGH_ODDS.get();
        lightningRodBoost = LIGHTNING_ROD_BOOST.get();
        lightningVisualOnly = LIGHTNING_VISUAL_ONLY.get();
        snowAccumulation = SNOW_ACCUMULATION.get();
        snowAccumulateOdds = SNOW_ACCUMULATE_ODDS.get();
        snowMaxLayers = SNOW_MAX_LAYERS.get();
        nodeBudgetPerTick = NODE_BUDGET_PER_TICK.get();
        chunkBudgetPerTick = CHUNK_BUDGET_PER_TICK.get();
        debugOpacityChurn = DEBUG_OPACITY_CHURN.get();
        debugSkyState = DEBUG_SKY_STATE.get();
        debugWeatherParticles = DEBUG_WEATHER_PARTICLES.get();
        debugLightning = DEBUG_LIGHTNING.get();
    }

    private SunwellConfig() {
    }
}
