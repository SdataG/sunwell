package com.SdataG.sunwell.registry;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.block.entity.SunwellLanternBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Sunwell.MOD_ID);

    public static final RegistryObject<BlockEntityType<SunwellLanternBlockEntity>> SUNWELL_LANTERN_BE =
            BLOCK_ENTITY_TYPES.register("sunwell_lantern",
                    () -> BlockEntityType.Builder.of(
                                    SunwellLanternBlockEntity::new,
                                    ModBlocks.SUNWELL_LANTERN.get())
                            .build(null));

    private ModBlockEntities() {
    }
}
