package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, Sunwell.MOD_ID);

    /**
     * Sunwell's own falling rain for the lantern's cone. Deliberately not tied to any weather mod:
     * it looks the same whether or not one is installed, and can't break when they update.
     * {@code true} = ignore the particle limiter, so a busy cone still renders.
     */
    public static final RegistryObject<SimpleParticleType> SKY_RAIN =
            PARTICLE_TYPES.register("sky_rain", () -> new SimpleParticleType(false));

    private ModParticles() {
    }
}
