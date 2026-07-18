package com.SdataG.sunwell.client.render;

import java.util.Map;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.client.render.LanternOrbEffects.SkyOrb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Bake-time cache for the standalone orb models registered in
 * {@link com.SdataG.sunwell.client.SunwellClientEvents}. One orb model per sky state.
 *
 * <p>Art slots (each has a {@code _floor} twin for the non-hanging model):
 * {@code sun_orb}, {@code moon_orb}, {@code cloudy_orb}, {@code storm_orb}. Any slot that has no
 * model yet falls back to the sun orb, which the renderer then tints — so new art can be dropped in
 * without touching code.</p>
 */
public final class SunwellOrbModels {

    /** Model path per sky state (hanging, floor). */
    private static String[] paths(SkyOrb orb) {
        return switch (orb) {
            case MOON -> new String[]{"moon_orb", "moon_orb_floor"};
            case CLOUDY -> new String[]{"cloudy_orb", "cloudy_orb_floor"};
            case STORM -> new String[]{"storm_orb", "storm_orb_floor"};
            case SUN -> new String[]{"sun_orb", "sun_orb_floor"};
        };
    }

    /** Every orb model path the client should load. */
    public static String[] allPaths() {
        return new String[]{
                "sun_orb", "sun_orb_floor",
                "moon_orb", "moon_orb_floor",
                "cloudy_orb", "cloudy_orb_floor",
                "storm_orb", "storm_orb_floor",
        };
    }

    private static final Map<String, BakedModel> BAKED = new java.util.HashMap<>();

    /** Cached vertical centre (block space) per baked orb model — see {@link #centerY}. */
    private static final Map<BakedModel, Float> CENTER_Y = new java.util.IdentityHashMap<>();

    private SunwellOrbModels() {
    }

    /**
     * Vertical centre of an orb model in block space (0–1), read from the baked geometry itself.
     *
     * <p>The glow halo and shafts pivot on this, so re-modelling or moving the orb re-centres the VFX
     * automatically — no hardcoded bounds to keep in sync and nothing to regenerate. Falls back to
     * {@code fallback} for a model with no quads.</p>
     */
    public static float centerY(BakedModel model, float fallback) {
        Float cached = CENTER_Y.get(model);
        if (cached != null) {
            return cached;
        }
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
        java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                new java.util.ArrayList<>(model.getQuads(null, null, random));
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            quads.addAll(model.getQuads(null, side, random));
        }

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) {
                // Baked quad vertices are already in 0–1 block space; Y sits at offset 1.
                float y = Float.intBitsToFloat(vertices[i * stride + 1]);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        float center = minY > maxY ? fallback : (minY + maxY) * 0.5F;
        CENTER_Y.put(model, center);
        return center;
    }

    public static void onBake(Map<ResourceLocation, BakedModel> models) {
        BAKED.clear();
        CENTER_Y.clear();
        for (String path : allPaths()) {
            BakedModel baked = models.get(key(path));
            if (baked != null) {
                BAKED.put(path, baked);
            }
        }
    }

    /** Orb model for the given sky, falling back to the sun orb when that art does not exist yet. */
    public static BakedModel resolveOrb(SkyOrb orb, boolean hanging) {
        BakedModel model = lookup(paths(orb)[hanging ? 0 : 1]);
        if (isRenderable(model)) {
            return model;
        }
        return lookup(paths(SkyOrb.SUN)[hanging ? 0 : 1]);
    }

    private static BakedModel lookup(String path) {
        BakedModel cached = BAKED.get(path);
        if (isRenderable(cached)) {
            return cached;
        }
        BakedModel runtime = Minecraft.getInstance().getModelManager().getModel(key(path));
        return isRenderable(runtime) ? runtime : null;
    }

    public static boolean isRenderable(BakedModel model) {
        if (model == null) {
            return false;
        }
        return model != Minecraft.getInstance().getModelManager().getMissingModel();
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath(Sunwell.MOD_ID, "block/" + path);
    }
}
