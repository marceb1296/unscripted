package dev.unscripted.core;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class Timings {
    private static final class Entry {
        long total;
        long max;
        long calls;
    }

    private final Map<String, Entry> entries = new TreeMap<>();
    private long ticks;

    public void tick() {
        ticks++;
    }

    /** Un reloj que retrocede no resta: cuenta 0. Las sumas saturan en vez de desbordar. */
    public void add(String what, long nanos) {
        long n = Math.max(0, nanos);
        Entry e = entries.computeIfAbsent(what == null ? "?" : what, k -> new Entry());
        e.total = e.total > Long.MAX_VALUE - n ? Long.MAX_VALUE : e.total + n;
        e.max = Math.max(e.max, n);
        e.calls++;
    }

    public long ticks() {
        return ticks;
    }

    public double perTick(String what) {
        Entry e = entries.get(what);
        return e == null || ticks == 0 ? 0 : e.total / 1e6 / ticks;
    }

    public double maxMs(String what) {
        Entry e = entries.get(what);
        return e == null ? 0 : e.max / 1e6;
    }

    public String report() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%d ticks", ticks));
        entries.forEach((what, e) -> out.append(String.format(Locale.ROOT, "\n%s: %.3f ms/tick, max %.2f ms, %d calls",
                what, perTick(what), e.max / 1e6, e.calls)));
        return out.toString();
    }

    public void reset() {
        entries.clear();
        ticks = 0;
    }
}
