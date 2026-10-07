package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class SettingsAndPlacementTest {
    @Test
    void settingsRepairHostileConfig() {
        Settings s = new Settings(500, 100, -1, Double.NaN, Float.NaN, -2, 0, -5, Double.NaN);
        assertEquals(100, s.minGap());
        assertEquals(500, s.maxGap());
        assertEquals(0, s.nearbyRadius());
        assertEquals(1, s.tenseFollowup());
        assertEquals(0, s.lowHealthFactor());
        assertEquals(1, s.memoryCapacity());
        assertEquals(0, s.memoryMaxAge());
        assertEquals(1, s.cooldownScale());
        assertEquals(1, new Settings(0, 0, 0, 0, 0, 0, 1, 0, -0.5).cooldownScale());
        assertEquals(1, new Settings(0, 0, 0, 0, 0, 0, 1, 0, Double.POSITIVE_INFINITY).cooldownScale());
    }

    @Test
    void placementStaysInTheRingAndCoversAllSides() {
        Random r = new Random(7);
        int[] quadrants = new int[4];
        for (Placement.Offset o : Placement.candidates(r::nextDouble, 30, 60, 2000)) {
            double d = Math.hypot(o.dx(), o.dz());
            assertTrue(d >= 29 && d <= 61, "fuera del anillo: " + o);
            quadrants[(o.dx() >= 0 ? 0 : 1) + (o.dz() >= 0 ? 0 : 2)]++;
        }
        for (int q : quadrants) {
            assertTrue(q > 400, "cuadrante con pocas posiciones: " + q);
        }
    }

    @Test
    void placementIsEvenOverTheRingArea() {
        // parejo por área: entre 30 y 45 cae (45² - 30²) / (60² - 30²) = 41.7 %; lineal por distancia daría 50 %
        Random r = new Random(11);
        int inner = 0;
        int n = 20_000;
        for (Placement.Offset o : Placement.candidates(r::nextDouble, 30, 60, n)) {
            if (Math.hypot(o.dx(), o.dz()) < 45) {
                inner++;
            }
        }
        assertEquals(0.417, inner / (double) n, 0.02);
    }

    @Test
    void placementWithSwappedOrNegativeBounds() {
        for (Placement.Offset o : Placement.candidates(() -> 0.99, 50, 10, 5)) {
            assertEquals(50, Math.hypot(o.dx(), o.dz()), 1);
        }
        assertTrue(Placement.candidates(() -> 0.5, 10, 20, -3).isEmpty());
    }

    @Test
    void textsInThePlayersLanguage() {
        assertEquals("hola", Lang.pick("es_mx", "hi", "hola"));
        assertEquals("hola", Lang.pick("es_ES", "hi", "hola"));
        assertEquals("hola", Lang.pick("ES_AR", "hi", "hola"));
        assertEquals("hi", Lang.pick("en_us", "hi", "hola"));
        // un cliente que no informa idioma, o uno raro, recibe inglés
        assertEquals("hi", Lang.pick(null, "hi", "hola"));
        assertEquals("hi", Lang.pick("", "hi", "hola"));
        assertEquals("hi", Lang.pick("pt_br", "hi", "hola"));
        // vasco de España: lleva "es" pero no es español
        assertEquals("hi", Lang.pick("eu_es", "hi", "hola"));
    }
}
