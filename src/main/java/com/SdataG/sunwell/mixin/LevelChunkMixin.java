package com.SdataG.sunwell.mixin;

import com.SdataG.sunwell.Sunwell;
import com.SdataG.sunwell.SunwellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void sunwell$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                                  CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        SunwellManager manager = SunwellManager.get(level);
        if (manager == null) {
            return;
        }
        BlockState old = cir.getReturnValue();
        if (old == null) {
            return;
        }
        boolean wasSource = old.is(Sunwell.SUNWELL_SOURCE);
        boolean isSource = state.is(Sunwell.SUNWELL_SOURCE);
        if (wasSource != isSource) {
            manager.onSourceChanged(pos, state, isSource);
        } else if (isSource) {
            manager.onSourceChanged(pos, state, true);
        } else if (manager.hasSources()
                && old.getLightBlock(self, pos) != state.getLightBlock(self, pos)) {
            manager.onOpacityChanged(pos);
        }
    }
}
