package com.SdataG.sunwell.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * Additive glow render type that depth-<em>tests</em> (so a wall in front still occludes it) but does
 * NOT depth-<em>write</em>. Writing depth is what made the old {@link RenderType#lightning()} halo
 * z-fight/"tear" against the orb and cage geometry. No cull so the billboarded disc shows from both
 * sides. POSITION_COLOR additive (SRC_ALPHA, ONE) via the stock lightning shader.
 */
public final class GlowRenderType extends RenderType {

    private GlowRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                           boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static final RenderType ORB_GLOW = create(
            "sunwell_orb_glow",
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
