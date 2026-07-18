package com.SdataG.sunwell.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Sunwell's own falling rain, used for the lantern's light cone.
 *
 * <p>The streak shape lives in the 8x8 sprite (a soft vertical column) rather than in geometry — a
 * particle quad is square, so a streaked texture is what reads as rain. Falls at a near-constant
 * speed (real rain is at terminal velocity, not accelerating), water-tinted, and pops on impact.</p>
 */
public class SkyRainParticle extends TextureSheetParticle {

    private SkyRainParticle(ClientLevel level, double x, double y, double z,
                            double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.pickSprite(sprites);

        // An upward launch means the caller wants a sprinkler arc (floor lantern); anything else
        // falls straight. Arcs need real gravity to come back down; falling rain wants almost none,
        // since real rain is at terminal velocity rather than accelerating.
        boolean arc = yd > 0.0D;
        if (xd == 0.0D && yd == 0.0D && zd == 0.0D) {
            this.xd = 0.0D;
            this.yd = -0.45D - this.random.nextDouble() * 0.15D;
            this.zd = 0.0D;
        } else {
            this.xd = xd;
            this.yd = yd;
            this.zd = zd;
        }
        this.gravity = arc ? 0.16F : 0.06F;

        this.lifetime = (arc ? 55 : 40) + this.random.nextInt(20);
        this.quadSize = 0.07F + this.random.nextFloat() * 0.03F;
        this.setSize(0.01F, 0.01F);

        // Cool water tint; slight per-drop variance so a shaft doesn't look like a solid sheet.
        float shade = 0.85F + this.random.nextFloat() * 0.15F;
        this.rCol = 0.58F * shade;
        this.gCol = 0.72F * shade;
        this.bCol = 0.96F * shade;
        this.alpha = 0.80F;
        this.hasPhysics = true;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.removed) {
            return;
        }
        // Splash and die on contact instead of sliding along the floor.
        if (this.onGround) {
            if (this.random.nextInt(8) == 0) {
                this.level.playLocalSound(this.x, this.y, this.z, SoundEvents.GENERIC_SMALL_FALL,
                        SoundSource.WEATHER, 0.05F, 0.9F + this.random.nextFloat() * 0.2F, false);
            }
            this.remove();
        }
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new SkyRainParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
