package com.SdataG.sunwell.mixin.amendments;

import com.SdataG.sunwell.registry.ModBlocks;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Amendments recognise the Sunwell Lantern as a lantern.
 *
 * <p>Amendments rejects it, and for one specific reason. {@code WallLanternBlock.isValidBlock} ends:</p>
 *
 * <pre>
 * if (block instanceof LanternBlock) {
 *     if (block.defaultBlockState().hasBlockEntity()) {
 *         return SUPPSQUARED &amp;&amp; SuppSquaredCompat.isLightableLantern(block);   // -&gt; false for us
 *     }
 *     return true;
 * }
 * return false;
 * </pre>
 *
 * <p><b>Any {@code LanternBlock} with a block entity is excluded.</b> Sunwell's lantern has one — it
 * drives the orb — so we fail the check, and that single boolean locks us out of the entire feature.
 * {@code isValidBlock} is not just the placement gate; {@code BlockScanner} uses it to build the set
 * of lanterns the client generates wall-lantern models for. Registering a placement ourselves through
 * {@code AdditionalItemPlacementsAPI} could never have been enough: the placement would exist, but
 * the scanner still wouldn't know the block, so there'd be no wall model to render.</p>
 *
 * <p>Passing the check instead means Amendments does everything itself, natively and in its own
 * order — placement, models, textures, the scanner — exactly as it does for a vanilla lantern. That
 * is why this replaced the reflective registration rather than joining it.</p>
 *
 * <p><b>Common, not client.</b> {@code isValidBlock} is called by {@code ModRegistry} when it builds
 * the placement registrations — which runs on both sides — as well as by the client-only
 * {@code BlockScanner}. A client-only mixin would leave a dedicated server never registering the
 * placement at all, so wall lanterns would work in single player and silently fail online.</p>
 *
 * <p>The supported alternative is Amendments' own {@code id_whitelist} config ("Ids of blocks that
 * are not detected as lanterns but should be"), which short-circuits this same method to true. This
 * mixin does the same thing without asking every pack to edit another mod's config.</p>
 */
@Mixin(value = net.mehvahdjukaar.amendments.common.block.WallLanternBlock.class, remap = false)
public final class WallLanternBlockMixin {

    private WallLanternBlockMixin() {
    }

    @Inject(method = "isValidBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sunwell$acceptSunwellLantern(Block block, CallbackInfoReturnable<Boolean> callback) {
        if (block == ModBlocks.SUNWELL_LANTERN.get()) {
            callback.setReturnValue(true);
        }
    }
}
