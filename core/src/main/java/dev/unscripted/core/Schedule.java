package dev.unscripted.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reparte las consultas al director: cada jugador tiene un turno fijo dentro del intervalo (según su id,
 * así reaparecer no lo cambia) y nunca se atienden más de {@code budget} por tick. Los que no entran
 * esperan su turno en orden de llegada.
 */
public final class Schedule {
    private final int interval;
    private final int budget;
    private final Set<String> waiting = new LinkedHashSet<>();

    public Schedule(int interval, int budget) {
        this.interval = Math.max(1, interval);
        this.budget = Math.max(1, budget);
    }

    public List<String> due(long now, List<String> online) {
        long slot = Math.floorMod(now, interval);
        for (String id : online) {
            if (id != null && Math.floorMod(id.hashCode(), interval) == slot) {
                waiting.add(id);
            }
        }
        List<String> out = new ArrayList<>(Math.min(budget, waiting.size()));
        if (waiting.isEmpty()) {
            return out;
        }
        Set<String> connected = new HashSet<>(online);
        Iterator<String> it = waiting.iterator();
        while (it.hasNext() && out.size() < budget) {
            String id = it.next();
            it.remove();
            if (connected.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public int waiting() {
        return waiting.size();
    }
}
