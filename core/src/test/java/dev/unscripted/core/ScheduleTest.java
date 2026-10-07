package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduleTest {
    static List<String> players(int n, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new UUID(r.nextLong(), r.nextLong()).toString());
        }
        return out;
    }

    /** Corre el reparto y devuelve en qué ticks se consultó a cada jugador; falla si un tick pasa el tope. */
    static Map<String, List<Long>> run(Schedule s, List<String> online, long from, long ticks, int budget) {
        Map<String, List<Long>> seen = new HashMap<>();
        for (long t = from; t < from + ticks; t++) {
            List<String> due = s.due(t, online);
            assertTrue(due.size() <= budget, "tick " + t + ": " + due.size());
            for (String id : due) {
                seen.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
            }
        }
        return seen;
    }

    @Test
    void everyPlayerOncePerIntervalAndNeverMoreThanTheBudgetPerTick() {
        List<String> online = players(700, 1);
        Map<String, List<Long>> seen = run(new Schedule(400, 2), online, 0, 4000, 2);
        assertEquals(online.size(), seen.size());
        for (List<Long> times : seen.values()) {
            assertTrue(times.size() >= 9 && times.size() <= 10, "veces " + times.size());
            for (int i = 1; i < times.size(); i++) {
                long gap = times.get(i) - times.get(i - 1);
                assertTrue(gap > 300 && gap < 500, "espera " + gap);
            }
        }
    }

    @Test
    void playersWithTheSameHashWaitInLineInsteadOfBeingDropped() {
        // "Aa" y "BB" tienen el mismo hashCode: 64 jugadores en el mismo turno
        List<String> same = new ArrayList<>(List.of(""));
        for (int i = 0; i < 6; i++) {
            List<String> next = new ArrayList<>();
            for (String s : same) {
                next.add(s + "Aa");
                next.add(s + "BB");
            }
            same = next;
        }
        assertEquals(1, same.stream().map(String::hashCode).distinct().count());
        Schedule s = new Schedule(400, 2);
        Map<String, List<Long>> seen = run(s, same, Math.floorMod(same.get(0).hashCode(), 400), 400, 2);
        assertEquals(64, seen.size());
        long last = seen.values().stream().mapToLong(t -> t.get(0)).max().orElseThrow();
        long first = seen.values().stream().mapToLong(t -> t.get(0)).min().orElseThrow();
        assertEquals(31, last - first);
        assertTrue(seen.values().stream().allMatch(t -> t.size() == 1));
        assertEquals(0, s.waiting());
    }

    @Test
    void worksWithNegativeAndHugeClocks() {
        List<String> online = players(50, 2);
        // unos ticks de más por si dos comparten el último turno
        assertEquals(50, run(new Schedule(400, 2), online, -10_000, 450, 2).size());
        assertEquals(50, run(new Schedule(400, 2), online, Long.MAX_VALUE - 451, 450, 2).size());
        assertEquals(50, run(new Schedule(400, 2), online, Long.MIN_VALUE, 450, 2).size());
    }

    @Test
    void disconnectedPlayersLeaveTheLine() {
        List<String> same = List.of("AaAa", "AaBB", "BBAa", "BBBB");
        Schedule s = new Schedule(10, 1);
        long slot = Math.floorMod("AaAa".hashCode(), 10);
        assertEquals(List.of("AaAa"), s.due(slot, same));
        // se van dos de los que esperaban
        List<String> after = List.of("AaAa", "BBBB");
        assertEquals(List.of("BBBB"), s.due(slot + 1, after));
        assertEquals(List.of(), s.due(slot + 2, after));
        assertEquals(0, s.waiting());
    }

    @Test
    void strangeSettingsAndInputsDoNotStopTheDirector() {
        List<String> online = new ArrayList<>(Arrays.asList("a", null, "b"));
        Map<String, List<Long>> seen = run(new Schedule(0, 0), online, 0, 5, 1);
        // intervalo y tope imposibles quedan en 1: uno por tick, por turnos
        assertEquals(5, seen.get("a").size() + seen.get("b").size());
        assertTrue(seen.get("a").size() >= 2 && seen.get("b").size() >= 2);
        assertEquals(2, run(new Schedule(-7, -3), online, 0, 10, 1).size());
        assertEquals(List.of(), new Schedule(400, 2).due(0, List.of()));
    }
}
