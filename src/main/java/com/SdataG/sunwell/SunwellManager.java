package com.SdataG.sunwell;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;

/**
 * Per-{@link Level} cache of virtual skylight. Server levels run the full optimized
 * recompute pipeline; client levels only maintain a rain-through cache near the player
 * when {@link SunwellConfig#allowRainThrough} is enabled.
 */
public final class SunwellManager {

    private static final Map<Level, SunwellManager> MANAGERS = new ConcurrentHashMap<>();
    private static final Map<Object, SunwellManager> ENGINES = new ConcurrentHashMap<>();
    private static final Long2ObjectOpenHashMap<Long2ByteOpenHashMap> EMPTY = new Long2ObjectOpenHashMap<>();

    /** True while at least one level has published lit chunks. Hot paths check this first. */
    public static volatile boolean ACTIVE = false;

    private static final ThreadLocal<Boolean> SUPPRESS_BOOST = ThreadLocal.withInitial(() -> false);

    /** Client rain cache only updates chunks within this radius of the local player. */
    private static final int CLIENT_RAIN_CHUNK_RADIUS = 8;

    /** Sources within this squared distance share one flood representative (adjacent ceiling grids). */
    private static final int CLUSTER_MERGE_DISTANCE_SQ = 4;


    private final Level level;

    // Server-thread-only state (client writes on client thread).
    private final Long2ObjectOpenHashMap<LongOpenHashSet> sourcesInChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Long2ByteOpenHashMap> litByChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Long2ByteOpenHashMap> profileByChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ByteOpenHashMap sourceProfileByPos = new Long2ByteOpenHashMap();
    private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
    private int nextBatchSize = -1;

    private Object skyEngineKey;
    private Object blockEngineKey;
    private Object levelEngineKey;
    /** Last observed Iris/Oculus shader-active state (client only), for re-meshing on toggle. */
    private boolean lastShadersActive;

    /** Chunk-load scans may be reported from worker threads; drained on the level thread. */
    private final ConcurrentLinkedQueue<LevelChunk> pendingScans = new ConcurrentLinkedQueue<>();

    /** Immutable snapshot for lock-free reads from other threads (light engine, spawn checks). */
    private volatile Long2ObjectOpenHashMap<Long2ByteOpenHashMap> litSnapshot = EMPTY;
    private volatile Long2ObjectOpenHashMap<Long2ByteOpenHashMap> profileSnapshot = EMPTY;

    /**
     * Outdoor sky per lit chunk (keyed by {@link ChunkPos#asLong}), sampled at the chunk centre.
     * Built on the level thread into a fresh map and published as an immutable volatile snapshot so
     * the render/light threads (which call {@link #skyAt} while meshing) only ever READ it — never
     * mutate. Mutating a shared {@code Long2ByteOpenHashMap} from concurrent mesh workers corrupted
     * its backing array (AIOOBE during rehash) and crashed on mass re-mesh, e.g. a weather change.
     */
    private static final Long2ByteOpenHashMap EMPTY_OUTDOOR = new Long2ByteOpenHashMap();
    private volatile Long2ByteOpenHashMap outdoorSnapshot = EMPTY_OUTDOOR;
    private int outdoorRefreshCounter;

    private SunwellManager(Level level) {
        this.level = level;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void onLevelLoad(Level level) {
        SunwellManager manager = new SunwellManager(level);
        MANAGERS.put(level, manager);
        Object sky = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.SKY);
        Object engine = level.getChunkSource().getLightEngine();
        manager.skyEngineKey = sky;
        manager.levelEngineKey = engine;
        ENGINES.put(sky, manager);
        ENGINES.put(engine, manager);
        // Block engine is registered on the CLIENT only: it exists purely to fill room light for
        // shaderpacks (see LightEngineMixin), so the server never pays a lookup for it.
        if (level.isClientSide) {
            Object block = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
            manager.blockEngineKey = block;
            ENGINES.put(block, manager);
        }
    }

    public static void onLevelUnload(Level level) {
        SunwellManager manager = MANAGERS.remove(level);
        if (manager != null) {
            if (manager.skyEngineKey != null) {
                ENGINES.remove(manager.skyEngineKey);
            }
            if (manager.blockEngineKey != null) {
                ENGINES.remove(manager.blockEngineKey);
            }
            if (manager.levelEngineKey != null) {
                ENGINES.remove(manager.levelEngineKey);
            }
            manager.litSnapshot = EMPTY;
            manager.profileSnapshot = EMPTY;
        }
        refreshActive();
    }

    public static SunwellManager get(Level level) {
        return MANAGERS.get(level);
    }

    public static SunwellManager byEngine(Object engine) {
        return ENGINES.get(engine);
    }

    public boolean isSkyEngine(Object engine) {
        return engine == this.skyEngineKey;
    }

    public boolean isBlockEngine(Object engine) {
        return this.blockEngineKey != null && engine == this.blockEngineKey;
    }

    /**
     * Client-only block-light fill for shaderpacks. Iris/Sodium don't render our virtual SKY light as
     * room brightness underground (their shadow pass sees no sun path down here), so when a shader pack
     * is active we also raise BLOCK light across the lit region to the same exposure-scaled level -- an
     * even fill that dims with day/night/weather like the sky it stands in for, not a torch falloff.
     * Never applied on the server, so growth and spawning are untouched.
     */
    public int shaderBlockFillAt(BlockPos pos) {
        return skyAt(pos);
    }

    /** Re-mesh the lit region when the shader pack is toggled, so the block-light fill takes effect. */
    private void maybeRerenderOnShaderToggle() {
        ShaderCompat.refresh();
        boolean now = ShaderCompat.shadersActive();
        if (now != this.lastShadersActive) {
            this.lastShadersActive = now;
            if (!this.litByChunk.isEmpty()) {
                markChunksForClientRerender(new LongOpenHashSet(this.litByChunk.keySet()));
            }
        }
    }

    /** @deprecated Use {@link #byEngine(Object)}; kept for mixin compatibility. */
    @Deprecated
    public static SunwellManager getBySkyEngine(Object skyEngine) {
        return byEngine(skyEngine);
    }

    public static boolean isBoostSuppressed() {
        return SUPPRESS_BOOST.get();
    }

    public static void setBoostSuppressed(boolean suppressed) {
        SUPPRESS_BOOST.set(suppressed);
    }

    public static int virtualSky(Level level, BlockPos pos) {
        if (!ACTIVE || level.isClientSide) {
            return 0;
        }
        SunwellManager m = MANAGERS.get(level);
        return m == null ? 0 : m.skyAt(pos);
    }

    /**
     * True when {@code pos} sits in a rain-through sunwell column during active dimension weather
     * and the local biome supports rain or snow at that position.
     */
    public static boolean allowsPrecipitationAt(Level level, BlockPos pos) {
        if (!SunwellConfig.allowRainThrough || !SunwellVirtualEnvironment.isRaining(level)) {
            return false;
        }
        if (!isInRainThroughRegion(level, pos)) {
            return false;
        }
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        // Inverted from vanilla isRainingAt (which rejects pos below the surface heightmap).
        // Underground sunwell rooms should receive precipitation; open air above the surface should not.
        if (pos.getY() > level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY()) {
            return false;
        }
        if (SunwellVirtualEnvironment.usesVirtualWeather(level)) {
            return true;
        }
        Biome.Precipitation precipitation = level.getBiome(pos).value().getPrecipitationAt(pos);
        return precipitation == Biome.Precipitation.RAIN || precipitation == Biome.Precipitation.SNOW;
    }

    /**
     * True when {@code pos} is under a rain-through sunwell source while the dimension has active
     * weather. Used for {@code canSeeSky} so rain collectors and other open-sky checks behave like
     * the block is outdoors without raising virtual sky light to 15.
     */
    public static boolean allowsSkyAccessForWeatherAt(Level level, BlockPos pos) {
        if (!SunwellConfig.allowRainThrough || !SunwellVirtualEnvironment.isRaining(level)) {
            return false;
        }
        if (!isInRainThroughRegion(level, pos)) {
            return false;
        }
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        if (pos.getY() > level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY()) {
            return false;
        }
        return true;
    }

