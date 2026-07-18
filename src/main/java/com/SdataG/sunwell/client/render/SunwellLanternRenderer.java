package com.SdataG.sunwell.client.render;

import com.SdataG.sunwell.block.entity.SunwellLanternBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

public class SunwellLanternRenderer implements BlockEntityRenderer<SunwellLanternBlockEntity> {

    public SunwellLanternRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            SunwellLanternBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int combinedLight,
            int combinedOverlay
    ) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }

        BlockState state = blockEntity.getBlockState();
        BlockPos pos = blockEntity.getBlockPos();
        boolean hanging = state.getValue(LanternBlock.HANGING);

        LanternOrbPresenter.render(
                level,
                pos,
                poseStack,
                bufferSource,
                state,
                hanging,
                partialTick,
                combinedOverlay,
                false
        );
    }
}
