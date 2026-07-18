package com.SdataG.sunwell.integration;

import com.SdataG.sunwell.Sunwell;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Resolves the block held inside a {@link Sunwell#SOURCE_HOLDER} block.
 *
 * <p>Mounting a Sunwell Lantern on a wall replaces it with {@code amendments:wall_lantern}, which
 * merely <em>holds</em> our lantern in its block entity. On 1.20 that block is shared by every lantern
 * in the game, so it cannot simply be tagged as a source — that would turn vanilla and soul lanterns
 * into sunwells. It is a source only when what it holds is one.</p>
 *
 * <p>Reads through Moonlight's {@code IBlockHolder}, so this works for any holder block a pack tags,
 * not only Amendments' wall lanterns.</p>
 */
public final class SunwellHeldSource {

    private SunwellHeldSource() {
    }

    /**
     * The block a holder is holding, or {@code null} if this isn't one / Amendments isn't present.
     * Never throws: an absent Moonlight must not break the chunk scan.
     */
    public static BlockState heldBlock(BlockGetter level, BlockPos pos) {
        if (!SunwellAmendmentsCompat.isActive()) {
            return null;
        }
        try {
            return Hooks.heldBlock(level, pos);
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    /** The only place naming a Moonlight type; loaded only once the guard above passes. */
    private static final class Hooks {

        private Hooks() {
        }

        static BlockState heldBlock(BlockGetter level, BlockPos pos) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof net.mehvahdjukaar.moonlight.api.block.IBlockHolder holder) {
                return holder.getHeldBlock();
            }
            return null;
        }
    }
}
