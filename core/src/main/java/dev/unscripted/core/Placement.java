package dev.unscripted.core;

import java.util.ArrayList;
import java.util.List;

public final class Placement {
    public record Offset(int dx, int dz) {
    }

    private Placement() {
    }

    public static List<Offset> candidates(Rng rng, int min, int max, int count) {
        min = Math.max(0, min);
        max = Math.max(min, max);
        List<Offset> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = Math.sqrt((double) min * min + rng.nextDouble() * ((double) max * max - (double) min * min));
            out.add(new Offset((int) Math.round(Math.cos(angle) * dist), (int) Math.round(Math.sin(angle) * dist)));
        }
        return out;
    }
}
