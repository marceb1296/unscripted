package dev.unscripted.core.scene;

import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Terrain;
import dev.unscripted.core.Time;
import dev.unscripted.core.Weather;

public final class DustDevil implements Scene {
    public static final String ID = "dust_devil";
    static final int PASSED_RADIUS = 192;
    static final long PASSED_TIME = 12000;

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
        return 24000;
    }

    @Override
    public double weight(Context c, Memory m) {
        if (!c.isDay() || c.weather() != Weather.CLEAR || wooded(c)) {
            return 0;
        }
        if (m.count(Twister.PASSED, c.x(), c.z(), PASSED_RADIUS, Time.ago(c.gameTime(), PASSED_TIME)) > 0) {
            return 0;
        }
        if (c.has(Terrain.DESERT) || c.has(Terrain.BADLANDS)) {
            return 14;
        }
        if (c.has(Terrain.BEACH) || c.has(Terrain.SAVANNA) || c.has(Terrain.SNOWY)) {
            return 8;
        }
        return c.has(Terrain.PLAINS) ? 5 : 0;
    }

    /** Entre árboles no se ve ni tiene dónde correr. */
    static boolean wooded(Context c) {
        return c.has(Terrain.FOREST) || c.has(Terrain.TAIGA) || c.has(Terrain.JUNGLE) || c.has(Terrain.SWAMP);
    }
}
