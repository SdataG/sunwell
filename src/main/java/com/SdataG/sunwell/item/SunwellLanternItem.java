package com.SdataG.sunwell.item;

import com.SdataG.sunwell.client.render.SunwellLanternItemRenderer;
import com.SdataG.sunwell.integration.SunwellAmendmentsCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

public class SunwellLanternItem extends BlockItem {

    public SunwellLanternItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        if (SunwellAmendmentsCompat.isActive()) {
            return;
        }
        consumer.accept(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                SunwellLanternItemRenderer renderer = SunwellLanternItemRenderer.getInstance();
                if (renderer == null) {
                    SunwellLanternItemRenderer.init(
                            Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                            Minecraft.getInstance().getEntityModels()
                    );
                    renderer = SunwellLanternItemRenderer.getInstance();
                }
                return renderer;
            }
        });
    }
}