    private static boolean isInRainThroughRegion(Level level, BlockPos pos) {
        SunwellManager manager = MANAGERS.get(level);
        return manager != null
                && manager.baseSkyAt(pos) > 0
                && SunwellProfile.hasRainThrough(manager.profileAt(pos));
    }

    public static void invalidateAll() {
        for (SunwellManager manager : MANAGERS.values()) {
            manager.invalidateFromConfig();
        }
        refreshActive();
    }

    private static void refreshActive() {
        boolean any = false;
        for (SunwellManager manager : MANAGERS.values()) {
            if (!manager.litSnapshot.isEmpty()) {
                any = true;
                break;
            }
        }
        ACTIVE = any;
    }

    private void invalidateFromConfig() {
        this.litByChunk.clear();
        this.profileByChunk.clear();
        this.litSnapshot = EMPTY;
        this.profileSnapshot = EMPTY;
        this.sourceProfileByPos.clear();
        this.dirtyChunks.clear();
        this.nextBatchSize = -1;
        this.outdoorSnapshot = EMPTY_OUTDOOR;
        this.outdoorRefreshCounter = 0;
        for (long ck : this.sourcesInChunk.keySet()) {
            markDirtyAround(ChunkPos.getX(ck), ChunkPos.getZ(ck));
        }
    }

    // ------------------------------------------------------------------ queries (any thread)

    /** Lock-free; safe from the light engine thread. */
    public int skyAt(BlockPos pos) {
        int base = baseSkyAt(pos);
        if (base <= 0) {
            return 0;
        }
        byte profile = profileAt(pos);
        if (SunwellProfile.hasDynamicExposure(profile)) {
            return SunwellExposure.scaleVirtualLight(this.level, pos, base, outdoorSkyAt(pos.getX(), pos.getZ()));
        }
        return Math.min(base, SunwellConfig.skyLevel);
    }

    /** Unscaled virtual sky from the flood snapshot. */
    public int baseSkyAt(BlockPos pos) {
        Long2ByteOpenHashMap map = chunkLitMap(this.litSnapshot, pos);
        if (map == null) {
            return 0;
        }
        return map.get(pos.asLong()) & 0xFF;
    }

    /** Behavior flags for the source profile that won at this position. */
    public byte profileAt(BlockPos pos) {
        Long2ByteOpenHashMap map = chunkLitMap(this.profileSnapshot, pos);
        if (map == null) {
            return 0;
        }
        return map.get(pos.asLong());
    }

    public boolean allowsUndeadBurningAt(BlockPos pos) {
        return SunwellConfig.enableUndeadBurning
                && baseSkyAt(pos) > 0
                && SunwellProfile.hasUndeadBurning(profileAt(pos));
    }

    public byte profileAtSource(BlockPos source) {
        return this.sourceProfileByPos.get(source.asLong());
    }


    /**
     * Rare visual lightning through full-profile lantern columns during thunderstorms. A vanilla
     * lightning rod anywhere in a column's lit cone strikes {@code lightningRodBoost}x
     * more often — the rod "attracts" the sunwell's storm.
     */
    public void tryWeatherLightning(ServerLevel level) {
        if (!SunwellConfig.allowRainThrough
                || SunwellConfig.lightningThroughOdds <= 0
                || !SunwellVirtualEnvironment.isThundering(level)
                || this.sourcesInChunk.isEmpty()
                || level.players().isEmpty()) {
            return;
        }

        int baseOdds = SunwellConfig.lightningThroughOdds;
        int rodOdds = Math.max(1, baseOdds / Math.max(1, SunwellConfig.lightningRodBoost));
        boolean rodTick = level.random.nextInt(rodOdds) == 0;
        boolean baseTick = level.random.nextInt(baseOdds) == 0;
        if (!rodTick && !baseTick) {
            return;
        }

        var players = level.players();
        BlockPos center = players.get(level.random.nextInt(players.size())).blockPosition();

        it.unimi.dsi.fastutil.longs.LongArrayList candidates = new it.unimi.dsi.fastutil.longs.LongArrayList();
        forEachSourceNear(center, 48, packed -> {
            if (SunwellProfile.hasWeatherPresentation(this.sourceProfileByPos.get(packed))) {
                candidates.add(packed);
            }
        });
        if (candidates.isEmpty()) {
            return;
        }

        BlockPos source = null;
        // The frequent rod roll only fires on a column whose lit cone actually contains a rod.
        if (rodTick) {
            int examined = 0;
            for (int i = 0; i < candidates.size() && examined < 24; i++) {
                BlockPos candidate = unpack(candidates.getLong(i));
                examined++;
                if (hasRodInCone(level, candidate)) {
                    source = candidate;
                    break;
                }
            }
        }
        // No rod (or rod roll didn't fire): fall back to the rare base strike on any column.
        if (source == null) {
            if (!baseTick) {
                return;
            }
            source = unpack(candidates.getLong(level.random.nextInt(candidates.size())));
        }

        // If ANY lightning rod stands in this lamp's lit region, the bolt catches on one instead of
        // the ground — a rod protects the whole cone, wherever it sits in range, not just dead centre.
        // With several rods, pick one at random so it doesn't always hammer the same one.
        java.util.List<BlockPos> rods = findRodsInCone(level, source);
        BlockPos rod = rods.isEmpty() ? null : rods.get(level.random.nextInt(rods.size()));

        // Land anywhere in the lit cone, not always straight under the lamp. A sunwell is a hole in
        // the ceiling: the bolt should come down through it and hit the floor somewhere in the pool it
        // lights, the same area the rain falls in.
        // A rod catches the bolt: it strikes the rod, harms nothing, starts no fire, and just powers
        // the rod's redstone — the whole point of a lightning rod. Only when there is NO rod does the
        // bolt fall into the cone and behave like weather (fire etc., per config).
        boolean caughtByRod = rod != null;
        BlockPos strike = caughtByRod ? rod : pickConeStrike(level, source);
        if (strike == null) {
            if (SunwellConfig.debugLightning) {
                com.SdataG.sunwell.Sunwell.LOGGER.info(
                        "[sunwell] strike-pick returned NULL for src={} -> falling back to empty air {} under the lamp",
                        source, source.below(1));
            }
            strike = source.below(1);
        }
        if (SunwellConfig.debugLightning) {
            com.SdataG.sunwell.Sunwell.LOGGER.info("[sunwell] BOLT src={} caughtByRod={} strike={} dropFromLamp={}",
                    source, caughtByRod, strike, source.getY() - strike.getY());
        }
        var bolt = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(strike.getX() + 0.5D, strike.getY(), strike.getZ() + 0.5D);
        // A caught bolt is always visual-only (no fire, no ground damage) no matter the config — the
        // rod absorbed it. A free strike honours lightningVisualOnly: false = a fully real bolt.
        bolt.setVisualOnly(caughtByRod || SunwellConfig.lightningVisualOnly);
        level.addFreshEntity(bolt);

        // Visual-only bolts skip vanilla's block effects, so power the rod ourselves — otherwise the
        // rod would flash but emit no redstone, which is the one thing a rod is for.
        if (caughtByRod) {
            BlockState rodState = level.getBlockState(rod);
            if (rodState.getBlock() instanceof net.minecraft.world.level.block.LightningRodBlock rodBlock) {
                rodBlock.onLightningStrike(rodState, level, rod);
            }
        }
    }

