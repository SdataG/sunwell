package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.block.entity.SunwellLanternBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Sunwell.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SunwellLanternBlockEntity>> SUNWELL_LANTERN_BE =
            BLOCK_ENTITY_TYPES.register("sunwell_lantern",
                    () -> BlockEntityType.Builder.of(
                                    SunwellLanternBlockEntity::new,
                                    ModBlocks.SUNWELL_LANTERN.get())
                            .build(null));

    private ModBlockEntities() {
    }
}
