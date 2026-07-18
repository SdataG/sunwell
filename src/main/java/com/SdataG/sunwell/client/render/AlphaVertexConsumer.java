package com.SdataG.sunwell.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

/**
 * Wraps a {@link VertexConsumer} and scales every vertex's alpha by a constant.
 *
 * <p>{@code ModelBlockRenderer.renderModel} accepts red/green/blue but <em>no alpha</em>, so a baked
 * model cannot be faded through that call. Everything it emits funnels through
 * {@link VertexConsumer#color(int, int, int, int)}, though — so intercepting that is enough to
 * dissolve a whole model. This is what lets one orb cross-fade into the next instead of popping.</p>
 *
 * <p>Each builder method returns {@code this} so the renderer's call chain keeps running through the
 * wrapper rather than escaping to the underlying buffer after the first call.</p>
 */
public final class AlphaVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final float alpha;

    public AlphaVertexConsumer(VertexConsumer delegate, float alpha) {
        this.delegate = delegate;
        this.alpha = Mth.clamp(alpha, 0.0F, 1.0F);
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int a) {
        this.delegate.color(red, green, blue, Mth.clamp((int) (a * this.alpha), 0, 255));
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        this.delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        this.delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        this.delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        this.delegate.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int a) {
        this.delegate.defaultColor(red, green, blue, Mth.clamp((int) (a * this.alpha), 0, 255));
    }

    @Override
    public void unsetDefaultColor() {
        this.delegate.unsetDefaultColor();
    }
}
