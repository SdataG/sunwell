package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.block.SunwellLanternBlock;
import com.SdataG.sunwell.item.SunwellLanternItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, Sunwell.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, Sunwell.MOD_ID);

    /**
     * The single universal lamp: its orb presents whatever sky it stands in for.
     *
     * <p>Block light is deliberately low. This is a <em>skylight</em>, not a torch — the room is lit
     * by the virtual sky light it projects, which already scales with day/night and weather, so the
     * light it radiates genuinely dims in a storm and drops further at night. A constant block light
     * 15 simply drowned all of that out and kept every room at full brightness. This small value is
     * just the lantern's own presence so it isn't a black hole in a dark room.</p>
     *
     * <p>Only affects this lantern (a full dynamic_exposure profile). Pack grow-lamps tagged
     * {@code sunwell_source} only stay pinned at full strength and are unaffected.</p>
     */
    public static final DeferredHolder<Block, Block> SUNWELL_LANTERN = BLOCKS.register("sunwell_lantern",
            () -> new SunwellLanternBlock(lanternProperties(state -> 15)));

    public static final DeferredHolder<Item, Item> SUNWELL_LANTERN_ITEM = ITEMS.register("sunwell_lantern",
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
