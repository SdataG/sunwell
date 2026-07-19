package com.SdataG.sunwell;

import com.SdataG.sunwell.registry.ModBlockEntities;
import com.SdataG.sunwell.registry.ModBlocks;
import com.SdataG.sunwell.integration.SunwellAmendmentsCompat;
import com.SdataG.sunwell.integration.SunwellLanternTypeRegistrar;
import com.SdataG.sunwell.registry.ModParticles;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Sunwell.MOD_ID)
public class Sunwell {

    public static final String MOD_ID = "sunwell";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Any block in this tag emits a virtual sunwell region. Populated via KubeJS / datapacks. */
    public static final TagKey<Block> SUNWELL_SOURCE =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "sunwell_source"));

    /** Weather, surface sky, day/night, and flux modulate virtual light from this source. */
    public static final TagKey<Block> DYNAMIC_EXPOSURE =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "dynamic_exposure"));

    /** Undead sun-burn is allowed in regions lit by this source (also needs enableUndeadBurning config). */
    public static final TagKey<Block> UNDEAD_BURNING =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "undead_burning"));

    /** Rain and snow reach regions lit by this source during active weather. */
    public static final TagKey<Block> RAIN_THROUGH =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "rain_through"));

    public Sunwell(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SunwellConfig.SPEC);

        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modBus);
        ModParticles.PARTICLE_TYPES.register(modBus);
        modBus.addListener(this::onConfig);

        NeoForge.EVENT_BUS.register(SunwellEvents.class);

        // Optional integration, and the last thing the constructor does: if Amendments compat blows
        // up, Sunwell itself is already fully registered and working.
        // Must happen in the constructor: Moonlight refuses block-type finders once registry events
        // have run ("Tried to register a block type finder after registry events").
        try {
            SunwellLanternTypeRegistrar.register();
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("[sunwell] Amendments lantern type skipped (fail-open): {}", error.toString());
        }

        try {
            SunwellAmendmentsCompat.init();
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("[sunwell] Amendments compat skipped (fail-open): {}", error.toString());
        }
    }

    private void onConfig(ModConfigEvent event) {
        // The Unloading event (world leave / server stop) fires here too, but the config values can no
        // longer be read then -- baking would throw "Cannot get config value before config is loaded"
        // and crash the shutdown. Only (re)bake when the config is actually available.
        if (event instanceof ModConfigEvent.Unloading) {
            return;
        }
        if (event.getConfig().getSpec() == SunwellConfig.SPEC) {
            SunwellConfig.bake();
            SunwellManager.invalidateAll();
        }
    }
}
