package com.SdataG.sunwell;

import com.SdataG.sunwell.client.SunwellWeatherEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge-bus glue. Block place/break/fill detection lives in
 * {@code LevelChunkMixin} so that command /fill, KubeJS, worldgen post-edits and
 * other mods are all covered, not just player placement events.
 */
public final class SunwellEvents {

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof Level level) {
            SunwellManager.onLevelLoad(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            SunwellManager.onLevelUnload(level);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // May fire on worker threads; the manager only enqueues here and scans on the level thread.
        if (event.getLevel() instanceof Level level && event.getChunk() instanceof LevelChunk chunk) {
            SunwellManager m = SunwellManager.get(level);
            if (m != null) {
                m.queueChunkScan(chunk);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level && event.getChunk() instanceof LevelChunk chunk) {
            SunwellManager m = SunwellManager.get(level);
            if (m != null) {
                m.unloadChunk(chunk);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Level level = event.level;
        SunwellManager manager = SunwellManager.get(level);
        if (manager == null) {
            return;
        }

        if (level.isClientSide) {
            // Always drive the client tick: processTick floods the rain-through cache while it is
            // raining and clears it (re-rendering the affected sections) once rain stops. Gating this
            // on isRaining meant the clear path never ran after rain ended, so the virtual sky light
            // stayed rendered — the room looked "still exposed to sky" until a manual block update.
            manager.processTick();
            // The config/weather gate lives inside tick() rather than being duplicated here, so the
            // debugWeatherParticles trace can report "not raining" as the reason instead of the call
            // simply never happening.
            SunwellWeatherEffects.tick(level, manager);
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        manager.processTick();
        manager.tryWeatherLightning(serverLevel);
        manager.tryWeatherSnow(serverLevel);
    }

    @SubscribeEvent
    public static void onMobSpawn(MobSpawnEvent.PositionCheck event) {
        // Natural spawns only; structure spawners and summon commands bypass this event.
        // Server-side virtual sky is authoritative; ACTIVE short-circuits when no lit regions exist.
        if (!SunwellManager.ACTIVE || !SunwellConfig.blockHostileSpawns) {
            return;
        }
        if (event.getEntity().getType().getCategory() != MobCategory.MONSTER) {
            return;
        }
        ServerLevel sl = event.getLevel().getLevel();
        SunwellManager m = SunwellManager.get(sl);
        if (m != null) {
            BlockPos pos = event.getEntity().blockPosition();
            // baseSkyAt: flooded region regardless of day/night scaling on dynamic_exposure sources.
            if (m.baseSkyAt(pos) > 0) {
                event.setResult(Event.Result.DENY);
            }
        }
    }

    private SunwellEvents() {
    }
}
