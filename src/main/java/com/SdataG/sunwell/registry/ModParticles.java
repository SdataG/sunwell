package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, Sunwell.MOD_ID);

    /**
     * Sunwell's own falling rain for the lantern's cone. Deliberately not tied to any weather mod:
     * it looks the same whether or not one is installed, and can't break when they update.
     * {@code true} = ignore the particle limiter, so a busy cone still renders.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SKY_RAIN =
            PARTICLE_TYPES.register("sky_rain", () -> new SimpleParticleType(false));

    private ModParticles() {
    }
}
