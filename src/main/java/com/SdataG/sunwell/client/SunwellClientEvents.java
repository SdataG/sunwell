package com.SdataG.sunwell.client;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.client.particle.SkyRainParticle;
import com.SdataG.sunwell.client.render.SunwellOrbModels;
import com.SdataG.sunwell.registry.ModParticles;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Sunwell.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SunwellClientEvents {

    private SunwellClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (String path : SunwellOrbModels.allPaths()) {
            event.register(ResourceLocation.fromNamespaceAndPath(Sunwell.MOD_ID, "block/" + path));
        }
    }

    @SubscribeEvent
    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.SKY_RAIN.get(), SkyRainParticle.Provider::new);
    }

    @SubscribeEvent
    public static void onModelBake(ModelEvent.BakingCompleted event) {
        SunwellOrbModels.onBake(event.getModels());
    }
}