    /**
     * Build up snow on the floor of a snowing sunwell. Vanilla can't accumulate snow here because the
     * room has no real sky, so we place it ourselves to match the falling-snow particles. Rate-limited
     * ({@link SunwellConfig#snowAccumulateOdds}), player-local, snow biomes only, capped at
     * {@link SunwellConfig#snowMaxLayers} layers.
     */
    public void tryWeatherSnow(ServerLevel level) {
        if (!SunwellConfig.allowRainThrough
                || !SunwellConfig.snowAccumulation
                || !SunwellVirtualEnvironment.isRaining(level)
                || this.sourcesInChunk.isEmpty()
                || level.players().isEmpty()) {
            return;
        }
        if (level.random.nextInt(Math.max(1, SunwellConfig.snowAccumulateOdds)) != 0) {
            return;
        }

        var players = level.players();
        BlockPos center = players.get(level.random.nextInt(players.size())).blockPosition();

        it.unimi.dsi.fastutil.longs.LongArrayList candidates = new it.unimi.dsi.fastutil.longs.LongArrayList();
        forEachSourceNear(center, 48, packed -> {
            if (SunwellProfile.hasWeatherPresentation(this.sourceProfileByPos.get(packed))) {
                candidates.add(packed);
            }
        });
        if (candidates.isEmpty()) {
            return;
        }
        BlockPos source = unpack(candidates.getLong(level.random.nextInt(candidates.size())));

        // Only cold biomes get snow; a rainy biome under the same sunwell just rains.
        if (level.getBiome(source).value().getPrecipitationAt(source) != Biome.Precipitation.SNOW) {
            return;
        }

        int maxDepth = SunwellConfig.maxDepth;
        if (maxDepth <= 0) {
            return;
        }
        int dropHeight = coneDropHeight(level, source, maxDepth);
        int floorRadius = Math.max(1, litRadiusAtDepth(dropHeight, dropHeight));
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        // One placement per firing (rate is governed by snowAccumulateOdds) so snow builds slowly. Same
        // lit-shaft walk as the strike and rain: fall through air until inside the cone, then the first
        // solid block is the floor and snow settles on its top face.
        for (int i = 0; i < 1; i++) {
            double distance = floorRadius * Math.sqrt(level.random.nextDouble());
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            int x = net.minecraft.util.Mth.floor(source.getX() + 0.5D + Math.cos(angle) * distance);
            int z = net.minecraft.util.Mth.floor(source.getZ() + 0.5D + Math.sin(angle) * distance);

            // Walk to the real ground, treating our own snow pile (layers / powder snow / snow block) as
            // part of the column to build ON TOP of -- so floorTopY is the base of the pile and the
            // accumulate step deepens it rather than starting a new layer beside it.
            int floorTopY = Integer.MIN_VALUE;
            boolean everLit = false;
            for (int y = source.getY() - 1; y >= source.getY() - maxDepth && y >= minY; y--) {
                probe.set(x, y, z);
                BlockState st = level.getBlockState(probe);
                if (baseSkyAt(probe) > 0) {
                    everLit = true;
                }
                if (st.is(net.minecraft.world.level.block.Blocks.SNOW)) {
                    continue;   // our snow layer: keep descending to the ground beneath it
                }
                if (!st.getCollisionShape(level, probe).isEmpty()) {
                    if (everLit) {
                        floorTopY = y + 1;
                    }
                    break;
                }
            }
            if (floorTopY == Integer.MIN_VALUE) {
                continue;
            }
            accumulateSnow(level, new BlockPos(x, floorTopY, z));
        }
    }

    /** Add or deepen a snow layer at the floor cell, up to {@link SunwellConfig#snowMaxLayers}. */
    private void accumulateSnow(ServerLevel level, BlockPos base) {
        BlockState b = level.getBlockState(base);
        if (b.is(net.minecraft.world.level.block.Blocks.SNOW)) {
            int layers = b.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
            int max = Math.min(8, Math.max(1, SunwellConfig.snowMaxLayers));
            if (layers < max) {
                level.setBlockAndUpdate(base, b.setValue(
                        net.minecraft.world.level.block.SnowLayerBlock.LAYERS, layers + 1));
            }
            return;
        }
        if (!b.isAir()) {
            return;   // occupied by a plant, fluid, etc. -- don't overwrite it
        }
        BlockState snow = net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState();
        // Vanilla's own survival check: needs a sturdy top face below and won't sit on ice/etc.
        if (snow.canSurvive(level, base)) {
            level.setBlockAndUpdate(base, snow);
        }
    }

    /**
     * A random floor spot inside the lantern's lit cone for a bolt to hit, or {@code null}.
     *
     * <p>Samples the cone the same way the rain does — {@code sqrt(random)} so spots are spread evenly
     * over the disc's <em>area</em> rather than bunching at the rim — then walks down that column and
     * takes the last lit cell above the first solid block. Only ever returns somewhere the sunwell
     * genuinely reaches: it stops the moment the column leaves the lit region, so a bolt can't land
     * through a wall into an unlit room.</p>
     */
    /**
     * Radius this step of descent adds: {@code radius(depth+1) - radius(depth)}.
     *
     * <p>Integer differencing of the stretched curve, so the grant is 1 on a normal lamp and 0 on most
     * steps of a very tall one — which is the "spread every other block instead of every block" that
     * keeps a 30-block drop opening to exactly maxRadius at the floor instead of maxing out at 12 down.</p>
     */
    private static int coneGrant(int depthBelow, int maxR, int effectiveHeight) {
        int eff = Math.max(effectiveHeight, maxR);
        int now = Math.min(maxR, (int) ((long) depthBelow * maxR / eff));
        int next = Math.min(maxR, (int) ((long) (depthBelow + 1) * maxR / eff));
        return Math.max(0, next - now);
    }

    private BlockPos pickConeStrike(ServerLevel level, BlockPos source) {
        int maxDepth = SunwellConfig.maxDepth;
        if (maxDepth <= 0) {
            return null;
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int dropHeight = coneDropHeight(level, source, maxDepth);

        // Where a free (no-rod) bolt lands. Real lightning goes for the tallest thing around, so every
        // candidate carries a weight that grows the HIGHER it sits in the shaft — a tree canopy the bolt
        // meets on the way down, a raised ledge, an exposed mob or player. A weighted-random draw over
        // those weights keeps it a preference, never a certainty: flat open floor still gets hit, it's
        // just less likely than the tall target standing in the same pool of light.
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        java.util.List<Double> weights = new java.util.ArrayList<>();
        double total = 0.0D;

        // Living targets standing in the lit cone (mobs AND players). Strongly preferred but never a lock.
        int reachR = Math.max(1, litRadiusAtDepth(maxDepth, dropHeight));
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                source.getX() + 0.5D - reachR, source.getY() - maxDepth, source.getZ() + 0.5D - reachR,
                source.getX() + 0.5D + reachR + 1.0D, source.getY(), source.getZ() + 0.5D + reachR + 1.0D);
        for (net.minecraft.world.entity.LivingEntity target
                : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box)) {
            BlockPos ep = target.blockPosition();
            int depth = source.getY() - ep.getY();
            if (depth < 1 || depth > maxDepth) {
                continue;
            }
            int rr = litRadiusAtDepth(depth, dropHeight);
            double dx = target.getX() - (source.getX() + 0.5D);
            double dz = target.getZ() - (source.getZ() + 0.5D);
            if (dx * dx + dz * dz > (rr + 0.5D) * (rr + 0.5D)) {
                continue;   // outside the cone at the depth it stands
            }
            probe.set(ep.getX(), ep.getY(), ep.getZ());
            if (baseSkyAt(probe) <= 0) {
                continue;   // not actually under the open shaft
            }
            double w = 8.0D + (maxDepth - depth + 1) * 1.2D;   // living bonus + height
            spots.add(ep);
            weights.add(w);
            total += w;
        }

        // Floor / canopy spots sampled across the cone (the same area-uniform sampling the rain uses).
        // A strike that only clears a block under the lamp reads as broken, so a floor spot must drop at
        // least a few blocks to count as a normal candidate. Shallow spots are remembered ONLY as a last
        // resort for genuinely low rooms where nothing deeper exists — they never compete with real ones.
        int minDrop = Math.min(3, maxDepth);
        int entityCount = spots.size();   // everything added so far is a living target
        int rejWall = 0;                  // solid hit before the lit shaft opened here (a wall / overhang)
        int rejNoFloor = 0;               // inside the lit shaft but no floor within reach
        int rejShallow = 0;               // floor too close under the lamp to look like a real strike
        int deepestDropSeen = 0;
        BlockPos shallowFallback = null;
        int shallowDrop = 0;

