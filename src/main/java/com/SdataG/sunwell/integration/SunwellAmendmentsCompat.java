package com.SdataG.sunwell.integration;

import com.SdataG.sunwell.Sunwell;
import net.minecraftforge.fml.ModList;

/**
 * Tracks whether Amendments is present. That is now all this does.
 *
 * <p>It used to reflect into Moonlight's {@code AdditionalItemPlacementsAPI} to register a wall
 * placement for our lantern by hand. That was the wrong shape entirely: Amendments already walks
 * every item in the registry and registers the placement for anything
 * {@code WallLanternBlock.isValidBlock} accepts. It was rejecting us for one reason — our lantern has
 * a block entity — and that same check also feeds {@code BlockScanner}, which builds the set of
 * lanterns the client generates wall models for. So hand-registering a placement could never have
 * worked: the placement would exist with no model behind it.</p>
 *
 * <p>{@link com.SdataG.sunwell.mixin.amendments.WallLanternBlockMixin} makes us pass that check
 * instead, and Amendments does the rest natively. Three reflection bugs' worth of bridge code deleted
 * with it.</p>
 */
public final class SunwellAmendmentsCompat {

    private static volatile boolean amendmentsPresent;

    private SunwellAmendmentsCompat() {
    }

    public static void init() {
        amendmentsPresent = ModList.get().isLoaded("amendments") && ModList.get().isLoaded("moonlight");
    }

    public static boolean isActive() {
        return amendmentsPresent;
    }

}
