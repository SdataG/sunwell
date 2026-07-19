package com.SdataG.sunwell.mixin;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates optional-mod mixins (Amendments hand-orb inject) when dependencies are absent.
 */
public final class SunwellMixinPlugin implements IMixinConfigPlugin {

    /** Every mixin that targets an Amendments class. Applied only when Amendments is present. */
    private static final Set<String> AMENDMENTS_MIXINS = Set.of(
            "com.SdataG.sunwell.mixin.client.amendments.LanternRendererExtensionMixin",
            "com.SdataG.sunwell.mixin.amendments.WallLanternBlockMixin",
            "com.SdataG.sunwell.mixin.client.amendments.WallLanternBlockTileRendererMixin");

    /** Reapplies the virtual-light boost inside Embeddium's cloned light data. Only when Embeddium is present. */
    private static final Set<String> SODIUM_MIXINS = Set.of(
            "com.SdataG.sunwell.mixin.client.SodiumLevelSliceMixin");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (AMENDMENTS_MIXINS.contains(mixinClassName)) {
            return isModLoaded("amendments") && isModLoaded("moonlight");
        }
        if (SODIUM_MIXINS.contains(mixinClassName)) {
            return isModLoaded("embeddium");
        }
        return true;
    }

    /**
     * ModList is null during very early mixin prepare (Forge early display). Fall back to
     * LoadingModList, which is populated once mod jars are discovered.
     */
    private static boolean isModLoaded(String modId) {
        ModList modList = ModList.get();
        if (modList != null) {
            return modList.isLoaded(modId);
        }
        LoadingModList loading = LoadingModList.get();
        if (loading != null) {
            return loading.getModFileById(modId) != null;
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
