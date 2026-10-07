package dev.unscripted.core.scene;

import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Terrain;
import dev.unscripted.core.Time;
import java.util.Optional;

public final class Scavengers implements Scene {
    public static final String ID = "scavengers";
    public static final String BIG_DEATH = "big_death";
    public static final String SCAVENGED = "scavenged";
    /** Restos que el jugador puede tener cerca; un carroñero no vuelve a restos ya visitados a esta distancia. */
    static final int RADIUS = 96;
    static final int VISITED = 16;
    static final long FRESH = 24000;
    static final int MANY_DEATHS = 5;
    static final int CROWD = 32;

    public enum Variant { FOXES, WOLVES, ZOMBIES }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int intensity() {
        return 1;
    }

    @Override
    public long cooldown() {
        return 12000;
    }

    @Override
    public double weight(Context c, Memory m) {
        if (!(c.isNight() || c.isDusk()) || site(c, m).isEmpty()) {
            return 0;
        }
        return 12;
    }

    public static Optional<Memory.Event> site(Context c, Memory m) {
        for (Memory.Event death : m.find(BIG_DEATH, c.x(), c.z(), RADIUS, Time.ago(c.gameTime(), FRESH))) {
            if (m.count(SCAVENGED, death.x(), death.z(), VISITED, death.time()) == 0) {
                return Optional.of(death);
            }
        }
        return Optional.empty();
    }

    public static Variant variant(Context c, Memory m, Memory.Event site) {
        int deaths = m.count(BIG_DEATH, site.x(), site.z(), CROWD, Time.ago(c.gameTime(), FRESH));
        if (deaths >= MANY_DEATHS && c.isNight() && c.hostileMobsAllowed()) {
            return Variant.ZOMBIES;
        }
        return c.has(Terrain.TAIGA) || c.has(Terrain.SNOWY) ? Variant.FOXES : Variant.WOLVES;
    }
}
