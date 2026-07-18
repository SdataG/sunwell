package com.SdataG.sunwell;

import com.SdataG.sunwell.integration.SunwellAmendmentsCompat;
import com.SdataG.sunwell.registry.ModBlockEntities;
import com.SdataG.sunwell.registry.ModBlocks;
import com.SdataG.sunwell.registry.ModParticles;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

    /**
     * Blocks that <em>hold</em> another block inside a block entity, e.g. an Amendments wall lantern.
     *
     * <p>These are not sources themselves — a wall lantern holding a vanilla lantern must stay inert.
     * They are only a source when the block they hold is one, and they then inherit that held block's
     * profile. Tag-driven so any Moonlight {@code IBlockHolder} works, not just Amendments.</p>
     */
    public static final TagKey<Block> SOURCE_HOLDER =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "source_holder"));

    /** Rain and snow reach regions lit by this source during active weather. */
    public static final TagKey<Block> RAIN_THROUGH =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "rain_through"));

    public Sunwell() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SunwellConfig.SPEC);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modBus);
        ModParticles.PARTICLE_TYPES.register(modBus);
        modBus.addListener(this::onConfig);

        MinecraftForge.EVENT_BUS.register(SunwellEvents.class);

        // Optional integration, and the last thing the constructor does: if Amendments compat blows
        // up, Sunwell itself is already fully registered and working.
        try {
            SunwellAmendmentsCompat.init();
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("[sunwell] Amendments compat skipped (fail-open): {}", error.toString());
        }
    }

    private void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SunwellConfig.SPEC) {
            SunwellConfig.bake();
            SunwellManager.invalidateAll();
        }
    }
}
