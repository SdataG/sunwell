package com.SdataG.sunwell.integration;

import com.SdataG.sunwell.Sunwell;
import net.neoforged.fml.ModList;

/**
 * Registers the Sunwell Lantern with Amendments as a first-class lantern type, on 1.21.
 *
 * <p><b>Why this is different from the 1.20 route.</b> 1.20 has a single wall-lantern block that
 * mimics whatever lantern it holds, so making {@code WallLanternBlock.isValidBlock} return true is
 * enough — nothing new has to exist. 1.21 registers a <em>separate</em> wall-lantern block per lantern
 * type, and generates that block's assets in {@code ClientResourceGenerator}, which iterates
 * {@code LanternRegistry.getValues()} during resource-pack generation.</p>
 *
 * <p>That difference is why intercepting {@code detectTypeFromBlock} at render time failed: the type
 * appeared <em>after</em> the generator had already run, so Moonlight emitted a blockstate whose model
 * had never been built, and the wall lantern rendered as the missing-model cube. Nothing was logged,
 * because nothing errored — the generator simply never saw us.</p>
 *
 * <p>Moonlight has a supported entry point for exactly this: {@code BlockSetAPI.addBlockTypeFinder}.
 * It queues the finder ({@code BlockSetInternal.FINDER_ADDER}) and drains it in
 * {@code initializeBlockSets()}, before the block sets are filled and therefore before assets are
 * generated. The finder is a {@code Supplier<Optional<T>>}, so it's <em>called</em> later than it is
 * <em>registered</em> — which is what lets us hand over a block that doesn't exist yet at construction
 * time.</p>
 *
 * <p>It must be called during mod construction: {@code addFinder} throws
 * "Tried to register a block type finder after registry events" once frozen.</p>
 */
public final class SunwellLanternTypeRegistrar {

    private SunwellLanternTypeRegistrar() {
    }

    /** Call from the mod constructor, before registry events. */
    public static void register() {
        if (!ModList.get().isLoaded("amendments") || !ModList.get().isLoaded("moonlight")) {
            return;
        }
        try {
            Hooks.addFinder();
            Sunwell.LOGGER.info("[sunwell] registered Sunwell Lantern as an Amendments lantern type");
        } catch (RuntimeException | LinkageError error) {
            Sunwell.LOGGER.warn("[sunwell] Amendments lantern type registration failed (fail-open): {}",
                    error.toString());
        }
    }

    /**
     * The only place that names an Amendments/Moonlight type. Loading this class is what pulls them
     * in, so it happens inside the guard above — see {@link SunwellAmendmentsClientCompat} for why
     * that separation is load-bearing rather than stylistic.
     */
    private static final class Hooks {

        private Hooks() {
        }

        static void addFinder() {
            net.mehvahdjukaar.moonlight.api.set.BlockSetAPI.addBlockTypeFinder(
                    net.mehvahdjukaar.amendments.common.LanternRegistry.LanternType.class,
                    () -> {
                        // Runs at buildAll(), long after our block is registered — hence a Supplier.
                        net.minecraft.world.level.block.Block lantern =
                                com.SdataG.sunwell.registry.ModBlocks.SUNWELL_LANTERN.get();
                        if (lantern == null) {
                            return java.util.Optional.empty();
                        }
                        return java.util.Optional.of(
                                new net.mehvahdjukaar.amendments.common.LanternRegistry.LanternType(
                                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                                Sunwell.MOD_ID, "sunwell_lantern"),
                                        lantern));
                    });
        }
    }
}