        // Sample points across the LIT FLOOR DISC (exactly where the rain falls), not straight under the
        // lamp. The cone is narrow up top and only opens out lower down, so requiring open lit air ONE
        // block under the lamp rejected every spot out toward the floor edge — and when all samples
        // landed there the bolt found nothing and fell back to empty air by the lamp. This samples the
        // floor's actual radius and spreads uniformly over its area (sqrt of a uniform random).
        int floorRadius = Math.max(1, litRadiusAtDepth(dropHeight, dropHeight));
        for (int attempt = 0; attempt < 20; attempt++) {
            double distance = floorRadius * Math.sqrt(level.random.nextDouble());
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            int x = net.minecraft.util.Mth.floor(source.getX() + 0.5D + Math.cos(angle) * distance);
            int z = net.minecraft.util.Mth.floor(source.getZ() + 0.5D + Math.sin(angle) * distance);

            // Walk the column down from just under the lamp. This (x,z) may not be lit until some way
            // down (the cone widens with depth), so wait until we're inside the lit shaft, then take the
            // first solid block's top face as the floor. A solid hit BEFORE the shaft opens here is a
            // wall or overhang pressed against the lamp, not the room floor, and is rejected.
            int floorTopY = Integer.MIN_VALUE;
            boolean everLit = false;
            boolean hitWall = false;
            for (int y = source.getY() - 1; y >= source.getY() - maxDepth && y >= minY; y--) {
                probe.set(x, y, z);
                if (!level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()) {
                    if (everLit) {
                        floorTopY = y + 1;   // land on the exposed top face inside the lit shaft
                    } else {
                        hitWall = true;
                    }
                    break;
                }
                if (baseSkyAt(probe) > 0) {
                    everLit = true;
                }
            }
            if (floorTopY == Integer.MIN_VALUE) {
                if (hitWall) {
                    rejWall++;
                } else {
                    rejNoFloor++;
                }
                continue;
            }
            int drop = source.getY() - floorTopY;
            if (drop > deepestDropSeen) {
                deepestDropSeen = drop;
            }
            if (drop < minDrop) {
                if (drop > shallowDrop) {   // keep the best short spot in case the room really is that low
                    shallowDrop = drop;
                    shallowFallback = new BlockPos(x, floorTopY, z);
                }
                rejShallow++;
                continue;   // too shallow to look like a real strike
            }
            // Higher hit (smaller drop -> a tree top the bolt met, a raised ledge) preferred over a deep
            // pit floor, but every eligible spot keeps a solid base weight so flat floor is still struck
            // plenty often. It stays a lean, not a lock.
            double w = 2.0D + (maxDepth - drop);
            spots.add(new BlockPos(x, floorTopY, z));
            weights.add(w);
            total += w;
        }

