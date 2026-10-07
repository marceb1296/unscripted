package dev.unscripted.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class Memory {
    public record Event(String type, int x, int z, long time) {
    }

    static final int LOAD_LIMIT = 4;

    private final int capacity;
    private final long maxAge;
    private final Deque<Event> events = new ArrayDeque<>();

    public Memory(int capacity, long maxAge) {
        this.capacity = Math.max(1, capacity);
        this.maxAge = Math.max(0, maxAge);
    }

    public void add(String type, int x, int z, long time) {
        if (type == null || type.isBlank()) {
            return;
        }
        events.addLast(new Event(type, x, z, time));
        while (events.size() > capacity) {
            forgetOne();
        }
    }

    /**
     * Lleno: olvida el evento más viejo del tipo más repetido, para que una granja que mata cientos de
     * vacas no borre lo poco que pasó en otro lado.
     */
    private void forgetOne() {
        Map<String, Integer> counts = new HashMap<>();
        String most = null;
        for (Event e : events) {
            int n = counts.merge(e.type, 1, Integer::sum);
            if (most == null || n > counts.get(most)) {
                most = e.type;
            }
        }
        for (Iterator<Event> it = events.iterator(); it.hasNext(); ) {
            if (it.next().type.equals(most)) {
                it.remove();
                return;
            }
        }
    }

    /** Eventos del tipo dados a {@code radius} bloques o menos de (x, z), ocurridos desde {@code since}. */
    public int count(String type, int x, int z, int radius, long since) {
        long r2 = (long) radius * radius;
        int n = 0;
        for (Event e : events) {
            if (e.time >= since && e.type.equals(type) && distance2(e, x, z) <= r2) {
                n++;
            }
        }
        return n;
    }

    public List<Event> find(String type, int x, int z, int radius, long since) {
        long r2 = (long) radius * radius;
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.time >= since && e.type.equals(type) && distance2(e, x, z) <= r2) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(e -> distance2(e, x, z)));
        return out;
    }

    private static long distance2(Event e, int x, int z) {
        long dx = (long) e.x - x;
        long dz = (long) e.z - z;
        return dx * dx + dz * dz;
    }

    /** Olvida lo viejo. Un evento "del futuro" (el tiempo del mundo retrocedió) pasa a ser de ahora. */
    public void prune(long now) {
        long oldest = Time.ago(now, maxAge);
        List<Event> kept = new ArrayList<>(events.size());
        for (Event e : events) {
            if (e.time > now) {
                kept.add(new Event(e.type, e.x, e.z, now));
            } else if (e.time >= oldest) {
                kept.add(e);
            }
        }
        events.clear();
        events.addAll(kept);
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public void load(Iterable<Event> saved, long now) {
        events.clear();
        List<Event> valid = new ArrayList<>();
        if (saved != null) {
            for (Event e : saved) {
                if (e != null && e.type != null && !e.type.isBlank()) {
                    valid.add(e);
                }
            }
        }
        valid.sort(Comparator.comparingLong(Event::time));
        // nunca se guarda más que la capacidad; más que esto es un archivo roto y olvidar de a uno congelaría
        for (Event e : valid.subList(Math.max(0, valid.size() - LOAD_LIMIT * capacity), valid.size())) {
            events.addLast(e);
            if (events.size() > capacity) {
                forgetOne();
            }
        }
        prune(now);
    }

    public int size() {
        return events.size();
    }
}
