package dev.unscripted.neoforge;

import dev.unscripted.core.Config;
import dev.unscripted.core.Context;
import dev.unscripted.core.Director;
import dev.unscripted.core.Limits;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Schedule;
import dev.unscripted.core.Settings;
import dev.unscripted.core.Time;
import dev.unscripted.core.Timings;
import dev.unscripted.core.scene.DustDevil;
import dev.unscripted.core.scene.Scavengers;
import dev.unscripted.core.scene.Scenes;
import dev.unscripted.core.scene.Tornado;
import dev.unscripted.core.scene.TraderInTrouble;
import dev.unscripted.core.scene.Twister;
import dev.unscripted.core.scene.WanderingHorde;
import dev.unscripted.core.scene.WolfHunt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

final class SceneManager {
    static final int EVAL_INTERVAL = 400;
    /** Con 2 por tick alcanza para 800 jugadores sin que ninguno espere más que el intervalo. */
    static final int EVALS_PER_TICK = 2;
    static final double MOVED = 16;
    static final int ZONE = 96;

    interface Starter {
        @Nullable
        ActiveScene start(SceneManager m, ServerPlayer player, Context context, boolean small);
    }

    private static final Map<String, Starter> STARTERS = Map.of(
            WolfHunt.ID, (m, player, c, small) -> WolfHuntScene.start(player, m.memory(), m.config),
            Scavengers.ID, (m, player, c, small) -> ScavengersScene.start(player, c, m.memory()),
            TraderInTrouble.ID, (m, player, c, small) -> TraderScene.start(player, m.memory(), m.config),
            WanderingHorde.ID, (m, player, c, small) -> HordeScene.start(player, m.memory(), small, m.config,
                    () -> Limits.room(m.config, m.sceneMobs())),
            DustDevil.ID, (m, player, c, small) -> TwisterScene.start(player, c, m.memory(), m.config,
                    Twister.Kind.DUST_DEVIL),
            Tornado.ID, (m, player, c, small) -> TwisterScene.start(player, c, m.memory(), m.config,
                    Twister.Kind.TORNADO));

    /** Mobs que pone cada escena como mucho al empezar: el tope de mobs se revisa antes de buscarle lugar. */
    private int needed(String scene) {
        return switch (scene) {
            case WolfHunt.ID -> 2 * WolfHuntScene.MAX_FLOCK - 1;
            case TraderInTrouble.ID -> 1 + TraderScene.LLAMAS + TraderScene.MAX_RAIDERS;
            case Scavengers.ID -> ScavengersScene.MAX_MOBS;
            case WanderingHorde.ID -> config.horde().size(0, true, 0);
            default -> 0;
        };
    }

    @Nullable
    private static SceneManager current;

    private Config config;
    private boolean fast;
    @Nullable
    private String blocked;

    private final MinecraftServer server;
    private final UnscriptedData data;
    private Director director;
    private final List<ActiveScene> active = new ArrayList<>();
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Timings timings = new Timings();
    /** Lo que tardaron en este tick los arranques de escenas, para no contarlo dos veces en el director. */
    private long startNanos;
    private final Schedule schedule = new Schedule(EVAL_INTERVAL, EVALS_PER_TICK);

    private SceneManager(MinecraftServer server) {
        this.server = server;
        data = server.overworld().getDataStorage().computeIfAbsent(UnscriptedData.FACTORY, UnscriptedData.NAME);
        config = UnscriptedConfig.read();
        long now = server.overworld().getGameTime();
        Memory memory = new Memory(Settings.DEFAULT.memoryCapacity(), Settings.DEFAULT.memoryMaxAge());
        memory.load(data.events, now);
        List<Scene> scenes = scenes(config);
        director = new Director(settings(scenes), scenes, memory);
        director.restore(data.players);
        data.director = director;
    }

    static void start(MinecraftServer server) {
        current = new SceneManager(server);
    }

    static void stop() {
        if (current != null) {
            current.active.forEach(ActiveScene::stop);
            current.active.clear();
            current.data.setDirty();
            current = null;
        }
    }

    @Nullable
    static SceneManager get() {
        return current;
    }

    private static List<Scene> scenes(Config c) {
        return Scenes.all(c.startRain()).stream().filter(s -> STARTERS.containsKey(s.id())).toList();
    }

    static List<String> sceneIds() {
        return Scenes.all().stream().map(Scene::id).toList();
    }

    static boolean implemented(String id) {
        return STARTERS.containsKey(id);
    }

