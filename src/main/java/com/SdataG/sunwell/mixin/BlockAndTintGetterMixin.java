package com.SdataG.sunwell.mixin;

import com.SdataG.sunwell.SunwellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Lets rain-through sunwell columns satisfy open-sky checks (rain collectors, cauldrons,
 * snow accumulation) during active weather, without raising virtual sky light to 15.
 *
 * <p>{@code canSeeSky} is a <em>default method on the {@link BlockAndTintGetter} interface</em>,
 * not a method declared on {@code Level}. Injecting into {@code Level.canSeeSky} silently fails
 * (no descriptor to resolve), and Mixin 0.8.x does not allow {@code @Inject} inside an interface
 * mixin ("Injector in interface is unsupported"), so overwriting the interface default is the only
 * hook that actually fires for {@code Level}/{@code ClientLevel}, which inherit the default. The
 * overwrite body preserves vanilla behavior for every other caller.</p>
 */
@Mixin(BlockAndTintGetter.class)
public interface BlockAndTintGetterMixin {

    /**
     * @author Sunwell
     * @reason Rain-through sunwell columns must satisfy open-sky checks during weather
     * without raising virtual sky light to 15. Interface default methods cannot be hooked
     * with an injector in Mixin 0.8.x, so the default is overwritten and vanilla behavior
     * is reproduced for the non-sunwell path.
     */
    @Overwrite
    default boolean canSeeSky(BlockPos pos) {
        BlockAndTintGetter self = (BlockAndTintGetter) this;
        if (self instanceof Level level && SunwellManager.allowsSkyAccessForWeatherAt(level, pos)) {
            return true;
        }
        return self.getBrightness(LightLayer.SKY, pos) >= self.getMaxLightLevel();
    }
}
