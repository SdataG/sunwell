package com.SdataG.sunwell.client.render;

import com.SdataG.sunwell.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;

/**
 * Renders the animated orb on top of the cage item model in first/third person and on dropped items.
 * GUI/hotbar uses a static composite item model with the orb baked in.
 */
public class SunwellLanternItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static SunwellLanternItemRenderer instance;

    public SunwellLanternItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
        instance = this;
    }

    public static void init(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        if (instance == null) {
            new SunwellLanternItemRenderer(dispatcher, modelSet);
        }
    }

    public static SunwellLanternItemRenderer getInstance() {
        return instance;
    }

    @Override
    public void renderByItem(
            ItemStack stack,
            ItemDisplayContext displayContext,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int combinedLight,
            int combinedOverlay
    ) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        BlockState state = ModBlocks.SUNWELL_LANTERN.get().defaultBlockState();

        // A FIXED reference position keeps the orb's animation phase stable. Using the player's own
        // block position made every effect (ray rotation, flux, bob) jump block-to-block as they walked
        // -- the "stutter". The sky the orb shows (day/night/weather) is global, so a constant pos is fine.
        BlockPos effectPos = BlockPos.ZERO;

        float partialTick = Minecraft.getInstance().getFrameTime();

        // Only FIRST person is drawn in view space -- there the halo/shafts must be view-locked so they
        // hold still and turn with you. Third-person held items, dropped items and item frames are world
        // objects, so they keep the camera billboard like a placed lantern.
        boolean viewSpace = displayContext.firstPerson();

        LanternOrbPresenter.render(
                level,
                effectPos,
                poseStack,
                bufferSource,
                state,
                false,
                partialTick,
                combinedOverlay,
                viewSpace
        );
    }
}
