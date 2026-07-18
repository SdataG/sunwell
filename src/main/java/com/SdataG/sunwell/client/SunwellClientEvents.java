package com.SdataG.sunwell.client;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.client.particle.SkyRainParticle;
import com.SdataG.sunwell.client.render.SunwellOrbModels;
import com.SdataG.sunwell.registry.ModParticles;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid = Sunwell.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SunwellClientEvents {

    private SunwellClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (String path : SunwellOrbModels.allPaths()) {
            event.register(ModelResourceLocation.standalone(
                    ResourceLocation.fromNamespaceAndPath(Sunwell.MOD_ID, "block/" + path)));
        }
    }

    @SubscribeEvent
    public static void onModelBake(ModelEvent.BakingCompleted event) {
        SunwellOrbModels.onBake(event.getModels());
    }

    @SubscribeEvent
    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.SKY_RAIN.get(), SkyRainParticle.Provider::new);
    }
}
