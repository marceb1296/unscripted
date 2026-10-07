package dev.unscripted.core.scene;

import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Terrain;
import dev.unscripted.core.Time;
import dev.unscripted.core.Weather;

/**
 * Un tornado cruza a la vista con la lluvia. Si no llueve y {@code startRain} lo permite, la escena empieza
 * una lluvia corta; como llueve para todo el servidor, eso ocurre a lo sumo una vez cada {@link #RAIN_GAP}.
 */
public final class Tornado implements Scene {
    public static final String ID = "tornado";
    public static final String RAIN_STARTED = "rain_started";
    static final long RAIN_GAP = 2 * 24000L;
    static final int PASSED_RADIUS = 256;
    static final long PASSED_TIME = 3 * 24000L;

    private final boolean startRain;

    public Tornado(boolean startRain) {
        this.startRain = startRain;
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
        return 3 * 24000L;
    }

    @Override
    public double weight(Context c, Memory m) {
        if (!c.isDay() || overWater(c)) {
            return 0;
        }
        if (m.count(Twister.PASSED, c.x(), c.z(), PASSED_RADIUS, Time.ago(c.gameTime(), PASSED_TIME)) > 0) {
            return 0;
        }
        double w = switch (c.weather()) {
            case THUNDER -> 12;
            case RAIN -> 8;
            case CLEAR -> canStartRain(c, m) ? 4 : 0;
        };
        if (c.has(Terrain.FOREST) || c.has(Terrain.TAIGA) || c.has(Terrain.JUNGLE)) {
            w *= 0.5;
        }
        return w;
    }

    public boolean canStartRain(Context c, Memory m) {
        return startRain && c.weather() == Weather.CLEAR
                && m.count(RAIN_STARTED, c.x(), c.z(), Integer.MAX_VALUE, Time.ago(c.gameTime(), RAIN_GAP)) == 0;
    }

    static boolean overWater(Context c) {
        if (c.terrain().isEmpty()) {
            return false;
        }
        for (Terrain t : c.terrain()) {
            if (t != Terrain.OCEAN && t != Terrain.RIVER) {
                return false;
            }
        }
        return true;
    }
}
