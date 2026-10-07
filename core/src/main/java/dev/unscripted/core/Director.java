package dev.unscripted.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Director {
    static final double MAX_WEIGHT = 1e6;

    public record PlayerSnapshot(long nextAt, int lastIntensity, Map<String, Long> lastScene) {
        public PlayerSnapshot {
            Map<String, Long> clean = new HashMap<>();
            if (lastScene != null) {
                lastScene.forEach((id, t) -> {
                    if (id != null && t != null) {
                        clean.put(id, t);
                    }
                });
            }
            lastScene = Map.copyOf(clean);
        }
    }

    private static final class PlayerState {
        long nextAt;
        int lastIntensity;
        final Map<String, Long> lastScene = new HashMap<>();

        PlayerState(long nextAt) {
            this.nextAt = nextAt;
        }

        /** Si el tiempo del mundo retrocedió, nada puede quedar bloqueado para siempre. */
        void clamp(long now, long maxGap) {
            if (nextAt - now > maxGap && nextAt > now) {
                nextAt = Time.after(now, maxGap);
            }
            lastScene.replaceAll((id, t) -> Math.min(t, now));
        }
    }

    private final Settings settings;
    private final List<Scene> scenes;
    private final Memory memory;
    private final Map<String, PlayerState> players = new HashMap<>();

    public Director(Settings settings, List<Scene> scenes, Memory memory) {
        this.settings = settings;
        this.scenes = List.copyOf(scenes);
        this.memory = memory;
    }

    public Memory memory() {
        return memory;
    }

    public List<Scene> scenes() {
        return scenes;
    }

    public Map<String, PlayerSnapshot> snapshot() {
        Map<String, PlayerSnapshot> out = new HashMap<>();
        players.forEach((id, s) -> out.put(id, new PlayerSnapshot(s.nextAt, s.lastIntensity, s.lastScene)));
        return out;
    }

    /** Carga estados guardados; los incoherentes se corrigen en el próximo {@link #choose}. */
    public void restore(Map<String, PlayerSnapshot> saved) {
        players.clear();
        if (saved == null) {
            return;
        }
        saved.forEach((id, snap) -> {
            if (id != null && snap != null) {
                PlayerState s = new PlayerState(snap.nextAt());
                s.lastIntensity = snap.lastIntensity();
                s.lastScene.putAll(snap.lastScene());
                players.put(id, s);
            }
        });
    }

    /** Ticks que faltan para que el jugador pueda tener otra escena (0 si ya puede, -1 si no se lo conoce). */
    public long waitFor(String player, long now) {
        PlayerState s = players.get(player);
        return s == null ? -1 : Math.max(0, s.nextAt - now);
    }

    public Map<String, Double> weights(String player, Context c) {
        PlayerState s = players.getOrDefault(player, new PlayerState(Long.MIN_VALUE));
        Map<String, Double> out = new LinkedHashMap<>();
        for (Scene scene : scenes) {
            out.put(scene.id(), weight(scene, s, c));
        }
        return out;
    }

    /** La escena que debería empezar ahora para el jugador, si alguna. No registra nada hasta {@link #started}. */
    public Optional<Scene> choose(String player, Context c, Rng rng) {
        long now = c.gameTime();
        PlayerState s = players.computeIfAbsent(player, k -> new PlayerState(Time.after(now, gap(rng, 1))));
        s.clamp(now, Time.after(settings.maxGap(), settings.maxGap()));
        if (now < s.nextAt || !c.overworld() || c.underground() || c.activity() == Activity.FIGHTING) {
            return Optional.empty();
        }
        double[] w = new double[scenes.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = weight(scenes.get(i), s, c);
            total += w[i];
        }
        if (total <= 0) {
            return Optional.empty();
        }
        double r = rng.nextDouble() * total;
        int last = -1;
        for (int i = 0; i < w.length; i++) {
            if (w[i] > 0) {
                last = i;
                r -= w[i];
                if (r < 0) {
                    return Optional.of(scenes.get(i));
                }
            }
        }
        return Optional.of(scenes.get(last));
    }

    /** El juego confirma que la escena empezó (encontró lugar): se aplican enfriamientos y memoria. */
    public void started(String player, Scene scene, Context c, Rng rng) {
        long now = c.gameTime();
        PlayerState s = players.computeIfAbsent(player, k -> new PlayerState(now));
        s.lastScene.put(scene.id(), now);
        s.lastIntensity = scene.intensity();
        s.nextAt = Time.after(now, gap(rng, 1 + 0.5 * Math.max(0, scene.intensity() - 1)));
        memory.add(sceneEvent(scene), c.x(), c.z(), now);
    }

    public static String sceneEvent(Scene scene) {
        return "scene:" + scene.id();
    }

    double weight(Scene scene, PlayerState s, Context c) {
        double w = scene.weight(c, memory);
        if (!(w > 0) || !Double.isFinite(w)) {
            return 0;
        }
        // acotar antes de multiplicar: un peso enorme por el de la configuración daría infinito
        w = Math.min(w, MAX_WEIGHT) * settings.weight(scene.id());
        long now = c.gameTime();
        // la conversión de double a long satura en Long.MAX_VALUE
        long cooldown = (long) (Math.max(0, scene.cooldown()) * settings.cooldownScale());
        long since = Time.ago(now, cooldown);
        Long last = s.lastScene.get(scene.id());
        if (last != null && last > since) {
            return 0;
        }
        if (memory.count(sceneEvent(scene), c.x(), c.z(), settings.nearbyRadius(), since + 1) > 0) {
            return 0;
        }
        if (scene.intensity() >= 2) {
            if (s.lastIntensity >= 2) {
                w *= settings.tenseFollowup();
            }
            if (c.health() < settings.lowHealth()) {
                w *= settings.lowHealthFactor();
            }
        }
        return w;
    }

    private long gap(Rng rng, double scale) {
        double g = (settings.minGap() + rng.nextDouble() * (settings.maxGap() - settings.minGap())) * scale;
        return g >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) g;
    }
}
