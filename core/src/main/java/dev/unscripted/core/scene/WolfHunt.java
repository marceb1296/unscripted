package dev.unscripted.core.scene;

import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Time;
import dev.unscripted.core.Terrain;

public final class WolfHunt implements Scene {
    public static final String ID = "wolf_hunt";
    public static final String FED = "wolves_fed";
    public static final String DRIVEN_OFF = "wolves_driven_off";

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
        if (!(c.has(Terrain.FOREST) || c.has(Terrain.TAIGA) || c.has(Terrain.PLAINS) || c.has(Terrain.SAVANNA))) {
            return 0;
        }
        double w = 10;
        if (c.isNight()) {
            w *= 0.5;
        }
        if (c.count("minecraft:sheep") >= 3) {
            w *= 1.5;
        }
        if (m.count(DRIVEN_OFF, c.x(), c.z(), 256, Time.ago(c.gameTime(), 3 * 24000L)) > 0) {
            w *= 0.3;
        }
        return w;
    }
}