        BlockPos chosen;
        if (spots.isEmpty()) {
            chosen = shallowFallback;   // low room: the deepest shallow spot, or null if nothing valid at all
        } else {
            double r = level.random.nextDouble() * total;
            BlockPos pick = spots.get(spots.size() - 1);
            for (int i = 0; i < spots.size(); i++) {
                r -= weights.get(i);
                if (r <= 0.0D) {
                    pick = spots.get(i);
                    break;
                }
            }
            chosen = pick;
        }
        if (SunwellConfig.debugLightning) {
            com.SdataG.sunwell.Sunwell.LOGGER.info(
                    "[sunwell] strike-pick src={} srcY={} maxDepth={} dropHeight={} floorRadius={} | {} entity + {}"
                            + " floor candidates (deepest drop seen={}) | rejects: wall={} noFloor={} tooShallow={} |"
                            + " shallowFallback={} -> CHOSEN {}",
                    source, source.getY(), maxDepth, dropHeight, floorRadius, entityCount,
                    spots.size() - entityCount, deepestDropSeen, rejWall, rejNoFloor, rejShallow,
                    shallowFallback, chosen);
        }
        return chosen;
    }

    /** True as soon as any lightning rod is found in this lamp's lit cone (early-exit, for boost checks). */
    private boolean hasRodInCone(ServerLevel level, BlockPos source) {
        return forEachRodInCone(level, source, null);
    }

    /** Every lightning rod standing in this lamp's lit cone. */
    private java.util.List<BlockPos> findRodsInCone(ServerLevel level, BlockPos source) {
        java.util.List<BlockPos> rods = new java.util.ArrayList<>();
        forEachRodInCone(level, source, rods);
        return rods;
    }

    /**
     * Walk the lit cone below {@code source} looking for lightning rods. Collects into {@code out} when
     * given; returns true as soon as one is found when {@code out} is null (cheap boost check). A rod
     * anywhere in the cone's radius at its depth counts, so a rod on a distant wall still guards the room.
     */
    private boolean forEachRodInCone(ServerLevel level, BlockPos source, java.util.List<BlockPos> out) {
        int maxDepth = SunwellConfig.maxDepth;
        int dropHeight = coneDropHeight(level, source, maxDepth);
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int depth = 0; depth <= maxDepth; depth++) {
            int y = source.getY() - depth;
            if (y < minY) {
                break;
            }
            int radius = litRadiusAtDepth(depth, dropHeight);
            int rsq = radius * radius;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > rsq) {
                        continue;
                    }
                    probe.set(source.getX() + dx, y, source.getZ() + dz);
                    if (level.getBlockState(probe).is(net.minecraft.world.level.block.Blocks.LIGHTNING_ROD)) {
                        if (out == null) {
                            return true;
                        }
                        out.add(probe.immutable());
                    }
                }
            }
        }
        return out != null && !out.isEmpty();
    }

    /**
     * Horizontal radius the flood reaches {@code depthBelowSource} blocks under a source — the same
     * cone growth {@link #flood} applies. Presentation (rain shafts) uses this so the effect matches
     * the region that is actually lit: a base radius right at the lamp (so a floor lantern still
     * covers a disc around itself) widening the further it falls (so a high ceiling lamp throws a
     * much wider pool on the ground).
     */
    public static int litRadiusAtDepth(int depthBelowSource) {
        return litRadiusAtDepth(depthBelowSource, 0);
    }

    /**
     * Cone radius {@code depthBelowSource} blocks under a lamp whose floor is {@code dropHeight} down.
     *
     * <p>Apex at the lamp, widening by {@code coneSpread} per block — until the drop is taller than
     * {@code maxRadius}. Past that the spread is <em>stretched</em>: a lamp 30 up gains 0.4 radius per
     * block instead of 1, so the cone still opens to exactly {@code maxRadius} at the floor rather
     * than hitting full width at 12 down and running as a cylinder for the last 18.</p>
     *
     * <p>The stretch only ever <em>slows</em> the spread. A drop shorter than {@code maxRadius} stays
     * 1:1 and simply lights a smaller pool — a lamp 4 blocks up gets a radius-4 pool, not a forced
     * 12. Pass {@code dropHeight <= 0} when the floor isn't known to get the plain 1:1 cone.</p>
     */
    public static int litRadiusAtDepth(int depthBelowSource, int dropHeight) {
        int maxR = SunwellConfig.maxRadius;
        int spread = Math.max(0, SunwellConfig.coneSpread);
        if (spread == 0) {
            return maxR;
        }
        int depth = Math.max(0, depthBelowSource);
        int effective = Math.max(dropHeight, maxR);
        if (dropHeight <= 0) {
            return Math.min(maxR, spread * depth);
        }
        return Math.min(maxR, (int) ((long) depth * maxR / effective));
    }

    /**
     * Blocks from {@code source} down to the first solid floor, capped at {@code maxDepth}.
     * Drives the cone stretch: how far the lamp has to open out over.
     */
    public static int coneDropHeight(net.minecraft.world.level.BlockGetter level, BlockPos source, int maxDepth) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int drop = 1; drop <= maxDepth; drop++) {
            probe.set(source.getX(), source.getY() - drop, source.getZ());
            if (!level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()) {
                return drop;
            }
        }
        return maxDepth;
    }

    /**
     * The state whose tags decide this position's sunwell, or {@code null} if it isn't a source.
     *
     * <p>Usually just the block itself. But a {@link Sunwell#SOURCE_HOLDER} — an Amendments wall
     * lantern — is not a source on its own: it is one only while holding a block that is, and it then
     * inherits that block's profile. That indirection is what lets a wall-mounted Sunwell Lantern
     * light and rain like a ceiling one while a wall-mounted vanilla lantern stays inert. Tagging the
     * holder itself would make every lantern in the game a sunwell.</p>
     */
    private static BlockState effectiveSourceState(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        if (state.is(Sunwell.SUNWELL_SOURCE)) {
            return state;
        }
        if (!state.is(Sunwell.SOURCE_HOLDER)) {
            return null;
        }
        BlockState held = com.SdataG.sunwell.integration.SunwellHeldSource.heldBlock(level, pos);
        return held != null && held.is(Sunwell.SUNWELL_SOURCE) ? held : null;
    }

    private static BlockPos unpack(long packed) {
        return new BlockPos(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
    }

    private static Long2ByteOpenHashMap chunkLitMap(Long2ObjectOpenHashMap<Long2ByteOpenHashMap> snap, BlockPos pos) {
        if (snap.isEmpty()) {
            return null;
        }
        return snap.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** Lock-free: safe to call from render/light threads. Reads the published snapshot, never mutates. */
    private int outdoorSkyAt(int x, int z) {
        if (!SunwellConfig.respondToSurfaceLight) {
            return 15;
        }
        Long2ByteOpenHashMap snap = this.outdoorSnapshot;
        long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
        if (snap.containsKey(chunkKey)) {
            return snap.get(chunkKey) & 0xFF;
        }
        // Not yet sampled (chunk just became lit): compute without caching. sampleOutdoorSkyLight only
        // reads (heightmap + suppressed light query), so it is safe off the level thread.
        return SunwellExposure.sampleOutdoorSkyLight(this.level, x, z);
    }

    /** Level-thread only: rebuild the per-chunk outdoor sky map and publish it as an immutable snapshot. */
    private void refreshOutdoorSkyCache() {
        if (!SunwellConfig.respondToSurfaceLight || this.litByChunk.isEmpty()) {
            this.outdoorSnapshot = EMPTY_OUTDOOR;
            return;
        }
        if (++this.outdoorRefreshCounter % 5 != 0) {
            return;
        }
        Long2ByteOpenHashMap rebuilt = new Long2ByteOpenHashMap(this.litByChunk.size());
        for (long chunkKey : this.litByChunk.keySet()) {
            int x = (ChunkPos.getX(chunkKey) << 4) + 8;
            int z = (ChunkPos.getZ(chunkKey) << 4) + 8;
            rebuilt.put(chunkKey, (byte) SunwellExposure.sampleOutdoorSkyLight(this.level, x, z));
        }
        this.outdoorSnapshot = rebuilt;
    }

    /**
     * The source lamp a bolt at {@code strike} most likely fell from: the nearest lit source directly
     * above it whose cone reaches down to it. Client-safe. {@code null} if none — then the bolt just
     * draws straight up its own column.
     *
     * <p>The bolt entity only knows where it landed, not which lamp spawned it, so the renderer has to
     * work backwards: a strike lands somewhere in a lamp's cone, so the lamp is up and — because the
     * strike is offset within that cone — usually off to one side. That horizontal offset is exactly
     * what makes the bolt <em>arc</em> from the orb across to its hit point.</p>
     */
    public BlockPos nearestSourceAbove(BlockPos strike) {
        int maxR = SunwellConfig.maxRadius + 2;
        int maxD = SunwellConfig.maxDepth;
        long[] best = {Long.MIN_VALUE};
        double[] bestScore = {Double.MAX_VALUE};
        forEachSourceNear(strike, maxR, packed -> {
            int sx = BlockPos.getX(packed);
            int sy = BlockPos.getY(packed);
            int sz = BlockPos.getZ(packed);
            int depth = sy - strike.getY();
            if (depth <= 0 || depth > maxD) {
                return;
            }
            double dx = sx - strike.getX();
            double dz = sz - strike.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz > litRadiusAtDepth(depth) + 1.5D) {
                return;
            }
            // Prefer the lamp most directly overhead; tie-break toward the closer one.
            double score = horiz + depth * 0.15D;
            if (score < bestScore[0]) {
                bestScore[0] = score;
                best[0] = packed;
            }
        });
        return best[0] == Long.MIN_VALUE ? null
                : new BlockPos(BlockPos.getX(best[0]), BlockPos.getY(best[0]), BlockPos.getZ(best[0]));
    }

    public boolean hasSources() {
        return !this.sourcesInChunk.isEmpty();
    }

    /** Outdoor sky level (0–15) for the surface column above {@code x}/{@code z}. */
    public int getOutdoorSkyLight(int x, int z) {
        return outdoorSkyAt(x, z);
    }

    public void forEachSourceNear(BlockPos center, int blockRadius, LongConsumer action) {
        if (this.sourcesInChunk.isEmpty()) {
            return;
        }
        int radiusSq = blockRadius * blockRadius;
        int minChunkX = (center.getX() - blockRadius) >> 4;
        int maxChunkX = (center.getX() + blockRadius) >> 4;
        int minChunkZ = (center.getZ() - blockRadius) >> 4;
        int maxChunkZ = (center.getZ() + blockRadius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LongOpenHashSet set = this.sourcesInChunk.get(ChunkPos.asLong(chunkX, chunkZ));
                if (set == null) {
                    continue;
                }
                for (long packed : set) {
                    int dx = BlockPos.getX(packed) - center.getX();
                    int dz = BlockPos.getZ(packed) - center.getZ();
                    if (dx * dx + dz * dz <= radiusSq) {
                        action.accept(packed);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ change notifications

    public void queueChunkScan(LevelChunk chunk) {
        this.pendingScans.add(chunk);
    }

    public void onSourceChanged(BlockPos pos, BlockState state, boolean isSource) {
        long packed = pos.asLong();
        long ck = chunkOf(packed);
        // A holder (wall lantern) reports isSource=false — it isn't tagged as a source itself. Resolve
        // what it holds before deciding, or mounting a lantern on a wall would silently drop its sunwell.
        // Derive rather than trust the caller's flag: a holder reports "relevant", but it's only a
        // real source while what it holds is one. A wall lantern holding a vanilla lantern resolves to
        // null here and is correctly removed.
        BlockState effective = effectiveSourceState(this.level, pos, state);
        if (effective != null) {
            this.sourcesInChunk.computeIfAbsent(ck, k -> new LongOpenHashSet()).add(packed);
            this.sourceProfileByPos.put(packed, SunwellProfile.fromState(effective));
        } else {
            LongOpenHashSet set = this.sourcesInChunk.get(ck);
            if (set != null) {
                set.remove(packed);
                if (set.isEmpty()) {
                    this.sourcesInChunk.remove(ck);
                }
            }
            this.sourceProfileByPos.remove(packed);
        }
        markDirtyAround(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void onOpacityChanged(BlockPos pos) {
        if (this.sourcesInChunk.isEmpty()) {
            return;
        }
        if (SunwellConfig.debugOpacityChurn) {
            Sunwell.LOGGER.debug("[sunwell] opacity invalidation at {}", pos);
        }
        markDirtyAround(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void unloadChunk(LevelChunk chunk) {
        long c = chunk.getPos().toLong();
        LongOpenHashSet set = this.sourcesInChunk.remove(c);
        boolean changed = this.litByChunk.remove(c) != null;
        changed |= this.profileByChunk.remove(c) != null;
        this.dirtyChunks.remove(c);
        if (set != null) {
            for (long packed : set) {
                this.sourceProfileByPos.remove(packed);
            }
            if (!set.isEmpty()) {
                markDirtyAround(chunk.getPos().x, chunk.getPos().z);
            }
        }
        if (changed) {
            publish();
        }
    }

    // ------------------------------------------------------------------ scanning

    private void drainScans() {
        LevelChunk chunk;
        while ((chunk = this.pendingScans.poll()) != null) {
            ChunkPos cp = chunk.getPos();
            if (this.level.getChunkSource().getChunkNow(cp.x, cp.z) != chunk) {
                continue;
            }
            scanChunk(chunk);
        }
    }

    private void scanChunk(LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        long ck = cp.toLong();
        LongOpenHashSet found = null;

        LevelChunkSection[] sections = chunk.getSections();
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                    || !section.maybeHas(state -> state.is(Sunwell.SUNWELL_SOURCE)
                            || state.is(Sunwell.SOURCE_HOLDER))) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState blockState = section.getBlockState(x, y, z);
                        BlockPos here = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        BlockState effective = effectiveSourceState(this.level, here, blockState);
                        if (effective != null) {
                            if (found == null) {
                                found = new LongOpenHashSet();
                            }
                            long packed = here.asLong();
                            found.add(packed);
                            this.sourceProfileByPos.put(packed, SunwellProfile.fromState(effective));
                        }
                    }
                }
            }
        }

        LongOpenHashSet old = found == null ? this.sourcesInChunk.remove(ck) : this.sourcesInChunk.put(ck, found);
        if (old != null) {
            for (long packed : old) {
                if (found == null || !found.contains(packed)) {
                    this.sourceProfileByPos.remove(packed);
                }
            }
        }
        boolean sourcesChanged = found == null ? old != null && !old.isEmpty() : !found.equals(old);
        if (sourcesChanged) {
            markDirtyAround(cp.x, cp.z);
        } else if (!this.litByChunk.containsKey(ck) && hasSourceNear(cp.x, cp.z)) {
            this.dirtyChunks.add(ck);
        }
    }

    // ------------------------------------------------------------------ dirty tracking

    /** Last whole-number virtual light level rendered, to detect weather/day-night brightness changes. */
    private int lastRenderedExposure = Integer.MIN_VALUE;

    /** Grace window (ticks) after the lit region first appears, during which we keep re-rendering it. */
    private int joinGraceTicks = 0;
    private boolean litWasEmpty = true;

    /**
     * Re-issue the client re-render for a few seconds after the lit region first appears.
     *
     * <p>On a fresh join (or walking into range) the light values are computed the moment the
     * flood runs, but the chunk sections they cover may not have finished meshing yet — so the
     * first {@code setSectionDirty} silently misses and the room stays dark until a block update.
     * {@link #rerenderLitOnExposureChange()} only retries when the brightness level changes, which
     * it won't at a fixed time of day. So when the lit region transitions empty -> populated, open a
     * short grace window and re-render every ten ticks; a later pass lands once the sections exist.</p>
     */
    private void maybeRerenderAfterJoin() {
        boolean empty = this.litByChunk.isEmpty();
        if (this.litWasEmpty && !empty) {
            this.joinGraceTicks = 100; // ~5s
        }
        this.litWasEmpty = empty;
        if (this.joinGraceTicks > 0) {
            this.joinGraceTicks--;
            if (!empty && this.joinGraceTicks % 10 == 0) {
                markChunksForClientRerender(new LongOpenHashSet(this.litByChunk.keySet()));
            }
        }
    }

    /**
     * Re-mesh every lit chunk when the effective virtual light steps up or down. The value the light
     * engine returns already reflects weather and time (it's scaled at query time), so nothing needs
     * re-flooding — the chunk mesh just has to be rebuilt to pick the new value up. Quantised to the
     * integer light level and ignoring the per-block flicker, so this fires a handful of times across a
     * transition, not every tick.
     */
    private void rerenderLitOnExposureChange() {
        if (this.litByChunk.isEmpty()) {
            this.lastRenderedExposure = Integer.MIN_VALUE;
            return;
        }
        float env = SunwellExposure.dayNightMultiplier(this.level) * SunwellExposure.weatherMultiplier(this.level);
        int levelNow = Math.round(env * SunwellConfig.skyLevel);
        if (levelNow == this.lastRenderedExposure) {
            return;
        }
        this.lastRenderedExposure = levelNow;
        markChunksForClientRerender(new LongOpenHashSet(this.litByChunk.keySet()));
    }

    private void markDirtyAround(int chunkX, int chunkZ) {
        int rc = radiusChunks();
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                int cx = chunkX + dx;
                int cz = chunkZ + dz;
                long key = ChunkPos.asLong(cx, cz);
                // Recompute a chunk if a source is near OR it currently holds lit cells. The second
                // clause is essential when the LAST source near a chunk is removed: without it the
                // chunk is never re-flooded and its stale virtual light lingers forever (breaking a
                // lone lamp left the region lit). A now-sourceless chunk floods to empty and clears.
                if (hasSourceNear(cx, cz) || this.litByChunk.containsKey(key)) {
                    this.dirtyChunks.add(key);
                }
            }
        }
    }

    private boolean hasSourceNear(int chunkX, int chunkZ) {
        int rc = radiusChunks();
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                if (this.sourcesInChunk.containsKey(ChunkPos.asLong(chunkX + dx, chunkZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ recompute

    public void processTick() {
        // Drain chunk scans FIRST. This used to sit below the client's `if (!hasSources()) return;`
        // guard, which deadlocked on every fresh join: drainScans() is the only thing that turns a
        // queued chunk scan into a registered source, so requiring a source before running it meant
        // the client could never bootstrap. Sources only ever appeared via a direct block change —
        // which is why placing any block "fixed" it, and why weather effects never started for
        // lanterns that were already there when you logged in.
        drainScans();

        if (this.level.isClientSide) {
            // Render virtual sky light on the client whenever sources are near the player — not just
            // while raining — so a ceiling lamp actually lights the room/ground below (the lantern's
            // block light alone falls off with distance and never reaches a far floor).
            if (!hasSources()) {
                clearClientRainCache();
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            BlockPos playerPos = mc.player.blockPosition();
            pruneDistantDirtyChunks(playerPos);
            if (this.litSnapshot.isEmpty()) {
                markDirtyAround(playerPos.getX() >> 4, playerPos.getZ() >> 4);
            }
            refreshOutdoorSkyCache();
            maybeRerenderAfterJoin();
            // Weather and day/night change the virtual light returned at query time, but the room keeps
            // whatever brightness it was last meshed at until something re-renders it — which is why the
            // floor went patchy on a weather change (only sections that happened to re-mesh updated).
            // Re-mesh the whole lit region whenever the effective brightness level actually changes.
            rerenderLitOnExposureChange();
            maybeRerenderOnShaderToggle();
        } else if (hasSources()) {
            refreshOutdoorSkyCache();
        }

        if (this.dirtyChunks.isEmpty()) {
            return;
        }

        int chunkBudget = this.level.isClientSide()
                ? Math.max(1, SunwellConfig.chunkBudgetPerTick / 2)
                : Math.max(1, SunwellConfig.chunkBudgetPerTick);
        if (this.nextBatchSize <= 0) {
            this.nextBatchSize = chunkBudget;
        }
        int batchSize = Math.min(this.nextBatchSize, chunkBudget);

        LongOpenHashSet batch = new LongOpenHashSet(batchSize);
        LongIterator it = this.dirtyChunks.iterator();
        while (it.hasNext() && batch.size() < batchSize) {
            long c = it.nextLong();
            it.remove();
            if (this.level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) != null) {
                batch.add(c);
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        int rc = radiusChunks();
        LongOpenHashSet seeds = new LongOpenHashSet();
        for (LongIterator bi = batch.iterator(); bi.hasNext(); ) {
            long c = bi.nextLong();
            int cx = ChunkPos.getX(c);
            int cz = ChunkPos.getZ(c);
            for (int dx = -rc; dx <= rc; dx++) {
                for (int dz = -rc; dz <= rc; dz++) {
                    LongOpenHashSet s = this.sourcesInChunk.get(ChunkPos.asLong(cx + dx, cz + dz));
                    if (s != null) {
                        seeds.addAll(s);
                    }
                }
            }
        }

        LongOpenHashSet floodSeeds = clusterFloodSeeds(seeds);

        boolean changed = false;
        if (seeds.isEmpty()) {
            for (LongIterator bi = batch.iterator(); bi.hasNext(); ) {
                long c = bi.nextLong();
                changed |= this.litByChunk.remove(c) != null;
                changed |= this.profileByChunk.remove(c) != null;
            }
            if (changed) {
                publish();
                markChunksForClientRerender(batch);
            }
            return;
        }

        Long2ObjectOpenHashMap<Long2ByteOpenHashMap> rebuilt = new Long2ObjectOpenHashMap<>(batch.size());
        Long2ObjectOpenHashMap<Long2ByteOpenHashMap> rebuiltProfiles = new Long2ObjectOpenHashMap<>(batch.size());
        for (LongIterator bi = batch.iterator(); bi.hasNext(); ) {
            long chunkKey = bi.nextLong();
            Long2ByteOpenHashMap litMap = new Long2ByteOpenHashMap();
            litMap.defaultReturnValue((byte) 0);
            rebuilt.put(chunkKey, litMap);
            Long2ByteOpenHashMap profileMap = new Long2ByteOpenHashMap();
            profileMap.defaultReturnValue((byte) 0);
            rebuiltProfiles.put(chunkKey, profileMap);
        }

        long nodeBudget = Math.max(1000, SunwellConfig.nodeBudgetPerTick);
        if (this.level.isClientSide()) {
            nodeBudget = Math.max(500, nodeBudget / 4);
        }
        long nodes = flood(floodSeeds, rebuilt, rebuiltProfiles, nodeBudget * 4);

        if (nodes < 0) {
            this.dirtyChunks.addAll(batch);
            this.nextBatchSize = Math.max(1, batchSize / 2);
            return;
        }

        for (LongIterator bi = batch.iterator(); bi.hasNext(); ) {
            long c = bi.nextLong();
            Long2ByteOpenHashMap m = rebuilt.get(c);
            Long2ByteOpenHashMap profiles = rebuiltProfiles.get(c);
            if (m.isEmpty()) {
                changed |= this.litByChunk.remove(c) != null;
                changed |= this.profileByChunk.remove(c) != null;
            } else {
                this.litByChunk.put(c, m);
                this.profileByChunk.put(c, profiles);
                changed = true;
            }
        }
        if (changed) {
            publish();
            markChunksForClientRerender(batch);
        }

        if (nodes > nodeBudget && batchSize > 1) {
            this.nextBatchSize = Math.max(1, batchSize / 2);
        } else if (nodes < nodeBudget / 4 && batchSize == this.nextBatchSize) {
            this.nextBatchSize = Math.min(chunkBudget, this.nextBatchSize * 2);
        }
    }

    private void pruneDistantDirtyChunks(BlockPos playerPos) {
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;
        LongIterator it = this.dirtyChunks.iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            int cx = ChunkPos.getX(c);
            int cz = ChunkPos.getZ(c);
            if (Math.abs(cx - pcx) > CLIENT_RAIN_CHUNK_RADIUS
                    || Math.abs(cz - pcz) > CLIENT_RAIN_CHUNK_RADIUS) {
                it.remove();
            }
        }
    }

    private void clearClientRainCache() {
        if (!this.level.isClientSide || this.litByChunk.isEmpty()) {
            return;
        }
        // Re-render the sections that were lit so the virtual sky light visually clears when the
        // rain stops (they would otherwise keep their stale bright mesh until a block update).
        LongOpenHashSet wasLit = new LongOpenHashSet(this.litByChunk.keySet());
        this.litByChunk.clear();
        this.profileByChunk.clear();
        this.litSnapshot = EMPTY;
        this.profileSnapshot = EMPTY;
        this.dirtyChunks.clear();
        this.nextBatchSize = -1;
        refreshActive();
        markChunksForClientRerender(wasLit);
    }

    /**
     * Force the client to re-mesh the given chunk columns so a virtual-sky-light change (appearing
     * when rain starts / a lamp is placed, or clearing when a lamp is removed / rain stops) is
     * actually reflected in the render. Minecraft only re-queries light for sections it re-meshes,
     * and a lone block change near the source never covers the whole flooded region. Server no-op.
     */
    private void markChunksForClientRerender(LongOpenHashSet chunks) {
        if (!this.level.isClientSide || chunks.isEmpty()) {
            return;
        }
        net.minecraft.client.renderer.LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        int minSection = this.level.getMinSection();
        int maxSection = this.level.getMaxSection();
        for (long c : chunks) {
            int cx = ChunkPos.getX(c);
            int cz = ChunkPos.getZ(c);
            for (int sy = minSection; sy < maxSection; sy++) {
                renderer.setSectionDirty(cx, sy, cz);
            }
        }
    }

    private void publish() {
        this.litSnapshot = snapshotOf(this.litByChunk);
        this.profileSnapshot = snapshotOf(this.profileByChunk);
        refreshActive();
    }

    /**
     * Deep copy for publication to other threads.
     *
     * <p>{@code Long2ObjectOpenHashMap.clone()} is a <em>shallow</em> copy: it duplicates the outer
     * map but hands back the very same inner per-chunk maps the flood keeps mutating. Chunk meshing
     * runs on ForkJoinPool workers and reads these through {@code baseSkyAt}/{@code profileAt}, so a
     * shallow snapshot let a worker walk an inner map mid-rehash — the same race that crashed the
     * outdoor cache with an AIOOBE. The inner maps must be copied too, or "snapshot" means nothing.</p>
     */
    private static Long2ObjectOpenHashMap<Long2ByteOpenHashMap> snapshotOf(
            Long2ObjectOpenHashMap<Long2ByteOpenHashMap> source
    ) {
        if (source.isEmpty()) {
            return EMPTY;
        }
        Long2ObjectOpenHashMap<Long2ByteOpenHashMap> copy = new Long2ObjectOpenHashMap<>(source.size());
        for (Long2ObjectMap.Entry<Long2ByteOpenHashMap> e : source.long2ObjectEntrySet()) {
            copy.put(e.getLongKey(), e.getValue().clone());
        }
        return copy;
    }

    private static void writeLitCell(
            Long2ByteOpenHashMap litMap,
            Long2ByteOpenHashMap profileMap,
            long packedPos,
            int value,
            byte profile
    ) {
        int existing = litMap.get(packedPos) & 0xFF;
        byte existingProfile = profileMap.get(packedPos);
        byte mergedProfile;
        if (value > existing
                || (value == existing && SunwellProfile.sideEffectRank(profile) < SunwellProfile.sideEffectRank(existingProfile))) {
            litMap.put(packedPos, (byte) value);
            mergedProfile = profile;
        } else {
            mergedProfile = existingProfile;
        }
        // Rain-through is OR'd: one sunwell lantern in a static grow-light grid still opens weather.
        if (SunwellProfile.hasRainThrough(profile) || SunwellProfile.hasRainThrough(existingProfile)) {
            mergedProfile = (byte) (mergedProfile | SunwellProfile.RAIN_THROUGH);
        }
        profileMap.put(packedPos, mergedProfile);
    }

    // ------------------------------------------------------------------ flood fill

    /**
     * Neighboring lamps flood nearly the same region. Keep one representative per local
     * cluster so overlapping BFS work is not repeated (correctness unchanged — writeLitCell merges).
     */
    private static LongOpenHashSet clusterFloodSeeds(LongOpenHashSet seeds) {
        if (seeds.size() <= 1) {
            return seeds;
        }
        LongOpenHashSet representatives = new LongOpenHashSet(Math.max(4, seeds.size() / 3));
        outer:
        for (LongIterator it = seeds.iterator(); it.hasNext(); ) {
            long candidate = it.nextLong();
            int cx = BlockPos.getX(candidate);
            int cy = BlockPos.getY(candidate);
            int cz = BlockPos.getZ(candidate);
            for (LongIterator repIt = representatives.iterator(); repIt.hasNext(); ) {
                long rep = repIt.nextLong();
                int dx = cx - BlockPos.getX(rep);
                int dy = cy - BlockPos.getY(rep);
                int dz = cz - BlockPos.getZ(rep);
                if (dx * dx + dy * dy + dz * dz <= CLUSTER_MERGE_DISTANCE_SQ) {
                    continue outer;
                }
            }
            representatives.add(candidate);
        }
        return representatives;
    }

    /**
     * Multi-source 0-1 BFS. Each cell carries a packed budget (radius << 9 | depth):
     * sideways steps cost 1 radius, upward steps cost 2 radius, downward steps are free
     * but consume depth.
     *
     * @return nodes processed, or -1 if {@code hardCap} was exceeded (results incomplete).
     */
    private long flood(
            LongOpenHashSet seeds,
            Long2ObjectOpenHashMap<Long2ByteOpenHashMap> litOut,
            Long2ObjectOpenHashMap<Long2ByteOpenHashMap> profileOut,
            long hardCap
    ) {
        final int maxR = SunwellConfig.maxRadius;
        final int maxD = SunwellConfig.maxDepth;
        final int base = SunwellConfig.skyLevel;
        final boolean atten = SunwellConfig.attenuateByDepth;
        final int minY = this.level.getMinBuildHeight();
        final int maxY = this.level.getMaxBuildHeight();
        // Cone: the apex sits at the lamp and every block of descent grants coneSpread more horizontal
        // radius, so the higher a ceiling lamp hangs the wider the pool it lights below — a "small sun".
        // The cone never exceeds maxRadius, which is what bounds the flood volume.
        // coneSpread 0 = the classic straight cylinder (full radius at every level).
        final int coneSpread = Math.max(0, SunwellConfig.coneSpread);
        final int coneCapR = maxR;
        final boolean clientSide = this.level.isClientSide;
        final int edgeFadeReach = 4;   // client-only soft edge: taper the outermost N blocks of radius,
        final int edgeFadeStep = 3;    // dropping this much light per block so the pool fades to dark.

        Long2IntOpenHashMap best = new Long2IntOpenHashMap(Math.min(seeds.size() * 64, 1 << 16));
        best.defaultReturnValue(-1);
        LongArrayFIFOQueue posQueue = new LongArrayFIFOQueue();
        LongArrayFIFOQueue budgetQueue = new LongArrayFIFOQueue();
        LongArrayFIFOQueue profileQueue = new LongArrayFIFOQueue();
        // Each path remembers the drop its seed has to open out over, so the cone can be stretched
        // per-lamp. Carried alongside rather than packed into `budget`: that value is compared for
        // BFS dominance, and mixing an unrelated number into it would corrupt the comparison.
        LongArrayFIFOQueue heightQueue = new LongArrayFIFOQueue();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        ChunkCache cache = new ChunkCache();

        for (LongIterator si = seeds.iterator(); si.hasNext(); ) {
            long s = si.nextLong();
            int sx = BlockPos.getX(s);
            int sy = BlockPos.getY(s);
            int sz = BlockPos.getZ(s);
            byte seedProfile = this.sourceProfileByPos.getOrDefault(s, (byte) 0);
            // How far this lamp has to open out over. Past maxRadius the cone is stretched to still
            // land on maxRadius; shorter drops stay 1:1 and just light a smaller pool.
            int seedEff = coneSpread == 0 ? maxR
                    : Math.max(coneDropHeight(this.level, cursor.set(sx, sy, sz).immutable(), maxD), maxR);
            // Seed radius: 0 in cone mode so the cone starts UNDER the lamp rather than as a disc
            // around it. The first step down gets whatever the stretched schedule grants.
            int seedSide = coneSpread == 0 ? maxR - 1 : 0;
            int seedDown = coneSpread == 0 ? maxR : coneGrant(0, maxR, seedEff);
            tryVisit(sx, sy - 1, sz, seedDown, maxD - 1, true, seedProfile, seedEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(sx + 1, sy, sz, seedSide, maxD, false, seedProfile, seedEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(sx - 1, sy, sz, seedSide, maxD, false, seedProfile, seedEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(sx, sy, sz + 1, seedSide, maxD, false, seedProfile, seedEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(sx, sy, sz - 1, seedSide, maxD, false, seedProfile, seedEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(sx, sy + 1, sz, coneSpread == 0 ? maxR - 2 : 0, maxD, false, seedProfile, seedEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
        }

        long nodes = 0;
        while (!posQueue.isEmpty()) {
            if (++nodes > hardCap) {
                return -1;
            }
            long p = posQueue.dequeueLong();
            int budget = (int) budgetQueue.dequeueLong();
            byte pathProfile = (byte) profileQueue.dequeueLong();
            int pathEff = (int) heightQueue.dequeueLong();
            if (budget < best.get(p)) {
                continue;
            }
            int r = budget >>> 9;
            int d = budget & 511;
            int x = BlockPos.getX(p);
            int y = BlockPos.getY(p);
            int z = BlockPos.getZ(p);

            long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
            Long2ByteOpenHashMap litMap = litOut.get(chunkKey);
            Long2ByteOpenHashMap profileMap = profileOut.get(chunkKey);
            if (litMap != null && profileMap != null) {
                int value = atten ? Math.max(1, base - (maxR - Math.min(r, maxR)) / 2) : base;
                // Client-only soft edge: taper the outermost few blocks of radius so the lit pool blends
                // into darkness instead of hard-cutting. The server keeps the flat value, so grow light
                // (>=12) still reaches the full radius -- only the LOOK fades, never the farming.
                if (clientSide && r < edgeFadeReach) {
                    value = Math.max(1, value - (edgeFadeReach - r) * edgeFadeStep);
                }
                writeLitCell(litMap, profileMap, p, value, pathProfile);
            }

            // depth below the source = maxD - d; grant whatever the stretched cone owes this step.
            int nextDown = coneSpread == 0 ? maxR : Math.min(coneCapR, r + coneGrant(maxD - d, maxR, pathEff));
            tryVisit(x, y - 1, z, nextDown, d - 1, true, pathProfile, pathEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(x + 1, y, z, r - 1, d, false, pathProfile, pathEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(x - 1, y, z, r - 1, d, false, pathProfile, pathEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(x, y, z + 1, r - 1, d, false, pathProfile, pathEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(x, y, z - 1, r - 1, d, false, pathProfile, pathEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
            tryVisit(x, y + 1, z, r - 2, d, false, pathProfile, pathEff, best, posQueue, budgetQueue, profileQueue, heightQueue, cursor, cache, minY, maxY);
        }
        return nodes;
    }

    private void tryVisit(
            int x, int y, int z,
            int r, int d,
            boolean downward,
            byte profile,
            int effectiveHeight,
            Long2IntOpenHashMap best,
            LongArrayFIFOQueue posQueue,
            LongArrayFIFOQueue budgetQueue,
            LongArrayFIFOQueue profileQueue,
            LongArrayFIFOQueue heightQueue,
            BlockPos.MutableBlockPos cursor,
            ChunkCache cache,
            int minY,
            int maxY
    ) {
        if (r < 0 || d < 0 || y < minY || y >= maxY) {
            return;
        }
        long p = BlockPos.asLong(x, y, z);
        int packed = (r << 9) | d;
        if (packed <= best.get(p)) {
            return;
        }
        LevelChunk chunk = cache.get(x >> 4, z >> 4);
        if (chunk == null) {
            return;
        }
        cursor.set(x, y, z);
        BlockState state = chunk.getBlockState(cursor);
        best.put(p, packed);
        if (state.getLightBlock(chunk, cursor) >= 15) {
            return;
        }
        if (downward) {
            posQueue.enqueueFirst(p);
            budgetQueue.enqueueFirst(packed);
            profileQueue.enqueueFirst((long) profile);
            heightQueue.enqueueFirst((long) effectiveHeight);
        } else {
            posQueue.enqueue(p);
            budgetQueue.enqueue(packed);
            profileQueue.enqueue((long) profile);
            heightQueue.enqueue((long) effectiveHeight);
        }
    }

    /** Memoizing loaded-chunk lookup for the flood. */
    private final class ChunkCache {
        private static final Object MISSING = new Object();
        private final Long2ObjectOpenHashMap<Object> map = new Long2ObjectOpenHashMap<>();
        private long lastKey = Long.MIN_VALUE;
        private LevelChunk lastChunk;

        LevelChunk get(int cx, int cz) {
            long key = ChunkPos.asLong(cx, cz);
            if (key == this.lastKey) {
                return this.lastChunk;
            }
            Object o = this.map.get(key);
            if (o == null) {
                LevelChunk chunk = SunwellManager.this.level.getChunkSource().getChunkNow(cx, cz);
                o = chunk == null ? MISSING : chunk;
                this.map.put(key, o);
            }
            this.lastKey = key;
            this.lastChunk = o == MISSING ? null : (LevelChunk) o;
            return this.lastChunk;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int radiusChunks() {
        return (SunwellConfig.maxRadius >> 4) + 1;
    }

    private static long chunkOf(long packedPos) {
        return ChunkPos.asLong(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
    }
}
