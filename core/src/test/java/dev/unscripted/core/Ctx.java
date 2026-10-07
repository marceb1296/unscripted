package dev.unscripted.core;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Contexto de prueba: de día, en un bosque del mundo normal, explorando y con vida llena. */
final class Ctx {
    long time = 100_000;
    int dayTime = 6000;
    int moon = 3;
    Difficulty difficulty = Difficulty.NORMAL;
    boolean overworld = true;
    boolean underground = false;
    Set<Terrain> terrain = EnumSet.of(Terrain.FOREST);
    Map<String, Integer> mobs = new HashMap<>();
    Activity activity = Activity.EXPLORING;
    float health = 1f;
    int armor = 10;
    int x = 0;
    int z = 0;
    int village = Context.NO_VILLAGE;

    Ctx at(long t) {
        time = t;
        return this;
    }

    Ctx pos(int px, int pz) {
        x = px;
        z = pz;
        return this;
    }

    Ctx night() {
        dayTime = 18000;
        return this;
    }

    Context build() {
        return new Context(time, dayTime, moon, Weather.CLEAR, difficulty, overworld, underground, terrain, mobs,
                activity, health, armor, x, z, village);
    }
}
