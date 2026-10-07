package dev.unscripted.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lo que el director sabe de un jugador en un momento dado.
 *
 * @param gameTime         ticks totales del mundo
 * @param dayTime          hora del día en ticks (se normaliza a 0..23999)
 * @param moonPhase        0 es luna llena (se normaliza a 0..7)
 * @param mobs             cantidad de mobs cercanos por id ("minecraft:sheep")
 * @param health           vida actual sobre la máxima (se limita a 0..1)
 * @param villageDistance  distancia a la aldea más cercana, o {@link #NO_VILLAGE}
 */
public record Context(long gameTime, int dayTime, int moonPhase, Weather weather, Difficulty difficulty,
                      boolean overworld, boolean underground, Set<Terrain> terrain, Map<String, Integer> mobs,
                      Activity activity, float health, int armor, int x, int z, int villageDistance) {

    public static final int NO_VILLAGE = Integer.MAX_VALUE;

    public Context {
        dayTime = Math.floorMod(dayTime, 24000);
        moonPhase = Math.floorMod(moonPhase, 8);
        weather = weather == null ? Weather.CLEAR : weather;
        difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
        terrain = terrain == null || terrain.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(terrain));
        mobs = mobs == null ? Map.of() : Map.copyOf(mobs);
        activity = activity == null ? Activity.IDLE : activity;
        health = Float.isNaN(health) ? 1f : Math.max(0f, Math.min(1f, health));
        armor = Math.max(0, armor);
        villageDistance = Math.max(0, villageDistance);
    }

    public boolean isNight() {
        return dayTime >= 13000 && dayTime < 23000;
    }

    public boolean isDusk() {
        return dayTime >= 12000 && dayTime < 13000;
    }

    public boolean isDay() {
        return !isNight() && !isDusk();
    }

    public boolean isFullMoon() {
        return moonPhase == 0;
    }

    public boolean hostileMobsAllowed() {
        return difficulty != Difficulty.PEACEFUL;
    }

    public boolean has(Terrain t) {
        return terrain.contains(t);
    }

    public int count(String mob) {
        Integer n = mobs.get(mob);
        return n == null ? 0 : Math.max(0, n);
    }
}
