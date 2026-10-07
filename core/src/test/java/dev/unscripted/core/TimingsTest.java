package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimingsTest {
    @Test
    void averagesPerTickAndKeepsTheWorstCall() {
        Timings t = new Timings();
        for (int i = 0; i < 4; i++) {
            t.tick();
        }
        t.add("a", 1_000_000);
        t.add("a", 3_000_000);
        assertEquals(1.0, t.perTick("a"), 1e-9);
        assertEquals(3.0, t.maxMs("a"), 1e-9);
        assertEquals(0, t.perTick("missing"));
        assertTrue(t.report().contains("a: 1.000 ms/tick, max 3.00 ms, 2 calls"));
    }

    @Test
    void clockGoingBackwardsOrHugeValuesDoNotBreakTheSums() {
        Timings t = new Timings();
        t.tick();
        t.add("a", -5_000_000);
        assertEquals(0, t.perTick("a"));
        t.add("a", Long.MAX_VALUE);
        t.add("a", Long.MAX_VALUE);
        assertTrue(t.perTick("a") > 0);
        t.add(null, 10);
        assertTrue(t.report().contains("?:"));
        t.reset();
        assertEquals(0, t.ticks());
        assertEquals(0, t.perTick("a"));
    }

    @Test
    void noTicksMeansNoDivisionByZero() {
        Timings t = new Timings();
        t.add("a", 5);
        assertEquals(0, t.perTick("a"));
        assertTrue(t.report().startsWith("0 ticks"));
    }
}
