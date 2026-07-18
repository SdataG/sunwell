package com.SdataG.sunwell.client;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.integration.SunwellAmendmentsClientCompat;
import com.SdataG.sunwell.client.render.SunwellLanternItemRenderer;
import com.SdataG.sunwell.client.render.SunwellLanternRenderer;
import com.SdataG.sunwell.registry.ModBlockEntities;
import com.SdataG.sunwell.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = com.SdataG.sunwell.Sunwell.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SunwellClientSetup {

    private SunwellClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.SUNWELL_LANTERN.get(), RenderType.translucent());
            BlockEntityRenderers.register(ModBlockEntities.SUNWELL_LANTERN_BE.get(), SunwellLanternRenderer::new);
            SunwellLanternItemRenderer.init(
                    Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                    Minecraft.getInstance().getEntityModels()
            );

            // Belt and braces: SunwellAmendmentsClientCompat guards and catches internally, but a
            // link failure can surface at the call site rather than inside the callee, which no guard
            // within it could catch. Sunwell works fine with no Amendments, so nothing this optional
            // is allowed to abort client setup.
            try {
                SunwellAmendmentsClientCompat.init();
            } catch (RuntimeException | LinkageError error) {
                Sunwell.LOGGER.warn("[sunwell] Amendments client compat skipped (fail-open): {}", error.toString());
            }
        });
    }
}
