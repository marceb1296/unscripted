package dev.unscripted.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryTest {
    @Test
    void distanceDoesNotOverflowWithFarCoordinates() {
        Memory m = new Memory(16, 100_000);
        // con aritmética int, 65536² = 2³² se vuelve 0 y el evento parece estar encima
        m.add("x", 65536, 0, 10);
        assertEquals(0, m.count("x", 0, 0, 10, 0));
        m.add("x", 29_999_000, -29_999_000, 10);
        assertEquals(0, m.count("x", -29_999_000, 29_999_000, 1000, 0));
        assertEquals(1, m.count("x", 29_998_990, -29_999_000, 10, 0));
    }

    @Test
    void capacityDropsOldest() {
        Memory m = new Memory(100, Long.MAX_VALUE);
        for (int i = 0; i < 250; i++) {
            m.add("x", 0, 0, i);
        }
        assertEquals(100, m.size());
        assertEquals(150, m.events().get(0).time());
        assertEquals(100, m.count("x", 0, 0, 1, 0));
    }

    @Test
    void pruneForgetsOldAndClampsFuture() {
        Memory m = new Memory(16, 1000);
        m.add("old", 0, 0, 100);
        m.add("recent", 0, 0, 4500);
        m.add("future", 0, 0, 900_000);
        m.prune(5000);
        assertEquals(0, m.count("old", 0, 0, 1, Long.MIN_VALUE));
        assertEquals(1, m.count("recent", 0, 0, 1, Long.MIN_VALUE));
        // el mundo volvió atrás: el evento sigue, pero ya no queda en el futuro para siempre
        assertEquals(5000, m.events().stream().filter(e -> e.type().equals("future")).findFirst().orElseThrow().time());
        m.prune(7000);
        assertEquals(0, m.count("future", 0, 0, 1, Long.MIN_VALUE));
    }

    @Test
    void loadSkipsGarbageAndKeepsNewest() {
        Memory m = new Memory(3, 10_000);
        List<Memory.Event> saved = new ArrayList<>(Arrays.asList(
                null,
                new Memory.Event(null, 0, 0, 10),
                new Memory.Event(" ", 0, 0, 10),
                new Memory.Event("d", 0, 0, 900),
                new Memory.Event("a", 0, 0, 600),
                new Memory.Event("c", 0, 0, 800),
                new Memory.Event("b", 0, 0, 700)));
        m.load(saved, 1000);
        assertEquals(List.of("b", "c", "d"), m.events().stream().map(Memory.Event::type).toList());
        m.load(null, 1000);
        assertEquals(0, m.size());
    }

    @Test
    void blankTypesAreIgnored() {
        Memory m = new Memory(4, 100);
        m.add(null, 0, 0, 0);
        m.add("", 0, 0, 0);
        assertTrue(m.events().isEmpty());
    }

    @Test
    void aFloodOfOneTypeDoesNotEraseRareEvents() {
        // una granja automática de vacas anota cientos de muertes; lo poco que pasó en otro lado debe quedar
        Memory m = new Memory(512, Long.MAX_VALUE);
        m.add("trader_saved", 300, 300, 0);
        m.add("village_damaged", -300, 0, 1);
        for (int i = 0; i < 5000; i++) {
            m.add("big_death", 0, 0, 10 + i);
        }
        assertEquals(512, m.size());
        assertEquals(1, m.count("trader_saved", 300, 300, 1, 0));
        assertEquals(1, m.count("village_damaged", -300, 0, 1, 0));
        // del tipo que inunda se olvidan los más viejos
        assertEquals(510, m.count("big_death", 0, 0, 1, 5010 - 510));
    }

    @Test
    void loadingAFloodedSaveWithLessCapacityKeepsRareEvents() {
        // guardado con una capacidad mayor que la de ahora
        Memory m = new Memory(8, Long.MAX_VALUE);
        List<Memory.Event> saved = new ArrayList<>();
        saved.add(new Memory.Event("trader_saved", 0, 0, 5));
        for (int i = 0; i < 20; i++) {
            saved.add(new Memory.Event("big_death", 0, 0, 100 + i));
        }
        m.load(saved, 1000);
        assertEquals(8, m.size());
        assertEquals(1, m.count("trader_saved", 0, 0, 1, 0));
        assertEquals(7, m.count("big_death", 0, 0, 1, 113));
    }

    @Test
    void findSortsByDistanceAndFilters() {
        Memory m = new Memory(16, Long.MAX_VALUE);
        m.add("d", 50, 0, 100);
        m.add("d", 10, 0, 100);
        m.add("d", 30, 0, 100);
        m.add("d", 20, 0, 5);
        m.add("other", 1, 0, 100);
        m.add("d", 97, 0, 100);
        List<Memory.Event> found = m.find("d", 0, 0, 96, 50);
        assertEquals(List.of(10, 30, 50), found.stream().map(Memory.Event::x).toList());
        assertEquals(1, m.find("d", 10, 0, 0, 0).size());
        assertTrue(m.find("d", 65546, 0, 1, 0).isEmpty());
    }

    @Test
    void loadingAHugeSaveDoesNotFreezeTheServer() {
        // el mod nunca guarda más que la capacidad: algo así es un archivo roto o editado a mano
        Memory m = new Memory(512, Long.MAX_VALUE);
        List<Memory.Event> saved = new ArrayList<>();
        for (int i = 0; i < 1_000_000; i++) {
            saved.add(new Memory.Event("t" + (i % 1000), i % 100, 0, i));
        }
        long start = System.nanoTime();
        m.load(saved, 2_000_000);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(512, m.size());
        assertEquals(999_999, m.events().get(511).time());
        assertTrue(ms < 1000, "load took " + ms + " ms");
    }
}
