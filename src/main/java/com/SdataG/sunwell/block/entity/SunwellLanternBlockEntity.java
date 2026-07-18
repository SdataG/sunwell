package com.SdataG.sunwell.block.entity;

import com.SdataG.sunwell.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class SunwellLanternBlockEntity extends BlockEntity {

    public SunwellLanternBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUNWELL_LANTERN_BE.get(), pos, state);
    }
}
