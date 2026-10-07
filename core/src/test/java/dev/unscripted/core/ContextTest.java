package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextTest {
    @Test
    void normalizesHostileValues() {
        Map<String, Integer> mobs = new HashMap<>();
        mobs.put("minecraft:sheep", -4);
        Context c = new Context(0, -1000, -1, null, null, true, false, null, mobs, null, Float.NaN, -3, 0, 0, -10);
        assertEquals(23000, c.dayTime());
        assertEquals(7, c.moonPhase());
        assertEquals(1f, c.health());
        assertEquals(0, c.armor());
        assertEquals(0, c.count("minecraft:sheep"));
        assertEquals(0, c.villageDistance());
        assertTrue(c.terrain().isEmpty());
        assertEquals(Weather.CLEAR, c.weather());
        assertEquals(Activity.IDLE, c.activity());
    }

    @Test
    void mobMapIsCopied() {
        Map<String, Integer> mobs = new HashMap<>();
        Ctx b = new Ctx();
        b.mobs = mobs;
        Context c = b.build();
        mobs.put("minecraft:sheep", 9);
        assertEquals(0, c.count("minecraft:sheep"));
    }

    @Test
    void dayParts() {
        Ctx b = new Ctx();
        b.dayTime = 12500;
        assertTrue(b.build().isDusk());
        b.dayTime = 13000;
        assertTrue(b.build().isNight());
        b.dayTime = 23000;
        assertTrue(b.build().isDay());
        b.dayTime = 24000 * 5 + 18000;
        assertTrue(b.build().isNight());
    }
}
