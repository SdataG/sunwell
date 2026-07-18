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
        // SOURCE_HOLDER counts as relevant: a wall lantern carries no source tag itself, so keying
        // purely on SUNWELL_SOURCE meant mounting a lantern on a wall never notified the manager and
        // the sunwell silently vanished. onSourceChanged resolves what it holds and decides there.
        boolean wasSource = old.is(Sunwell.SUNWELL_SOURCE) || old.is(Sunwell.SOURCE_HOLDER);
        boolean isSource = state.is(Sunwell.SUNWELL_SOURCE) || state.is(Sunwell.SOURCE_HOLDER);
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
