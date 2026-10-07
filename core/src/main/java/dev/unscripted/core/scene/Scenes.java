package dev.unscripted.core.scene;

import dev.unscripted.core.Scene;
import java.util.List;

public final class Scenes {
    private Scenes() {
    }

    public static List<Scene> all() {
        return all(true);
    }

    public static List<Scene> all(boolean startRain) {
        return List.of(new WolfHunt(), new Scavengers(), new TraderInTrouble(), new WanderingHorde(), new DustDevil(),
                new Tornado(startRain));
    }
}
