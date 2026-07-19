package com.SdataG.sunwell.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * Additive glow render type that depth-<em>tests</em> (so a wall in front still occludes it) but does
 * NOT depth-<em>write</em>. Writing depth is what made the old {@link RenderType#lightning()} halo
 * z-fight/"tear" against the orb and cage geometry. No cull so the billboarded disc shows from both
 * sides. POSITION_COLOR additive (SRC_ALPHA, ONE) via the stock lightning shader.
 *
 * <p><b>Named {@code "lightning"}, not a custom name.</b> Iris classifies a render type by matching its
 * declared NAME against vanilla's own names to decide which shader program to route it through. A custom
 * name like the old {@code "sunwell_orb_glow"} matches nothing Iris knows, so it likely fell back to a
 * generic program with different low-alpha/exposure handling than vanilla's direct blit -- which is
 * exactly why the dim fade tail (the lowest-alpha part of the whole VFX) was invisible under a shaderpack
 * while rendering fine without one: below whatever cutoff that fallback program applied, it just wasn't
 * drawn at all, rather than fading. Reusing vanilla's own {@code "lightning"} name routes us through the
 * same program real lightning already uses there, which is known to work under shaders.</p>
 */
public final class GlowRenderType extends RenderType {

    private GlowRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                           boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static final RenderType ORB_GLOW = create(
            "lightning",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));
}
