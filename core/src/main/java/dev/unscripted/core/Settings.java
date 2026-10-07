package dev.unscripted.core;

import java.util.HashMap;
import java.util.Map;

public record Settings(long minGap, long maxGap, int nearbyRadius, double tenseFollowup,
                       float lowHealth, double lowHealthFactor, int memoryCapacity, long memoryMaxAge,
                       double cooldownScale, Map<String, Double> weights) {

    public static final double MAX_WEIGHT = 5;
    public static final Settings DEFAULT = new Settings(6000, 18000, 128, 0.25, 0.4f, 0.3, 512, 7 * 24000L, 1);

    public Settings(long minGap, long maxGap, int nearbyRadius, double tenseFollowup, float lowHealth,
                    double lowHealthFactor, int memoryCapacity, long memoryMaxAge, double cooldownScale) {
        this(minGap, maxGap, nearbyRadius, tenseFollowup, lowHealth, lowHealthFactor, memoryCapacity, memoryMaxAge,
                cooldownScale, Map.of());
    }

    public Settings {
        minGap = Math.max(0, minGap);
        maxGap = Math.max(0, maxGap);
        if (minGap > maxGap) {
            long t = minGap;
            minGap = maxGap;
            maxGap = t;
        }
        nearbyRadius = Math.max(0, nearbyRadius);
        tenseFollowup = factor(tenseFollowup);
        lowHealthFactor = factor(lowHealthFactor);
        lowHealth = Float.isNaN(lowHealth) ? 0f : Math.max(0f, Math.min(1f, lowHealth));
        memoryCapacity = Math.max(1, memoryCapacity);
        memoryMaxAge = Math.max(0, memoryMaxAge);
        cooldownScale = Double.isFinite(cooldownScale) && cooldownScale >= 0 ? cooldownScale : 1;
        Map<String, Double> clean = new HashMap<>();
        if (weights != null) {
            weights.forEach((id, w) -> {
                if (id != null && w != null) {
                    clean.put(id, Double.isNaN(w) ? 1 : Math.max(0, Math.min(MAX_WEIGHT, w)));
                }
            });
        }
        weights = Map.copyOf(clean);
    }

    public double weight(String scene) {
        return weights.getOrDefault(scene, 1.0);
    }

    public Settings withPacing(long minGap, long maxGap, double cooldownScale) {
        return new Settings(minGap, maxGap, nearbyRadius, tenseFollowup, lowHealth, lowHealthFactor, memoryCapacity,
                memoryMaxAge, cooldownScale, weights);
    }

    private static double factor(double f) {
        return Double.isFinite(f) ? Math.max(0, Math.min(1, f)) : 1;
    }
}
