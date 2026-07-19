package com.SdataG.sunwell;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /sunwell} operator commands for tuning lightning live, without editing the config by hand.
 *
 * <ul>
 *   <li>{@code /sunwell lightning}            &mdash; report the current odds/boost.</li>
 *   <li>{@code /sunwell lightning <odds>}     &mdash; 1-in-N chance per tick (0 = off). Lower = more often.</li>
 *   <li>{@code /sunwell rodboost <mult>}      &mdash; how much likelier near a lightning rod.</li>
 *   <li>{@code /sunwell probe}                &mdash; diagnose the sky light at the block you're looking
 *       at: its own value, and whether each face-adjacent neighbor is actually occluding it. Use this
 *       to confirm or rule out light crossing a wall, rather than guessing from a screenshot.</li>
 * </ul>
 *
 * <p>Changes are written straight to the server config so they persist across restarts.</p>
 */
public final class SunwellCommands {

    private SunwellCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sunwell")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("lightning")
                        .executes(ctx -> report(ctx.getSource()))
                        .then(Commands.argument("odds", IntegerArgumentType.integer(0, 1_000_000))
                                .executes(ctx -> {
                                    int value = IntegerArgumentType.getInteger(ctx, "odds");
                                    SunwellConfig.setLightningOdds(value);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "[sunwell] lightning odds set to 1-in-" + value
                                                    + (value == 0 ? " (off)" : "") + " -- lower = more frequent"), true);
                                    return 1;
                                })))
                .then(Commands.literal("rodboost")
                        .executes(ctx -> report(ctx.getSource()))
                        .then(Commands.argument("mult", IntegerArgumentType.integer(1, 100_000))
                                .executes(ctx -> {
                                    int value = IntegerArgumentType.getInteger(ctx, "mult");
                                    SunwellConfig.setLightningRodBoost(value);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "[sunwell] lightning rod boost set to " + value + "x"), true);
                                    return 1;
                                })))
                .then(Commands.literal("probe")
                        .executes(ctx -> probe(ctx.getSource()))));
    }

    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[sunwell] lightning: 1-in-" + SunwellConfig.lightningThroughOdds
                        + (SunwellConfig.lightningThroughOdds == 0 ? " (off)" : "")
                        + ", rod boost " + SunwellConfig.lightningRodBoost + "x"), false);
        return 1;
    }

    private static int probe(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        SunwellManager manager = SunwellManager.get(level);
        if (manager == null) {
            source.sendFailure(Component.literal("[sunwell] no manager for this level"));
            return 0;
        }

        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(24.0D));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("[sunwell] not looking at a block within 24 blocks"));
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        source.sendSuccess(() -> Component.literal(String.format(
                "[sunwell] probe %s: baseSky=%d sky=%d block=%s lightBlock=%d(%s)",
                pos.toShortString(), manager.baseSkyAt(pos), manager.skyAt(pos),
                state.getBlock(), state.getLightBlock(level, pos),
                state.getLightBlock(level, pos) >= 15 ? "OPAQUE" : "passes light")), false);

        // Each face-adjacent neighbor: its own baseSky and whether IT would block propagation. If a
        // neighbor is opaque (>=15) but still shows baseSky>0, light reached it from some other,
        // legitimate open path -- not through this face. If a non-opaque neighbor carries baseSky>0,
        // that is how the light is actually getting in.
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            BlockState ns = level.getBlockState(n);
            int nLightBlock = ns.getLightBlock(level, n);
            source.sendSuccess(() -> Component.literal(String.format(
                    "  %-5s %s: baseSky=%d block=%s lightBlock=%d(%s)",
                    dir, n.toShortString(), manager.baseSkyAt(n), ns.getBlock(), nLightBlock,
                    nLightBlock >= 15 ? "OPAQUE" : "passes light")), false);
        }
        return 1;
    }
}