    void tick(MinecraftServer server) {
        timings.tick();
        long start = System.nanoTime();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            List<String> online = new ArrayList<>(players.size());
            for (ServerPlayer p : players) {
                online.add(p.getStringUUID());
            }
            for (String id : schedule.due(server.getTickCount(), online)) {
                ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(id));
                if (p != null) {
                    evaluate(p);
                }
            }
        }
        long now = System.nanoTime();
        timings.add("director", now - start - startNanos);
        startNanos = 0;
        active.removeIf(scene -> {
            long t0 = System.nanoTime();
            String key = scene.id() + " " + scene.phase();
            try {
                return scene.tick();
            } catch (RuntimeException e) {
                Unscripted.LOGGER.error("Scene {} stopped after an error", scene.id(), e);
                scene.stop();
                return true;
            } finally {
                timings.add(key, System.nanoTime() - t0);
            }
        });
        timings.add("total", System.nanoTime() - start);
    }

    Timings timings() {
        return timings;
    }

    private void evaluate(ServerPlayer player) {
        // en la pantalla de muerte el cuerpo sigue en el mundo: la escena saldría donde nadie la ve
        if (player.isSpectator() || !player.isAlive()) {
            return;
        }
        Vec3 before = lastPos.put(player.getUUID(), player.position());
        boolean moved = before != null && before.distanceToSqr(player.position()) > MOVED * MOVED;
        if (activeFor(player.getUUID()) != null || !allowed(0)) {
            return;
        }
        // cerca de una escena en curso, el jugador ya es parte de ella
        for (ActiveScene s : active) {
            if (s.center().distanceToSqr(player.position()) < (double) ZONE * ZONE) {
                return;
            }
        }
        Context c = WorldContext.read(player, moved, false);
        Optional<Scene> scene = director.choose(player.getStringUUID(), c, player.getRandom()::nextDouble);
        scene.filter(s -> allowed(needed(s.id()))).ifPresent(s -> launch(player, s, c));
        data.setDirty();
    }

    boolean launch(ServerPlayer player, Scene scene, Context c) {
        return launch(player, scene, c, false);
    }

    boolean launch(ServerPlayer player, Scene scene, Context c, boolean small) {
        long t0 = System.nanoTime();
        ActiveScene running = STARTERS.get(scene.id()).start(this, player, c, small);
        long took = System.nanoTime() - t0;
        timings.add("start " + scene.id(), took);
        startNanos += took;
        if (running == null) {
            return false;
        }
        active.add(running);
        director.started(player.getStringUUID(), scene, c, player.getRandom()::nextDouble);
        data.setDirty();
        Unscripted.LOGGER.debug("Scene {} for {}", scene.id(), player.getName().getString());
        return true;
    }

    @Nullable
    Scene scene(String id) {
        return director.scenes().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    @Nullable
    ActiveScene activeFor(UUID player) {
        for (ActiveScene s : active) {
            if (s.player().equals(player)) {
                return s;
            }
        }
        return null;
    }

    Director director() {
        return director;
    }

    Memory memory() {
        return director.memory();
    }

    Config config() {
        return config;
    }

    private boolean allowed(int needed) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        String why = Limits.blocked(config, active.size(), sceneMobs(), needed, mspt);
        // el tope de mobs depende de la escena elegida: solo se anota el de las demás razones, y una vez por
        // motivo (el número de milisegundos cambia en cada consulta)
        String kind = why == null ? null : why.split(" \\(")[0];
        if (needed == 0 && !java.util.Objects.equals(kind, blocked)) {
            Unscripted.LOGGER.info(why == null ? "New scenes allowed again" : "No new scenes: {}", why);
            blocked = kind;
        }
        return why == null;
    }

    int sceneMobs() {
        int n = 0;
        for (ActiveScene s : active) {
            n += s.mobs();
        }
        return n;
    }

    int activeCount() {
        return active.size();
    }

    private Settings settings(List<Scene> scenes) {
        Settings s = config.settings(Settings.DEFAULT, scenes.stream().map(Scene::id).toList());
        return fast ? s.withPacing(600, 1200, 0.05) : s;
    }

    void apply(Config c) {
        config = c;
        rebuild(server.overworld().getGameTime());
        Unscripted.LOGGER.info("Config applied: {}", c);
    }

    Settings pacing(boolean fast, long now) {
        this.fast = fast;
        rebuild(now);
        return settingsNow();
    }

    Settings settingsNow() {
        return settings(director.scenes());
    }

    /** Cambia el ritmo conservando memoria y enfriamientos; nadie queda esperando más que el nuevo máximo. */
    private void rebuild(long now) {
        Settings settings = settingsNow();
        Map<String, Director.PlayerSnapshot> players = new HashMap<>();
        director.snapshot().forEach((id, snap) -> players.put(id, new Director.PlayerSnapshot(
                Math.min(snap.nextAt(), Time.after(now, settings.maxGap())), snap.lastIntensity(), snap.lastScene())));
        director = new Director(settings, scenes(config), director.memory());
        director.restore(players);
        data.director = director;
    }

    void bigDeath(int x, int z, long now) {
        director.memory().add(Scavengers.BIG_DEATH, x, z, now);
        data.setDirty();
    }

    void died(LivingEntity dead, DamageSource source) {
        for (ActiveScene s : active) {
            s.died(dead, source);
        }
    }

    void converted(LivingEntity from, LivingEntity to) {
        for (ActiveScene s : active) {
            s.converted(from, to);
        }
    }

    void forget(UUID player) {
        lastPos.remove(player);
    }
}
