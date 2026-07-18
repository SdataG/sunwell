package com.SdataG.sunwell.integration;

import com.SdataG.sunwell.Sunwell;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Registers Sunwell lantern items with Amendments' hold animation and wall-lantern rendering.
 *
 * <p>Amendments is entirely optional, and the rule that keeps it optional is:
 * <strong>this class must not name a single Amendments or Moonlight type.</strong></p>
 *
 * <p>Java resolves the types a class refers to when it <em>links</em> the class, not when a branch
 * happens to run. So a guard like {@code if (!isLoaded) return;} does not protect a method whose own
 * signature mentions a missing class — the JVM can throw {@link NoClassDefFoundError} at the
 * <em>call site</em>, before the guard ever executes, where no try/catch inside this class can see
 * it. And because that is an {@link Error} rather than an Exception, it also slips past
 * {@code catch (Exception)} and fails the whole mod with "Sunwell has failed to load correctly".</p>
 *
 * <p>So every Amendments-typed reference lives in {@link Hooks} — a separate class file, only ever
 * touched after the guard below confirms the mods are present, and then only inside a catch that
 * includes {@link LinkageError}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SunwellAmendmentsClientCompat {

    private SunwellAmendmentsClientCompat() {
    }

    public static void init() {
        if (!ModList.get().isLoaded("amendments") || !ModList.get().isLoaded("moonlight")) {
            Sunwell.LOGGER.info("[sunwell] Amendments/Moonlight not present - wall and hand lantern compat disabled");
            return;
        }
        try {
            Hooks.install();
            Sunwell.LOGGER.info("[sunwell] Amendments lantern compat registered");
        } catch (RuntimeException | LinkageError error) {
            Sunwell.LOGGER.warn("[sunwell] Amendments lantern compat failed (fail-open): {}", error.toString());
        }
    }

    /**
     * The only place on the client that names an Amendments or Moonlight type.
     *
     * <p>Loading this class is what pulls Amendments in — which is exactly why it is separate. That
     * happens on the first line of {@link #install()}, safely inside the caller's guard and catch.</p>
     */
    private static final class Hooks {

        private Hooks() {
        }

        static void install() {
            net.mehvahdjukaar.amendments.client.renderers.LanternRendererExtension extension =
                    new net.mehvahdjukaar.amendments.client.renderers.LanternRendererExtension();
            attachHoldExtension(com.SdataG.sunwell.registry.ModBlocks.SUNWELL_LANTERN_ITEM.get(), extension);

            // Wall-lantern orbs need nothing registered here: WallLanternBlockTileRendererMixin draws
            // them inside Amendments' own renderer, in Amendments' own pose.
        }

        private static void attachHoldExtension(
                net.minecraft.world.item.Item item,
                net.mehvahdjukaar.amendments.client.renderers.LanternRendererExtension extension
        ) {
            net.mehvahdjukaar.moonlight.api.item.IThirdPersonAnimationProvider.attachToItem(item, extension);
            net.mehvahdjukaar.moonlight.api.item.IThirdPersonSpecialItemRenderer.attachToItem(item, extension);
            net.mehvahdjukaar.moonlight.api.item.IFirstPersonSpecialItemRenderer.attachToItem(item, extension);
        }
    }
}
