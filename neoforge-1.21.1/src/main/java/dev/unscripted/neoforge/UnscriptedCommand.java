package dev.unscripted.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.unscripted.core.Context;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Settings;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class UnscriptedCommand {
    private UnscriptedCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("unscripted")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(UnscriptedCommand::status))
                .then(Commands.literal("perf").executes(ctx -> perf(ctx, false))
                        .then(Commands.literal("reset").executes(ctx -> perf(ctx, true))))
                .then(Commands.literal("pacing")
                        .then(Commands.literal("fast").executes(ctx -> pacing(ctx, true)))
                        .then(Commands.literal("normal").executes(ctx -> pacing(ctx, false))))
                .then(Commands.literal("run")
                        .then(Commands.argument("scene", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SceneManager.sceneIds(), builder))
                                .executes(ctx -> run(ctx, false))
                                .then(Commands.literal("small").executes(ctx -> run(ctx, true))))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SceneManager manager = SceneManager.get();
        if (manager == null) {
            return 0;
        }
        Context c = WorldContext.read(player, true, true);
        long wait = manager.director().waitFor(player.getStringUUID(), c.gameTime());
        ActiveScene running = manager.activeFor(player.getUUID());
        String terrain = c.terrain().stream().map(t -> t.name().toLowerCase()).sorted().collect(Collectors.joining(","));
        String village = c.villageDistance() == Context.NO_VILLAGE ? "-" : String.valueOf(c.villageDistance());
        send(ctx, Component.translatableWithFallback("unscripted.status.context",
                "Time %s, moon %s, %s, terrain [%s], village %s, health %s, armor %s, %s%s",
                c.dayTime(), c.moonPhase(), c.weather().name().toLowerCase(), terrain, village,
                Math.round(c.health() * 100) + "%", c.armor(), c.activity().name().toLowerCase(),
                c.underground() ? ", underground" : ""));
        send(ctx, Component.translatableWithFallback("unscripted.status.next", "Next scene possible in %s s",
                wait < 0 ? "?" : String.valueOf(wait / 20)));
        String weights = manager.director().weights(player.getStringUUID(), c).entrySet().stream()
                .map(e -> e.getKey() + " " + String.format(java.util.Locale.ROOT, "%.2f", e.getValue()))
                .collect(Collectors.joining(", "));
        send(ctx, Component.translatableWithFallback("unscripted.status.weights", "Weights: %s", weights));
        send(ctx, Component.translatableWithFallback("unscripted.status.limits",
                "Scenes %s/%s, scene mobs %s/%s, tick %s ms%s", manager.activeCount(), manager.config().maxActiveScenes(),
                manager.sceneMobs(), manager.config().maxSceneMobs(),
                String.format(java.util.Locale.ROOT, "%.1f", ctx.getSource().getServer().getAverageTickTimeNanos() / 1e6),
                manager.config().enabled() ? "" : ", disabled in the config"));
        if (running != null) {
            send(ctx, Component.translatableWithFallback("unscripted.status.running", "Running: %s (%s)", running.id(), running.describe()));
        }
        return 1;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean small) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "scene");
        SceneManager manager = SceneManager.get();
        if (manager == null) {
            return 0;
        }
        if (!SceneManager.sceneIds().contains(id)) {
            ctx.getSource().sendFailure(Component.translatableWithFallback("unscripted.run.unknown",
                    "Unknown scene: %s", id));
            return 0;
        }
        Scene scene = manager.scene(id);
        if (scene == null || !SceneManager.implemented(id)) {
            ctx.getSource().sendFailure(Component.translatableWithFallback("unscripted.run.missing",
                    "Scene %s is not available yet", id));
            return 0;
        }
        if (manager.activeFor(player.getUUID()) != null) {
            ctx.getSource().sendFailure(Component.translatableWithFallback("unscripted.run.busy",
                    "You already have a scene running"));
            return 0;
        }
        if (!manager.launch(player, scene, WorldContext.read(player, true, true), small)) {
            ctx.getSource().sendFailure(Component.translatableWithFallback("unscripted.run.nospot",
                    "No place found for %s around you", id));
            return 0;
        }
        send(ctx, Component.translatableWithFallback("unscripted.run.started", "Started %s", id));
        return 1;
    }

    private static int pacing(CommandContext<CommandSourceStack> ctx, boolean fast) {
        SceneManager manager = SceneManager.get();
        if (manager == null) {
            return 0;
        }
        Settings settings = manager.pacing(fast, ctx.getSource().getLevel().getGameTime());
        send(ctx, Component.translatableWithFallback("unscripted.pacing", "Pacing: %s (%s to %s s between scenes)",
                fast ? "fast" : "normal", settings.minGap() / 20, settings.maxGap() / 20));
        return 1;
    }

    private static int perf(CommandContext<CommandSourceStack> ctx, boolean reset) {
        SceneManager manager = SceneManager.get();
        if (manager == null) {
            return 0;
        }
        if (reset) {
            manager.timings().reset();
        }
        send(ctx, Component.literal(manager.timings().report() + String.format(java.util.Locale.ROOT,
                "\nscenes %d, scene mobs %d, server tick %.2f ms", manager.activeCount(), manager.sceneMobs(),
                ctx.getSource().getServer().getAverageTickTimeNanos() / 1e6)));
        return 1;
    }

    private static void send(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendSuccess(() -> message, false);
    }
}
