package dev.unscripted.core.scene;

import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Time;
import java.util.List;
import java.util.Map;

public final class TraderInTrouble implements Scene {
    public static final String ID = "trader_in_trouble";
    public static final String SAVED = "trader_saved";
    public static final int GIFT_ROLLS = 3;
    private static final Map<String, Integer> VALUE = Map.of(
            "minecraft:diamond", 100,
            "minecraft:emerald", 40,
            "minecraft:gold_ingot", 20,
            "minecraft:experience_bottle", 15,
            "minecraft:iron_ingot", 10,
            "minecraft:lapis_lazuli", 8,
            "minecraft:gold_nugget", 2,
            "minecraft:iron_nugget", 1);
    /** Lo que no está en la lista (otro paquete de datos cambió el botín) vale esto. */
    static final int UNKNOWN_VALUE = 5;

    public record Loot(String id, int count) {
    }

    /** El índice del botín más valioso (valor por unidad por cantidad), o -1 si no hay ninguno que sirva. */
    public static int bestGift(List<Loot> loot) {
        int best = -1;
        long bestValue = 0;
        for (int i = 0; i < loot.size(); i++) {
            Loot l = loot.get(i);
            if (l == null || l.id() == null || l.count() <= 0) {
                continue;
            }
            long v = (long) VALUE.getOrDefault(l.id(), UNKNOWN_VALUE) * l.count();
            if (v > bestValue) {
                best = i;
                bestValue = v;
            }
        }
        return best;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int intensity() {
        return 2;
    }

    @Override
    public long cooldown() {
        return 36000;
    }

    @Override
    public double weight(Context c, Memory m) {
        if (!c.hostileMobsAllowed() || c.isNight()) {
            return 0;
        }
        double w = 6;
        if (m.count(SAVED, c.x(), c.z(), 256, Time.ago(c.gameTime(), 3 * 24000L)) > 0) {
            w *= 0.5;
        }
        return w;
    }
}
