package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Puts the Sunwell Lantern in the vanilla Functional Blocks tab, immediately after the vanilla
 * Lantern, rather than giving one block its own creative tab.
 *
 * <p>A whole tab for a single item is noise: players look for a lantern where lanterns live. Sitting
 * next to {@code minecraft:lantern} also makes the comparison the mod is built around — sky light vs
 * block light — the first thing you see.</p>
 */
@EventBusSubscriber(modid = Sunwell.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            return;
        }
        // insertAfter rather than accept(): accept() appends to the end of the tab, which would bury
        // it under every banner and candle. This lands it directly beside the block it is a variant of.
        // (Forge spells this event.getEntries().putAfter(...); NeoForge hoisted it onto the event.)
        event.insertAfter(
                new ItemStack(Items.LANTERN),
                new ItemStack(ModBlocks.SUNWELL_LANTERN_ITEM.get()),
                net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }
}
