package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DirectorTest {
    static final Settings FAST = new Settings(100, 200, 128, 0.25, 0.4f, 0.3, 512, 1_000_000, 1);

    static Director director(Scene... scenes) {
        return new Director(FAST, List.of(scenes), new Memory(512, 1_000_000));
    }

    /** Primera llamada: registra al jugador; devuelve un tiempo en el que ya puede tocarle escena. */
    static long warm(Director d, String player, long t) {
        d.choose(player, new Ctx().at(t).build(), () -> 0.5);
        return t + FAST.maxGap();
    }

    @Test
    void noSceneRightAfterJoining() {
        Director d = director(new FixedScene("a", 1));
        assertTrue(d.choose("p", new Ctx().at(1000).build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", new Ctx().at(1099).build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", new Ctx().at(1100).build(), () -> 0.0).isPresent());
    }

    @Test
    void brokenWeightsAreNeverChosen() {
        Scene good = new FixedScene("good", 1);
        Director d = director(new FixedScene("nan", Double.NaN), new FixedScene("inf", Double.POSITIVE_INFINITY),
                new FixedScene("neg", -5), good, new FixedScene("ninf", Double.NEGATIVE_INFINITY));
        long t = warm(d, "p", 0);
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertEquals(Optional.of(good), d.choose("p", new Ctx().at(t).build(), r::nextDouble));
        }
    }

    @Test
    void onlyBrokenWeightsMeansNothing() {
        Director d = director(new FixedScene("nan", Double.NaN), new FixedScene("inf", Double.POSITIVE_INFINITY));
        long t = warm(d, "p", 0);
        assertTrue(d.choose("p", new Ctx().at(t).build(), () -> 0.3).isEmpty());
    }

    @Test
    void hugeWeightsDoNotOverflowTheTotal() {
        Scene a = new FixedScene("a", 1e308);
        Scene b = new FixedScene("b", 1e308);
        Director d = director(a, b);
        long t = warm(d, "p", 0);
        assertEquals(Optional.of(a), d.choose("p", new Ctx().at(t).build(), () -> 0.1));
        assertEquals(Optional.of(b), d.choose("p", new Ctx().at(t).build(), () -> 0.9));
    }

    @Test
    void roundingAtTheTopNeverPicksAZeroWeightScene() {
        // 0.1 + 0.2 + 0.3 en double: con el azar más alto, restar los pesos no baja de 0
        Scene c = new FixedScene("c", 0.3);
        Director d = director(new FixedScene("a", 0.1), new FixedScene("b", 0.2), c, new FixedScene("zero", 0));
        long t = warm(d, "p", 0);
        double top = Math.nextDown(1.0);
        assertEquals(Optional.of(c), d.choose("p", new Ctx().at(t).build(), () -> top));
    }

    @Test
    void rngReturningOneStillPicksARealScene() {
        Scene b = new FixedScene("b", 0.2);
        Director d = director(new FixedScene("a", 0.1), b, new FixedScene("zero", 0));
        long t = warm(d, "p", 0);
        assertEquals(Optional.of(b), d.choose("p", new Ctx().at(t).build(), () -> 1.0));
    }

    @Test
    void drawFollowsWeights() {
        Scene a = new FixedScene("a", 1);
        Scene b = new FixedScene("b", 3);
        Director d = director(a, b);
        long t = warm(d, "p", 0);
        Random r = new Random(42);
        int as = 0;
        for (int i = 0; i < 20_000; i++) {
            if (d.choose("p", new Ctx().at(t).build(), r::nextDouble).orElseThrow() == a) {
                as++;
            }
        }
        assertEquals(0.25, as / 20_000.0, 0.015);
    }

    @Test
    void cooldownPerPlayerThenAvailableAgain() {
        Scene a = new FixedScene("a", 1, 5000, 1);
        Director d = director(a);
        long t = warm(d, "p", 0);
        Context c = new Ctx().at(t).build();
        d.started("p", a, c, () -> 0.0);
        // lejos del lugar anterior para medir solo el enfriamiento del jugador
        assertTrue(d.choose("p", new Ctx().at(t + 4999).pos(10_000, 0).build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", new Ctx().at(t + 5000).pos(10_000, 0).build(), () -> 0.0).isPresent());
    }

    @Test
    void cooldownScaleShortensPlayerAndNearbyCooldowns() {
        Settings quick = new Settings(100, 200, 128, 0.25, 0.4f, 0.3, 512, 1_000_000, 0.05);
        Scene a = new FixedScene("a", 1, 24000, 1);
        Director d = new Director(quick, List.of(a), new Memory(512, 1_000_000));
        long t = warm(d, "p", 0);
        d.started("p", a, new Ctx().at(t).build(), () -> 0.0);
        // mismo lugar: cuentan el enfriamiento del jugador y el de la escena cercana, los dos escalados
        assertTrue(d.choose("p", new Ctx().at(t + 1199).build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", new Ctx().at(t + 1200).build(), () -> 0.0).isPresent());
    }

    @Test
    void gapAfterStartedBlocksEverything() {
        Scene a = new FixedScene("a", 1, 0, 1);
        Director d = director(a);
        long t = warm(d, "p", 0);
        d.started("p", a, new Ctx().at(t).build(), () -> 0.0);
        assertTrue(d.choose("p", new Ctx().at(t + 99).pos(10_000, 0).build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", new Ctx().at(t + 100).pos(10_000, 0).build(), () -> 0.0).isPresent());
    }

    @Test
    void sameSceneIsNotRepeatedNearAnotherPlayer() {
        Scene a = new FixedScene("a", 1, 5000, 1);
        Director d = director(a);
        long t = warm(d, "p1", 0);
        warm(d, "p2", 0);
        d.started("p1", a, new Ctx().at(t).build(), () -> 0.0);
        assertTrue(d.choose("p2", new Ctx().at(t + 10).pos(50, 50).build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p2", new Ctx().at(t + 10).pos(500, 0).build(), () -> 0.0).isPresent());
        assertTrue(d.choose("p2", new Ctx().at(t + 5000).pos(50, 50).build(), () -> 0.0).isPresent());
    }

    @Test
    void notUndergroundNorOtherDimensionsNorFighting() {
        Director d = director(new FixedScene("a", 1));
        long t = warm(d, "p", 0);
        Ctx under = new Ctx().at(t);
        under.underground = true;
        Ctx nether = new Ctx().at(t);
        nether.overworld = false;
        Ctx fight = new Ctx().at(t);
        fight.activity = Activity.FIGHTING;
        assertTrue(d.choose("p", under.build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", nether.build(), () -> 0.0).isEmpty());
        assertTrue(d.choose("p", fight.build(), () -> 0.0).isEmpty());
    }

    @Test
    void worldTimeGoingBackDoesNotBlockForever() {
        Scene a = new FixedScene("a", 1, 5000, 1);
        Director d = director(a);
        d.choose("p", new Ctx().at(1_000_000).build(), () -> 0.5);
        d.started("p", a, new Ctx().at(1_000_000).build(), () -> 0.0);
        // el servidor carga una copia vieja del mundo: el tiempo vuelve a 0
        d.memory().prune(0);
        boolean seen = false;
        for (long t = 0; t <= 20_000 && !seen; t += 100) {
            d.memory().prune(t);
            seen = d.choose("p", new Ctx().at(t).build(), () -> 0.0).isPresent();
        }
        assertTrue(seen);
    }

    @Test
    void enormousGapSettingDoesNotOverflowIntoConstantScenes() {
        Settings huge = new Settings(0, Long.MAX_VALUE, 128, 0.25, 0.4f, 0.3, 512, 1000, 1);
        Scene a = new FixedScene("a", 3, 0, 1);
        Director d = new Director(huge, List.of(a), new Memory(512, 1000));
        d.started("p", a, new Ctx().at(10).build(), () -> 0.9999);
        assertTrue(d.choose("p", new Ctx().at(11).pos(9999, 0).build(), () -> 0.0).isEmpty());
    }

    @Test
    void tenseSceneLessLikelyRightAfterAnotherTenseOne() {
        Scene calm = new FixedScene("calm", 1, 0, 1);
        Scene tense = new FixedScene("tense", 2, 0, 1);
        Scene danger = new FixedScene("danger", 3, 0, 1);
        Director d = director(calm, tense, danger);
        long t = warm(d, "p", 0);
        d.started("p", danger, new Ctx().at(t).pos(9999, 0).build(), () -> 0.0);
        long later = t + 1000;
        // pesos efectivos: calm 1, tense 0.25, danger 0.25: r = 0.7 de 1.5 = 1.05 cae en tense
        assertEquals(Optional.of(calm), d.choose("p", new Ctx().at(later).build(), () -> 0.66));
        assertEquals(Optional.of(tense), d.choose("p", new Ctx().at(later).build(), () -> 0.7));
        d.started("p", calm, new Ctx().at(later).pos(-9999, 0).build(), () -> 0.0);
        long after = later + 1000;
        // después de una tranquila, todas pesan igual: 0.5 de 3 = 1.5 cae en tense
        assertEquals(Optional.of(tense), d.choose("p", new Ctx().at(after).pos(0, 9999).build(), () -> 0.5));
    }

    @Test
    void lowHealthMakesTenseScenesRarer() {
        Scene calm = new FixedScene("calm", 1, 0, 1);
        Scene tense = new FixedScene("tense", 2, 0, 1);
        Director d = director(calm, tense);
        long t = warm(d, "p", 0);
        Ctx hurt = new Ctx().at(t);
        hurt.health = 0.2f;
        // pesos efectivos 1 y 0.3: 0.75 de 1.3 = 0.975 sigue en calm
        assertEquals(Optional.of(calm), d.choose("p", hurt.build(), () -> 0.75));
        assertEquals(Optional.of(tense), d.choose("p", new Ctx().at(t).build(), () -> 0.75));
    }

    @Test
    void snapshotRoundTripKeepsCooldownsAndWait() {
        Scene a = new FixedScene("a", 1, 5000, 1);
        Director d = director(a);
        long t = warm(d, "p", 0);
        d.started("p", a, new Ctx().at(t).build(), () -> 0.0);
        Director again = director(a);
        again.restore(d.snapshot());
        assertEquals(100, again.waitFor("p", t));
        assertTrue(again.choose("p", new Ctx().at(t + 4000).pos(10_000, 0).build(), () -> 0.0).isEmpty());
        assertTrue(again.choose("p", new Ctx().at(t + 5000).pos(10_000, 0).build(), () -> 0.0).isPresent());
    }

    @Test
    void restoreSkipsGarbage() {
        Map<String, Long> scenes = new HashMap<>();
        scenes.put(null, 5L);
        scenes.put("a", null);
        Map<String, Director.PlayerSnapshot> saved = new HashMap<>();
        saved.put(null, new Director.PlayerSnapshot(0, 1, Map.of()));
        saved.put("nulo", null);
        saved.put("p", new Director.PlayerSnapshot(50, 1, scenes));
        Director d = director(new FixedScene("a", 1));
        d.restore(saved);
        assertEquals(Map.of("p", new Director.PlayerSnapshot(50, 1, Map.of())), d.snapshot());
        d.restore(null);
        assertTrue(d.snapshot().isEmpty());
    }

    @Test
    void weightsReportDoesNotRegisterThePlayer() {
        Director d = director(new FixedScene("a", 2), new FixedScene("b", Double.NaN));
        assertEquals(Map.of("a", 2.0, "b", 0.0), d.weights("p", new Ctx().build()));
        assertEquals(-1, d.waitFor("p", 0));
    }
}
