package dev.unscripted.core;

import dev.unscripted.core.scene.Twister;
import dev.unscripted.core.scene.WanderingHorde;
import java.util.HashMap;
import java.util.Map;

public record Config(boolean enabled, Map<String, Boolean> sceneEnabled, Map<String, Double> sceneWeights,
                     double minMinutes, double maxMinutes, int distanceMin, int distanceMax,
                     WanderingHorde.Size horde, boolean traderGift, boolean traderDiscount, boolean heroEffect,
                     int maxActiveScenes, int maxSceneMobs, double msptLimit, boolean liftPlayers,
                     boolean startRain, Twister.Destruction destruction) {

    public static final double DEFAULT_MIN_MINUTES = 5;
    public static final double DEFAULT_MAX_MINUTES = 15;
    public static final double MIN_MINUTES = 0.5;
    public static final double MAX_MINUTES = 1440;
    public static final int MIN_DISTANCE = 16;
    public static final int MAX_DISTANCE = 96;
    public static final int MAX_ACTIVE = 64;
    public static final int MAX_MOBS = 2000;
    public static final double DEFAULT_MSPT = 45;
    public static final double MIN_MSPT = 10;
    public static final double MAX_MSPT = 1000;
    static final long TICKS_PER_MINUTE = 1200;

    public static final Config DEFAULT = new Config(true, Map.of(), Map.of(), DEFAULT_MIN_MINUTES, DEFAULT_MAX_MINUTES,
            24, 44, WanderingHorde.Size.DEFAULT, true, true, true, 8, 120, DEFAULT_MSPT, true, true,
            Twister.Destruction.NATURAL);

    public Config(boolean enabled, Map<String, Boolean> sceneEnabled, Map<String, Double> sceneWeights,
                  double minMinutes, double maxMinutes, int distanceMin, int distanceMax, WanderingHorde.Size horde,
                  boolean traderGift, boolean traderDiscount, boolean heroEffect, int maxActiveScenes,
                  int maxSceneMobs, double msptLimit) {
        this(enabled, sceneEnabled, sceneWeights, minMinutes, maxMinutes, distanceMin, distanceMax, horde, traderGift,
                traderDiscount, heroEffect, maxActiveScenes, maxSceneMobs, msptLimit, true, true,
                Twister.Destruction.NATURAL);
    }

    public Config {
        sceneEnabled = clean(sceneEnabled);
        Map<String, Double> weights = new HashMap<>();
        clean(sceneWeights).forEach((id, w) -> weights.put(id, Double.isNaN(w) ? 1 : Math.max(0, Math.min(Settings.MAX_WEIGHT, w))));
        sceneWeights = Map.copyOf(weights);
        minMinutes = minutes(minMinutes, DEFAULT_MIN_MINUTES);
        maxMinutes = minutes(maxMinutes, DEFAULT_MAX_MINUTES);
        if (minMinutes > maxMinutes) {
            double t = minMinutes;
            minMinutes = maxMinutes;
            maxMinutes = t;
        }
        distanceMin = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distanceMin));
        distanceMax = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distanceMax));
        if (distanceMin > distanceMax) {
            int t = distanceMin;
            distanceMin = distanceMax;
            distanceMax = t;
        }
        horde = horde == null ? WanderingHorde.Size.DEFAULT : horde;
        destruction = destruction == null ? Twister.Destruction.NATURAL : destruction;
        maxActiveScenes = Math.max(0, Math.min(MAX_ACTIVE, maxActiveScenes));
        maxSceneMobs = Math.max(0, Math.min(MAX_MOBS, maxSceneMobs));
        msptLimit = Double.isNaN(msptLimit) ? DEFAULT_MSPT
                : msptLimit <= 0 ? 0 : Math.max(MIN_MSPT, Math.min(MAX_MSPT, msptLimit));
    }

    public double weight(String scene) {
        return sceneEnabled.getOrDefault(scene, true) ? sceneWeights.getOrDefault(scene, 1.0) : 0;
    }

    public Settings settings(Settings base, Iterable<String> scenes) {
        Map<String, Double> weights = new HashMap<>();
        for (String id : scenes) {
            weights.put(id, weight(id));
        }
        return new Settings(ticks(minMinutes), ticks(maxMinutes), base.nearbyRadius(), base.tenseFollowup(),
                base.lowHealth(), base.lowHealthFactor(), base.memoryCapacity(), base.memoryMaxAge(),
                base.cooldownScale(), weights);
    }

    private static long ticks(double minutes) {
        return Math.round(minutes * TICKS_PER_MINUTE);
    }

    private static double minutes(double m, double fallback) {
        return Double.isNaN(m) ? fallback : Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, m));
    }

    private static <V> Map<String, V> clean(Map<String, V> in) {
        Map<String, V> out = new HashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
        }
        return Map.copyOf(out);
    }
}
