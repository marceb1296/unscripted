package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unscripted.core.scene.WanderingHorde;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ConfigTest {
    static Config with(Map<String, Boolean> on, Map<String, Double> weights) {
        Config d = Config.DEFAULT;
        return new Config(true, on, weights, d.minMinutes(), d.maxMinutes(), d.distanceMin(), d.distanceMax(),
                d.horde(), true, true, true, d.maxActiveScenes(), d.maxSceneMobs(), d.msptLimit());
    }

    static Config limits(boolean enabled, int maxActive, int maxMobs, double mspt) {
        Config d = Config.DEFAULT;
        return new Config(enabled, Map.of(), Map.of(), d.minMinutes(), d.maxMinutes(), d.distanceMin(),
                d.distanceMax(), d.horde(), true, true, true, maxActive, maxMobs, mspt);
    }

    static Settings fast(Settings s) {
        return s.withPacing(DirectorTest.FAST.minGap(), DirectorTest.FAST.maxGap(), 1);
    }

    @Test
    void defaultsMatchTheTunedGame() {
        Settings s = Config.DEFAULT.settings(Settings.DEFAULT, List.of("a"));
        assertEquals(Settings.DEFAULT.minGap(), s.minGap());
        assertEquals(Settings.DEFAULT.maxGap(), s.maxGap());
        assertEquals(1, s.weight("a"));
        assertEquals(WanderingHorde.Size.DEFAULT, Config.DEFAULT.horde());
    }

    @Test
    void handEditedFileWithBrokenValuesIsRepaired() {
        Map<String, Boolean> on = new HashMap<>();
        on.put(null, false);
        on.put("x", null);
        Map<String, Double> w = new HashMap<>();
        w.put("nan", Double.NaN);
        w.put("neg", -3.0);
        w.put("big", 1e300);
        w.put(null, 2.0);
        w.put("null", null);
        Config c = new Config(true, on, w, Double.NaN, -50, 1000, -1000, null, true, true, true, -4, Integer.MAX_VALUE,
                Double.NaN);
        assertEquals(1, c.weight("nan"));
        assertEquals(0, c.weight("neg"));
        assertEquals(Settings.MAX_WEIGHT, c.weight("big"));
        assertEquals(1, c.weight("null"));
        assertEquals(1, c.weight("x"));
        // NaN vuelve al de fábrica (5); -50 sube al mínimo y quedan en orden
        assertEquals(Config.MIN_MINUTES, c.minMinutes());
        assertEquals(Config.DEFAULT_MIN_MINUTES, c.maxMinutes());
        assertEquals(Config.MIN_DISTANCE, c.distanceMin());
        assertEquals(Config.MAX_DISTANCE, c.distanceMax());
        assertEquals(WanderingHorde.Size.DEFAULT, c.horde());
        assertEquals(0, c.maxActiveScenes());
        assertEquals(Config.MAX_MOBS, c.maxSceneMobs());
        assertEquals(Config.DEFAULT_MSPT, c.msptLimit());
    }

    @Test
    void hugeMinutesDoNotOverflowTheGaps() {
        Config d = Config.DEFAULT;
        Config c = new Config(true, Map.of(), Map.of(), Double.POSITIVE_INFINITY, 1e300, 24, 44, d.horde(), true, true,
                true, 8, 120, 45);
        Settings s = c.settings(Settings.DEFAULT, List.of());
        assertEquals((long) (Config.MAX_MINUTES * 1200), s.minGap());
        assertEquals(s.minGap(), s.maxGap());
        assertTrue(s.maxGap() > 0);
    }

    @Test
    void settingsRepairBrokenWeightsOnTheirOwn() {
        Map<String, Double> w = new HashMap<>();
        w.put("nan", Double.NaN);
        w.put("neg", -1.0);
        w.put("big", 1e9);
        w.put("null", null);
        w.put(null, 3.0);
        Settings s = new Settings(1, 2, 0, 1, 0, 1, 1, 0, 1, w);
        assertEquals(1, s.weight("nan"));
        assertEquals(0, s.weight("neg"));
        assertEquals(Settings.MAX_WEIGHT, s.weight("big"));
        assertEquals(1, s.weight("null"));
        assertEquals(1, s.weight("missing"));
        assertEquals(1, new Settings(1, 2, 0, 1, 0, 1, 1, 0, 1, null).weight("x"));
    }

    @Test
    void msptLimitIsOffAtZeroAndBoundedOtherwise() {
        assertEquals(0, limits(true, 8, 120, 0).msptLimit());
        assertEquals(0, limits(true, 8, 120, -5).msptLimit());
        assertEquals(0, limits(true, 8, 120, Double.NEGATIVE_INFINITY).msptLimit());
        assertEquals(Config.MIN_MSPT, limits(true, 8, 120, 0.001).msptLimit());
        assertEquals(Config.MAX_MSPT, limits(true, 8, 120, Double.POSITIVE_INFINITY).msptLimit());
    }

    @Test
    void disabledSceneIsNeverChosenWhateverItsWeight() {
        Scene a = new FixedScene("a", 1);
        Scene b = new FixedScene("b", 1);
        Config c = with(Map.of("a", false), Map.of("a", 5.0, "b", 0.5));
        assertEquals(0, c.weight("a"));
        Director d = new Director(fast(c.settings(Settings.DEFAULT, List.of("a", "b"))), List.of(a, b), new Memory(512, 1_000_000));
        long t = DirectorTest.warm(d, "p", 0);
        Random r = new Random(3);
        for (int i = 0; i < 300; i++) {
            assertEquals(Optional.of(b), d.choose("p", new Ctx().at(t).build(), r::nextDouble));
        }
    }

    @Test
    void zeroWeightTurnsASceneOffAndOthersScaleTheDraw() {
        Scene a = new FixedScene("a", 1);
        Scene b = new FixedScene("b", 1);
        Scene c = new FixedScene("c", 1);
        Settings s = with(Map.of(), Map.of("a", 0.0, "b", 3.0)).settings(Settings.DEFAULT, List.of("a", "b", "c"));
        s = fast(s);
        Director d = new Director(s, List.of(a, b, c), new Memory(512, 1_000_000));
        long t = DirectorTest.warm(d, "p", 0);
        Map<String, Double> w = d.weights("p", new Ctx().at(t).build());
        assertEquals(0, w.get("a"));
        assertEquals(3, w.get("b"));
        assertEquals(1, w.get("c"));
        int bCount = 0;
        Random r = new Random(9);
        for (int i = 0; i < 4000; i++) {
            Scene got = d.choose("p", new Ctx().at(t).build(), r::nextDouble).orElseThrow();
            assertTrue(got != a);
            bCount += got == b ? 1 : 0;
        }
        assertEquals(0.75, bCount / 4000.0, 0.03);
    }

    @Test
    void configWeightDoesNotPushAHugeSceneWeightToInfinity() {
        // una escena de peso enorme (otro paquete de datos) por 5 desborda a infinito y quedaba descartada
        Scene a = new FixedScene("a", 1e308);
        Scene b = new FixedScene("b", 1);
        Settings s = with(Map.of(), Map.of("a", 5.0)).settings(Settings.DEFAULT, List.of("a", "b"));
        s = fast(s);
        Director d = new Director(s, List.of(a, b), new Memory(512, 1_000_000));
        long t = DirectorTest.warm(d, "p", 0);
        assertEquals(Optional.of(a), d.choose("p", new Ctx().at(t).build(), () -> 0.5));
    }

    @Test
    void limitsBlockNewScenesForEachReason() {
        Config c = limits(true, 8, 120, 45);
        assertNull(Limits.blocked(c, 7, 100, 20, 44.9));
        assertNotNull(Limits.blocked(limits(false, 8, 120, 45), 0, 0, 1, 1));
        assertNotNull(Limits.blocked(c, 8, 0, 1, 1));
        assertNotNull(Limits.blocked(c, 0, 100, 21, 1));
        assertNotNull(Limits.blocked(c, 0, 0, 1, 45.1));
        // sin tope de tick: nada lo bloquea por lento; una medición rota (NaN) tampoco bloquea
        assertNull(Limits.blocked(limits(true, 8, 120, 0), 0, 0, 1, 10_000));
        assertNull(Limits.blocked(c, 0, 0, 1, Double.NaN));
        assertNotNull(Limits.blocked(c, 0, 0, 1, Double.POSITIVE_INFINITY));
        // sumas que desbordan un int no dejan pasar
        assertNotNull(Limits.blocked(c, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertNull(Limits.blocked(c, 0, -50, 120, 1));
        // un conteo negativo (escena rota) no abre lugar de más
        assertNotNull(Limits.blocked(c, 0, -50, 150, 1));
        // justo en el tope de tick todavía se puede
        assertNull(Limits.blocked(c, 0, 0, 1, 45.0));
        assertNotNull(Limits.blocked(limits(true, 0, 120, 45), 0, 0, 0, 1));
    }

    @Test
    void roomForWavesNeverGoesNegative() {
        Config c = limits(true, 8, 120, 45);
        assertEquals(20, Limits.room(c, 100));
        assertEquals(0, Limits.room(c, 500));
        assertEquals(120, Limits.room(c, -7));
        assertEquals(0, Limits.room(c, Integer.MAX_VALUE));
    }
}
