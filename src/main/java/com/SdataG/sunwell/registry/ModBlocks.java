package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.block.SunwellLanternBlock;
import com.SdataG.sunwell.item.SunwellLanternItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Sunwell.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Sunwell.MOD_ID);

    /**
     * Universal lamp. Orb mirrors the sky; room light comes from the projected sunwell.
     * Block light stays low (8) so day/night and weather dimming remain visible.
     * Pack blocks tagged {@code sunwell_source} only are unaffected.
     */
    public static final RegistryObject<Block> SUNWELL_LANTERN = BLOCKS.register("sunwell_lantern",
            () -> new SunwellLanternBlock(lanternProperties(state -> 8)));

    public static final RegistryObject<Item> SUNWELL_LANTERN_ITEM = ITEMS.register("sunwell_lantern",
            () -> new SunwellLanternItem(SUNWELL_LANTERN.get(), new Item.Properties()));

    private static BlockBehaviour.Properties lanternProperties(java.util.function.ToIntFunction<net.minecraft.world.level.block.state.BlockState> light) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.GOLD)
                .strength(3.5F)
                .sound(SoundType.LANTERN)
                .lightLevel(light)
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY);
    }

    private ModBlocks() {
    }
}
