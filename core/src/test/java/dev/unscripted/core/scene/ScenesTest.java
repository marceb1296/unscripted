package dev.unscripted.core.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unscripted.core.Activity;
import dev.unscripted.core.Context;
import dev.unscripted.core.Difficulty;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Placement;
import dev.unscripted.core.Terrain;
import dev.unscripted.core.Weather;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenesTest {
    static final long NOW = 1_000_000;

    static Context ctx(int dayTime, int moon, Difficulty diff, Set<Terrain> terrain, Map<String, Integer> mobs,
                       int armor, int village) {
        return new Context(NOW, dayTime, moon, Weather.CLEAR, diff, true, false, terrain, mobs, Activity.EXPLORING,
                1f, armor, 0, 0, village);
    }

    static Context day(Set<Terrain> terrain) {
        return ctx(6000, 3, Difficulty.NORMAL, terrain, Map.of(), 10, Context.NO_VILLAGE);
    }

    static Context night(Difficulty diff, int moon, int armor, int village) {
        // temprano: la horda necesita 5 minutos de noche por delante
        return ctx(15000, moon, diff, EnumSet.of(Terrain.PLAINS), Map.of(), armor, village);
    }

    final Memory memory = new Memory(512, 10 * 24000L);

    @Test
    void wolvesOnlyWhereWolvesLive() {
        WolfHunt w = new WolfHunt();
        assertEquals(0, w.weight(day(EnumSet.of(Terrain.DESERT)), memory));
        assertEquals(0, w.weight(day(Set.of()), memory));
        assertTrue(w.weight(day(EnumSet.of(Terrain.TAIGA)), memory) > 0);
    }

    @Test
    void wolvesPreferVisibleFlocksAndBackOffAfterBeingDrivenOff() {
        WolfHunt w = new WolfHunt();
        Context noSheep = day(EnumSet.of(Terrain.FOREST));
        Context sheep = ctx(6000, 3, Difficulty.NORMAL, EnumSet.of(Terrain.FOREST), Map.of("minecraft:sheep", 4), 10,
                Context.NO_VILLAGE);
        assertTrue(w.weight(sheep, memory) > w.weight(noSheep, memory));
        double before = w.weight(noSheep, memory);
        memory.add(WolfHunt.DRIVEN_OFF, 100, 0, NOW - 1000);
        assertTrue(w.weight(noSheep, memory) < before);
        memory.add(WolfHunt.DRIVEN_OFF, 100, 0, NOW - 1000);
        memory.prune(NOW + 4 * 24000L);
        Context later = new Context(NOW + 4 * 24000L, 6000, 3, Weather.CLEAR, Difficulty.NORMAL, true, false,
                EnumSet.of(Terrain.FOREST), Map.of(), Activity.EXPLORING, 1f, 10, 0, 0, Context.NO_VILLAGE);
        assertEquals(before, w.weight(later, memory));
    }

    @Test
    void scavengersNeedARecentNearbyDeathAndDarkness() {
        Scavengers s = new Scavengers();
        Context n = night(Difficulty.NORMAL, 3, 10, Context.NO_VILLAGE);
        assertEquals(0, s.weight(n, memory));
        memory.add(Scavengers.BIG_DEATH, 500, 0, NOW - 100);
        assertEquals(0, s.weight(n, memory));
        memory.add(Scavengers.BIG_DEATH, 40, 40, NOW - 100);
        assertTrue(s.weight(n, memory) > 0);
        assertEquals(0, s.weight(day(EnumSet.of(Terrain.PLAINS)), memory));
    }

    @Test
    void scavengersDoNotReturnToRemainsAlreadyVisited() {
        Scavengers s = new Scavengers();
        Context n = night(Difficulty.NORMAL, 3, 10, Context.NO_VILLAGE);
        memory.add(Scavengers.BIG_DEATH, 40, 40, NOW - 5000);
        assertTrue(s.weight(n, memory) > 0);
        memory.add(Scavengers.SCAVENGED, 50, 40, NOW - 4000);
        // los mismos restos no traen carroñeros dos veces, aunque el enfriamiento ya pasó
        assertEquals(0, s.weight(n, memory));
        assertTrue(Scavengers.site(n, memory).isEmpty());
        // una muerte nueva en el mismo lugar, después de la visita, sí
        memory.add(Scavengers.BIG_DEATH, 41, 40, NOW - 3000);
        assertEquals(41, Scavengers.site(n, memory).orElseThrow().x());
    }

    @Test
    void scavengersGoToTheNearestUnvisitedRemains() {
        Context n = night(Difficulty.NORMAL, 3, 10, Context.NO_VILLAGE);
        memory.add(Scavengers.BIG_DEATH, 80, 0, NOW - 100);
        memory.add(Scavengers.BIG_DEATH, 20, 0, NOW - 100);
        memory.add(Scavengers.BIG_DEATH, 50, 0, NOW - 100);
        memory.add(Scavengers.BIG_DEATH, 5, 0, NOW - 24001);
        assertEquals(20, Scavengers.site(n, memory).orElseThrow().x());
        memory.add(Scavengers.SCAVENGED, 20, 16, NOW - 50);
        assertEquals(50, Scavengers.site(n, memory).orElseThrow().x());
        memory.add(Scavengers.SCAVENGED, 50, 17, NOW - 50);
        assertEquals(50, Scavengers.site(n, memory).orElseThrow().x());
        // una visita en el mismo tick que la muerte ya la cuenta como visitada
        memory.add(Scavengers.SCAVENGED, 50, 0, NOW - 100);
        assertEquals(80, Scavengers.site(n, memory).orElseThrow().x());
    }

    @Test
    void zombiesOnlyWhereManyDiedTogether() {
        Context n = night(Difficulty.NORMAL, 3, 10, Context.NO_VILLAGE);
        // cinco muertes repartidas por la zona no son "donde murieron muchos"
        int[][] spread = {{0, 0}, {60, 0}, {-60, 0}, {0, 60}, {0, -60}};
        for (int[] p : spread) {
            memory.add(Scavengers.BIG_DEATH, p[0], p[1], NOW - 100);
        }
        Memory.Event site = Scavengers.site(n, memory).orElseThrow();
        assertEquals(Scavengers.Variant.WOLVES, Scavengers.variant(n, memory, site));
        for (int i = 0; i < 3; i++) {
            memory.add(Scavengers.BIG_DEATH, 10, 10, NOW - 100);
        }
        assertEquals(Scavengers.Variant.WOLVES, Scavengers.variant(n, memory, site));
        memory.add(Scavengers.BIG_DEATH, 32, 0, NOW - 100);
        assertEquals(Scavengers.Variant.ZOMBIES, Scavengers.variant(n, memory, site));
        assertEquals(Scavengers.Variant.WOLVES,
                Scavengers.variant(night(Difficulty.PEACEFUL, 3, 10, Context.NO_VILLAGE), memory, site));
        Context dusk = ctx(12500, 3, Difficulty.NORMAL, EnumSet.of(Terrain.PLAINS), Map.of(), 10, Context.NO_VILLAGE);
        assertEquals(Scavengers.Variant.WOLVES, Scavengers.variant(dusk, memory, site));
        Context taiga = ctx(12500, 3, Difficulty.NORMAL, EnumSet.of(Terrain.TAIGA), Map.of(), 10, Context.NO_VILLAGE);
        assertEquals(Scavengers.Variant.FOXES, Scavengers.variant(taiga, memory, site));
        // las muertes de hace más de un día no cuentan
        Memory fresh = new Memory(512, 10 * 24000L);
        for (int i = 0; i < 4; i++) {
            fresh.add(Scavengers.BIG_DEATH, 0, 0, NOW - 100);
        }
        fresh.add(Scavengers.BIG_DEATH, 0, 0, NOW - 24001);
        assertEquals(Scavengers.Variant.WOLVES, Scavengers.variant(n, fresh, Scavengers.site(n, fresh).orElseThrow()));
    }

    @Test
    void scavengersSurviveTheStartOfTime() {
        Context early = new Context(Long.MIN_VALUE + 10, 18000, 3, Weather.CLEAR, Difficulty.NORMAL, true, false,
                EnumSet.of(Terrain.PLAINS), Map.of(), Activity.EXPLORING, 1f, 10, 0, 0, Context.NO_VILLAGE);
        memory.add(Scavengers.BIG_DEATH, 0, 0, Long.MIN_VALUE + 5);
        assertTrue(Scavengers.site(early, memory).isPresent());
    }

    @Test
    void traderNeedsDaylightAndHostiles() {
        TraderInTrouble t = new TraderInTrouble();
        assertTrue(t.weight(day(EnumSet.of(Terrain.PLAINS)), memory) > 0);
        assertEquals(0, t.weight(night(Difficulty.NORMAL, 3, 10, Context.NO_VILLAGE), memory));
        assertEquals(0, t.weight(ctx(6000, 3, Difficulty.PEACEFUL, EnumSet.of(Terrain.PLAINS), Map.of(), 10,
                Context.NO_VILLAGE), memory));
    }

    @Test
    void hordeAtNightNearAVillageAndStrongerOnFullMoon() {
        WanderingHorde h = new WanderingHorde();
        assertEquals(0, h.weight(night(Difficulty.NORMAL, 3, 10, Context.NO_VILLAGE), memory));
        assertEquals(0, h.weight(night(Difficulty.PEACEFUL, 3, 10, 50), memory));
        assertEquals(0, h.weight(ctx(6000, 3, Difficulty.NORMAL, Set.of(), Map.of(), 10, 50), memory));
        double normal = h.weight(night(Difficulty.NORMAL, 3, 10, 50), memory);
        assertTrue(normal > 0);
        assertEquals(normal * 2, h.weight(night(Difficulty.NORMAL, 0, 10, 50), memory));
        assertEquals(normal / 2, h.weight(night(Difficulty.NORMAL, 3, 0, 50), memory));
        assertEquals(0, h.weight(night(Difficulty.NORMAL, 3, 10, 161), memory));
    }

    @Test
    void hordeNeedsTimeToArriveBeforeDawn() {
        WanderingHorde h = new WanderingHorde();
        // a las 22900 quedan 5 segundos de noche: los zombies se queman antes de llegar
        assertEquals(0, h.weight(ctx(22900, 3, Difficulty.NORMAL, EnumSet.of(Terrain.PLAINS), Map.of(), 10, 50), memory));
        // con menos de 5 minutos no entran las tres oleadas y la aldea no se puede defender
        assertEquals(0, h.weight(ctx(20000, 3, Difficulty.NORMAL, EnumSet.of(Terrain.PLAINS), Map.of(), 10, 50), memory));
        assertEquals(0, h.weight(ctx(17001, 3, Difficulty.NORMAL, EnumSet.of(Terrain.PLAINS), Map.of(), 10, 50), memory));
        assertTrue(h.weight(ctx(17000, 3, Difficulty.NORMAL, EnumSet.of(Terrain.PLAINS), Map.of(), 10, 50), memory) > 0);
        assertTrue(h.weight(ctx(13000, 3, Difficulty.NORMAL, EnumSet.of(Terrain.PLAINS), Map.of(), 10, 50), memory) > 0);
    }

    @Test
    void villageIsLookedUpWheneverTheHordeCouldHappen() {
        WanderingHorde h = new WanderingHorde();
        for (Difficulty d : Difficulty.values()) {
            for (int t = -24000; t < 48000; t += 250) {
                double w = h.weight(ctx(t, 3, d, EnumSet.of(Terrain.PLAINS), Map.of(), 10, 50), memory);
                // si el loader no busca la campana cuando la horda podía ocurrir, la horda no sale nunca
                assertEquals(WanderingHorde.canHappen(t, d), w > 0, d + " a las " + t);
            }
        }
    }

    @Test
    void nightLeftWithStrangeClocks() {
        assertEquals(10000, WanderingHorde.nightLeft(13000));
        assertEquals(1, WanderingHorde.nightLeft(22999));
        assertEquals(0, WanderingHorde.nightLeft(23000));
        assertEquals(0, WanderingHorde.nightLeft(12999));
        assertEquals(0, WanderingHorde.nightLeft(6000));
        // días enteros después, o un reloj negativo (comandos, otros mods): con % la hora queda negativa
        assertEquals(5000, WanderingHorde.nightLeft(24000L * 1_000_000 + 18000));
        assertEquals(5000, WanderingHorde.nightLeft(-6000));
        assertEquals(WanderingHorde.nightLeft(Long.MAX_VALUE % 24000), WanderingHorde.nightLeft(Long.MAX_VALUE));
        assertEquals(WanderingHorde.nightLeft(Math.floorMod(Long.MIN_VALUE, 24000L)), WanderingHorde.nightLeft(Long.MIN_VALUE));
    }

    @Test
    void wavesGrowWithTheMoonAndThePlayersAndHaveOneZombieVillager() {
        WanderingHorde.Size d = WanderingHorde.Size.DEFAULT;
        assertEquals(6, d.wave(0, false, false, 0).size());
        assertEquals(8, d.wave(1, false, false, 0).size());
        assertEquals(10, d.wave(2, false, false, 0).size());
        assertEquals(12, d.wave(2, false, true, 0).size());
        assertEquals(14, d.wave(1, false, true, 2).size());
        for (int i = 0; i < d.waves(); i++) {
            List<WanderingHorde.Kind> w = d.wave(i, false, false, 1);
            assertEquals(1, w.stream().filter(k -> k == WanderingHorde.Kind.ZOMBIE_VILLAGER).count());
            assertEquals(w.size() - 1, w.stream().filter(k -> k == WanderingHorde.Kind.ZOMBIE).count());
        }
        List<WanderingHorde.Kind> desert = d.wave(0, true, false, 0);
        assertEquals(desert.size() - 1, desert.stream().filter(k -> k == WanderingHorde.Kind.HUSK).count());
        // un servidor lleno no arma oleadas de cien, ni índices raros rompen el arreglo
        assertEquals(10 + 2 * d.maxExtraPlayers(), d.wave(2, false, false, 200).size());
        assertEquals(6, d.wave(0, false, false, -5).size());
        assertEquals(10, d.wave(99, false, false, 0).size());
        assertEquals(6, d.wave(-1, false, false, 0).size());
        assertEquals(10 + 2 * d.maxExtraPlayers(), d.wave(2, false, false, Integer.MAX_VALUE).size());
    }

    @Test
    void hostileHordeConfigNeverBuildsHugeOrEmptyWaves() {
        // valores de un archivo editado a mano: enormes, negativos o desbordantes
        WanderingHorde.Size huge = new WanderingHorde.Size(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(WanderingHorde.Size.MAX_WAVES, huge.waves());
        for (int i = 0; i < huge.waves(); i++) {
            assertEquals(WanderingHorde.Size.MAX_WAVE, huge.wave(i, true, true, Integer.MAX_VALUE).size());
        }
        WanderingHorde.Size none = new WanderingHorde.Size(-3, -1, -7, -2, -9, -4);
        assertEquals(1, none.waves());
        assertEquals(1, none.wave(0, false, true, 5).size());
        assertEquals(List.of(WanderingHorde.Kind.ZOMBIE_VILLAGER), none.wave(4, false, false, 0));
        assertEquals(1, none.size(0, false, 0));
        // un extra negativo no achica la oleada
        assertEquals(6, new WanderingHorde.Size(3, 6, 2, -5, 4, -3).size(0, true, 2));
        // sin crecimiento ni extras, todas iguales
        WanderingHorde.Size flat = new WanderingHorde.Size(5, 4, 0, 0, 0, 0);
        for (int i = 0; i < 5; i++) {
            assertEquals(4, flat.wave(i, false, true, 3).size());
        }
        // una oleada de más se acota a la última
        assertEquals(4 + 7 * 4, new WanderingHorde.Size(5, 4, 7, 0, 0, 0).size(9, false, 0));
    }

    @Test
    void nextWaveComesWhenFewAreLeftOrAfterAMinuteWithoutPassingTheCap() {
        assertTrue(WanderingHorde.nextWaveDue(3, 8, 0));
        assertTrue(!WanderingHorde.nextWaveDue(4, 8, 1199));
        assertTrue(WanderingHorde.nextWaveDue(4, 8, 1200));
        assertTrue(WanderingHorde.nextWaveDue(8, 8, 1200));
        // 9 vivos y 8 más pasan el tope de 16: espera a que queden pocos
        assertTrue(!WanderingHorde.nextWaveDue(9, 8, 5000));
        // una oleada más grande que el tope entra sola, no con otros vivos
        assertTrue(!WanderingHorde.nextWaveDue(4, 18, 5000));
        assertTrue(WanderingHorde.nextWaveDue(0, 18, 0));
        assertTrue(!WanderingHorde.nextWaveDue(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void hordeStartsWhereThePlayerSeesItPass() {
        // jugador a 100 bloques de la campana, hacia el este
        List<Placement.Offset> around = List.of(
                new Placement.Offset(-80, 0),
                new Placement.Offset(0, 90),
                new Placement.Offset(70, 0),
                new Placement.Offset(90, 0),
                new Placement.Offset(66, 20));
        List<Placement.Offset> ranked = WanderingHorde.rankStarts(around, 1000, -500, 1100, -500, List.of());
        // a unos 40 y 30 bloques del jugador, en la franja; el de 10 queda fuera (encima del jugador)
        assertEquals(new Placement.Offset(66, 20), ranked.get(0));
        assertEquals(new Placement.Offset(70, 0), ranked.get(1));
        assertEquals(new Placement.Offset(90, 0), ranked.get(2));
        assertEquals(new Placement.Offset(-80, 0), ranked.get(4));
        // con el jugador dentro de la aldea, ninguno queda en la franja: el más cercano a ella gana
        List<Placement.Offset> inside = WanderingHorde.rankStarts(around, 0, 0, -10, 0, List.of());
        assertEquals(new Placement.Offset(-80, 0), inside.get(0));
        assertEquals(new Placement.Offset(90, 0), inside.get(inside.size() - 1));
        // cerca del borde del mundo
        List<Placement.Offset> edge = WanderingHorde.rankStarts(around, 29_999_900, 29_999_900,
                29_999_990, 29_999_900, List.of());
        assertEquals(new Placement.Offset(66, 20), edge.get(0));
        assertEquals(5, edge.size());
    }

    @Test
    void sameSideCountsOnlyWavesLessThanNinetyDegreesAway() {
        List<Placement.Offset> before = List.of(new Placement.Offset(80, 0), new Placement.Offset(0, 80));
        assertEquals(2, WanderingHorde.sameSide(new Placement.Offset(50, 50), before));
        assertEquals(1, WanderingHorde.sameSide(new Placement.Offset(70, -10), before));
        // justo a 90 grados de las dos, o del lado opuesto: ninguna
        assertEquals(0, WanderingHorde.sameSide(new Placement.Offset(0, -70), List.of(new Placement.Offset(80, 0))));
        assertEquals(0, WanderingHorde.sameSide(new Placement.Offset(-60, -60), before));
        assertEquals(0, WanderingHorde.sameSide(new Placement.Offset(0, 0), before));
        assertEquals(0, WanderingHorde.sameSide(new Placement.Offset(50, 50), List.of()));
    }

    @Test
    void laterWavesComeFromAnotherSide() {
        List<Placement.Offset> around = List.of(new Placement.Offset(70, 0), new Placement.Offset(0, 80),
                new Placement.Offset(-75, 5), new Placement.Offset(10, -90));
        // jugador al este: sin oleadas antes, gana la del este
        assertEquals(new Placement.Offset(70, 0), WanderingHorde.rankStarts(around, 0, 0, 110, 0, List.of()).get(0));
        // la primera vino del este: ahora gana uno del otro lado, aunque quede más lejos del jugador
        List<Placement.Offset> second = WanderingHorde.rankStarts(around, 0, 0, 110, 0, List.of(new Placement.Offset(80, 10)));
        assertEquals(new Placement.Offset(10, -90), second.get(0));
        assertEquals(new Placement.Offset(-75, 5), second.get(1));
        // con oleadas del este y del sur, la que está del lado de ambas queda última
        List<Placement.Offset> third = WanderingHorde.rankStarts(List.of(new Placement.Offset(60, 60),
                new Placement.Offset(-70, 0), new Placement.Offset(0, -70)), 0, 0, 110, 0,
                List.of(new Placement.Offset(80, 0), new Placement.Offset(0, 80)));
        assertEquals(new Placement.Offset(60, 60), third.get(2));
        // coordenadas al borde del mundo no desbordan el producto
        List<Placement.Offset> huge = WanderingHorde.rankStarts(List.of(new Placement.Offset(-60_000, 0),
                new Placement.Offset(60_000, 0)), 0, 0, 60_000, 0, List.of(new Placement.Offset(60_000, 0)));
        assertEquals(new Placement.Offset(-60_000, 0), huge.get(0));
    }

    @Test
    void villageIsDefendedOnlyIfPlayersKilledHalfOfTheWholeHorde() {
        assertTrue(WanderingHorde.judge(5, 5, 3, 0).defended());
        assertTrue(WanderingHorde.judge(4, 4, 2, 0).defended());
        // los golems mataron a la mayoría
        assertTrue(!WanderingHorde.judge(5, 5, 2, 0).defended());
        // quedan zombies vivos, o desaparecieron sin morir
        assertTrue(!WanderingHorde.judge(5, 4, 4, 0).defended());
        assertTrue(!WanderingHorde.judge(6, 6, 1, 0).defended());
        // bajas contadas de más no tapan zombies vivos
        assertTrue(!WanderingHorde.judge(6, 2, 40, 0).defended());
        assertTrue(!WanderingHorde.judge(0, 0, 0, 0).defended());
        assertTrue(!WanderingHorde.judge(5, 5, -10, 0).defended());
        assertTrue(WanderingHorde.judge(1_500_000_000, 1_500_000_000, 1_500_000_000, 0).defended());
        WanderingHorde.Outcome both = WanderingHorde.judge(5, 5, 5, 1);
        assertTrue(both.defended() && both.damaged());
        assertTrue(!WanderingHorde.judge(5, 0, 0, 0).damaged());
        assertTrue(!WanderingHorde.judge(5, 0, 0, -1).damaged());
    }

    @Test
    void repeatedDeathEventsCountOnce() {
        // NeoForge lanza LivingDeathEvent antes del control de vanilla: el mismo zombie puede llegar dos veces
        WanderingHorde.Tally t = new WanderingHorde.Tally();
        t.spawned(4);
        t.memberGone("a", true);
        t.memberGone("a", true);
        t.memberGone("b", false);
        t.memberGone("b", true);
        t.memberGone("c", false);
        assertEquals(3, t.deaths());
        assertEquals(1, t.playerKills());
        // con dobles contados, 4 eventos de 3 zombies parecerían la horda entera muerta
        assertTrue(!t.outcome(true).defended());
        t.memberGone("d", true);
        assertTrue(t.outcome(true).defended());
        // faltaba una oleada: matar a los que llegaron no alcanza
        assertTrue(!t.outcome(false).defended());
        t.villagerLost("v");
        t.villagerLost("v");
        t.villagerLost(null);
        t.memberGone(null, true);
        assertEquals(1, t.villagersLost());
        assertEquals(4, t.deaths());
        assertTrue(t.outcome(true).damaged());
        assertTrue(t.outcome(false).damaged());
        WanderingHorde.Tally golems = new WanderingHorde.Tally();
        golems.spawned(4);
        for (String id : List.of("a", "b", "c", "d")) {
            golems.memberGone(id, id.equals("a"));
            golems.memberGone("a", true);
        }
        assertEquals(1, golems.playerKills());
        assertTrue(!golems.outcome(true).defended());
        // una oleada que no pudo llegar sale de la cuenta, pero nunca deja miembros negativos
        WanderingHorde.Tally stuck = new WanderingHorde.Tally();
        stuck.spawned(6);
        stuck.spawned(8);
        stuck.spawned(-8);
        for (String id : List.of("a", "b", "c", "d", "e", "f")) {
            stuck.memberGone(id, true);
        }
        assertEquals(6, stuck.members());
        assertTrue(stuck.outcome(true).defended());
        stuck.spawned(-100);
        assertEquals(0, stuck.members());
        assertTrue(!stuck.outcome(true).defended());
    }

    @Test
    void holdingOutUntilDawnDefendsTheVillage() {
        WanderingHorde.Tally t = new WanderingHorde.Tally();
        t.spawned(6);
        t.spawned(8);
        for (int i = 0; i < 6; i++) {
            t.memberGone("z" + i, true);
        }
        // 6 de 14: no alcanza la mitad
        assertTrue(!t.outcomeAtDawn().defended());
        t.memberGone("z6", true);
        assertTrue(t.outcomeAtDawn().defended());
        // al amanecer con oleadas pendientes, la regla común no defiende
        assertTrue(!t.outcome(false).defended());
        // un aldeano perdido: dañada y sin defensa
        t.villagerLost("v");
        assertTrue(!t.outcomeAtDawn().defended());
        assertTrue(t.outcomeAtDawn().damaged());
        // matar a uno solo y esconderse
        WanderingHorde.Tally hide = new WanderingHorde.Tally();
        hide.spawned(6);
        hide.memberGone("a", true);
        hide.memberGone("b", false);
        hide.memberGone("c", false);
        assertTrue(!hide.outcomeAtDawn().defended());
        assertTrue(!new WanderingHorde.Tally().outcomeAtDawn().defended());
        // con cantidad impar, la mitad no se redondea a favor del jugador: 2 de 5 no alcanza
        WanderingHorde.Tally odd = new WanderingHorde.Tally();
        odd.spawned(5);
        odd.memberGone("a", true);
        odd.memberGone("b", true);
        assertTrue(!odd.outcomeAtDawn().defended());
        odd.memberGone("c", true);
        assertTrue(odd.outcomeAtDawn().defended());
    }

    @Test
    void hordeRestsAfterAVillageWasHit() {
        WanderingHorde h = new WanderingHorde();
        memory.add(WanderingHorde.VILLAGE_DAMAGED, 30, 30, NOW - 5000);
        assertEquals(0, h.weight(night(Difficulty.NORMAL, 3, 10, 50), memory));
    }

    @Test
    void traderIsRarerWhereOneWasJustSaved() {
        TraderInTrouble t = new TraderInTrouble();
        Context c = day(EnumSet.of(Terrain.PLAINS));
        double before = t.weight(c, memory);
        memory.add(TraderInTrouble.SAVED, 200, 0, NOW - 3 * 24000L);
        assertEquals(before / 2, t.weight(c, memory));
        memory.prune(NOW + 1);
        Context later = new Context(NOW + 1, 6000, 3, Weather.CLEAR, Difficulty.NORMAL, true, false,
                EnumSet.of(Terrain.PLAINS), Map.of(), Activity.EXPLORING, 1f, 10, 0, 0, Context.NO_VILLAGE);
        assertEquals(before, t.weight(later, memory));
    }

    @Test
    void memoryStillCountsAtTheStartOfTime() {
        // con la resta sin saturar, el "desde" da la vuelta a un número enorme y la memoria deja de contar
        long t = Long.MIN_VALUE + 10;
        memory.add(WolfHunt.DRIVEN_OFF, 0, 0, t);
        memory.add(TraderInTrouble.SAVED, 0, 0, t);
        memory.add(WanderingHorde.VILLAGE_DAMAGED, 0, 0, t);
        Context day = new Context(t, 6000, 3, Weather.CLEAR, Difficulty.NORMAL, true, false,
                EnumSet.of(Terrain.FOREST), Map.of(), Activity.EXPLORING, 1f, 10, 0, 0, Context.NO_VILLAGE);
        Context night = new Context(t, 15000, 3, Weather.CLEAR, Difficulty.NORMAL, true, false,
                EnumSet.of(Terrain.PLAINS), Map.of(), Activity.EXPLORING, 1f, 10, 0, 0, 50);
        Memory empty = new Memory(512, 10 * 24000L);
        assertTrue(new WolfHunt().weight(day, memory) < new WolfHunt().weight(day, empty));
        assertTrue(new TraderInTrouble().weight(day, memory) < new TraderInTrouble().weight(day, empty));
        assertEquals(0, new WanderingHorde().weight(night, memory));
    }

    @Test
    void giftIsTheMostValuableLoot() {
        List<TraderInTrouble.Loot> loot = new ArrayList<>(Arrays.asList(
                new TraderInTrouble.Loot("minecraft:iron_nugget", 10),
                null,
                new TraderInTrouble.Loot(null, 5),
                new TraderInTrouble.Loot("minecraft:diamond", 1),
                new TraderInTrouble.Loot("minecraft:emerald", 0),
                new TraderInTrouble.Loot("minecraft:emerald", -64),
                new TraderInTrouble.Loot("minecraft:gold_nugget", 9)));
        assertEquals(3, TraderInTrouble.bestGift(loot));
        loot.add(new TraderInTrouble.Loot("minecraft:emerald", 3));
        assertEquals(7, TraderInTrouble.bestGift(loot));
        // con int, 500 millones por 5 desborda a negativo y nunca gana
        loot.add(new TraderInTrouble.Loot("modpack:coin", 500_000_000));
        assertEquals(8, TraderInTrouble.bestGift(loot));
        // empate: el primero que salió
        assertEquals(0, TraderInTrouble.bestGift(List.of(new TraderInTrouble.Loot("minecraft:gold_ingot", 2),
                new TraderInTrouble.Loot("minecraft:emerald", 1))));
        assertEquals(-1, TraderInTrouble.bestGift(List.of()));
        assertEquals(-1, TraderInTrouble.bestGift(List.of(new TraderInTrouble.Loot("minecraft:diamond", 0))));
    }
}
