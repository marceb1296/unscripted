package dev.unscripted.core;

/** Sumas y restas de ticks que saturan en vez de desbordar. */
public final class Time {
    private Time() {
    }

    public static long after(long now, long delay) {
        long r = now + delay;
        if (delay > 0 && r < now) {
            return Long.MAX_VALUE;
        }
        return r;
    }

    public static long ago(long now, long delay) {
        long r = now - delay;
        if (delay > 0 && r > now) {
            return Long.MIN_VALUE;
        }
        return r;
    }
}
