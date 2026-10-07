package dev.unscripted.core.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unscripted.core.Activity;
import dev.unscripted.core.Context;
import dev.unscripted.core.Difficulty;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Rng;
import dev.unscripted.core.Terrain;
import dev.unscripted.core.Weather;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TwisterTest {
    static final long NOW = 1_000_000;
    final Memory memory = new Memory(512, 10 * 24000L);

    static Context ctx(int dayTime, Weather weather, Set<Terrain> terrain, int x, int z) {
        return new Context(NOW, dayTime, 3, weather, Difficulty.NORMAL, true, false, terrain, Map.of(),
                Activity.EXPLORING, 1f, 10, x, z, Context.NO_VILLAGE);
    }

    static Context day(Weather weather, Terrain... terrain) {
        return ctx(6000, weather, terrain.length == 0 ? Set.of() : EnumSet.of(terrain[0], terrain), 0, 0);
    }

    static Rng rng(long seed) {
        return new Random(seed)::nextDouble;
    }

    static Twister.Size size(Twister.Kind kind, long seed) {
        return Twister.roll(kind, rng(seed));
    }

    static double distance(double[] p, double x, double z) {
        return Math.hypot(p[0] - x, p[1] - z);
    }

    @Test
    void dustDevilsOnlyOnDryOpenGroundOnClearDays() {
        DustDevil d = new DustDevil();
        assertTrue(d.weight(day(Weather.CLEAR, Terrain.DESERT), memory) > 0);
        assertTrue(d.weight(day(Weather.CLEAR, Terrain.BADLANDS), memory) > 0);
        assertTrue(d.weight(day(Weather.CLEAR, Terrain.BEACH), memory) > 0);
        assertTrue(d.weight(day(Weather.CLEAR, Terrain.SNOWY, Terrain.PLAINS), memory) > 0);
        // el desierto es el bioma vacío que esta escena viene a llenar
        assertTrue(d.weight(day(Weather.CLEAR, Terrain.DESERT), memory)
                > d.weight(day(Weather.CLEAR, Terrain.PLAINS), memory));
        assertEquals(0, d.weight(day(Weather.CLEAR, Terrain.FOREST), memory));
        assertEquals(0, d.weight(day(Weather.CLEAR, Terrain.SNOWY, Terrain.TAIGA), memory));
        assertEquals(0, d.weight(day(Weather.CLEAR, Terrain.OCEAN), memory));
        assertEquals(0, d.weight(day(Weather.RAIN, Terrain.DESERT), memory));
        assertEquals(0, d.weight(ctx(18000, Weather.CLEAR, EnumSet.of(Terrain.DESERT), 0, 0), memory));
    }

    @Test
    void tornadoUsesRainOrStartsItOnlyIfAllowed() {
        Tornado allowed = new Tornado(true);
        Tornado never = new Tornado(false);
        Context clear = day(Weather.CLEAR, Terrain.PLAINS);
        Context rain = day(Weather.RAIN, Terrain.PLAINS);
        assertTrue(allowed.weight(clear, memory) > 0);
        assertEquals(0, never.weight(clear, memory));
        assertTrue(never.weight(rain, memory) > 0);
        // la lluvia de vanilla es rara: cuando la hay, el tornado es más probable
        assertTrue(allowed.weight(rain, memory) > allowed.weight(clear, memory));
        assertTrue(allowed.weight(day(Weather.THUNDER, Terrain.PLAINS), memory) >= allowed.weight(rain, memory));
        assertEquals(0, allowed.weight(ctx(18000, Weather.RAIN, EnumSet.of(Terrain.PLAINS), 0, 0), memory));
        assertEquals(0, allowed.weight(day(Weather.RAIN, Terrain.OCEAN), memory));
        assertEquals(0, allowed.weight(day(Weather.RAIN, Terrain.RIVER), memory));
        assertTrue(allowed.weight(day(Weather.RAIN, Terrain.FOREST), memory) < allowed.weight(rain, memory));
    }

    @Test
    void startedRainIsForTheWholeServerSoItIsRareAnywhere() {
        Tornado t = new Tornado(true);
        Context clear = day(Weather.CLEAR, Terrain.PLAINS);
        // otro jugador, muy lejos, ya hizo llover por un tornado ayer
        memory.add(Tornado.RAIN_STARTED, 25_000_000, -25_000_000, NOW - 24000);
        assertEquals(0, t.weight(clear, memory));
        // con lluvia que ya existe no se empieza nada: puede ocurrir igual
        assertTrue(t.weight(day(Weather.RAIN, Terrain.PLAINS), memory) > 0);
        Memory old = new Memory(512, 10 * 24000L);
        old.add(Tornado.RAIN_STARTED, 0, 0, NOW - 3 * 24000L);
        assertTrue(t.weight(clear, old) > 0);
    }

    @Test
    void neitherRepeatsWhereOneJustPassed() {
        DustDevil d = new DustDevil();
        Tornado t = new Tornado(true);
        memory.add(Twister.PASSED, 100, 50, NOW - 2000);
        assertEquals(0, d.weight(day(Weather.CLEAR, Terrain.DESERT), memory));
        assertEquals(0, t.weight(day(Weather.RAIN, Terrain.DESERT), memory));
        Context far = ctx(6000, Weather.CLEAR, EnumSet.of(Terrain.DESERT), 2000, 0);
        assertTrue(d.weight(far, memory) > 0);
    }

    @Test
    void groundPicksTheLookAndTheCargoOfTheBiome() {
        assertEquals(Twister.Ground.BADLANDS, Twister.ground(EnumSet.of(Terrain.BADLANDS, Terrain.DESERT)));
        assertEquals(Twister.Dust.RED_SAND, Twister.Ground.BADLANDS.dust());
        assertEquals(Twister.Dust.SAND, Twister.ground(EnumSet.of(Terrain.DESERT)).dust());
        assertEquals(Twister.Dust.SAND, Twister.ground(EnumSet.of(Terrain.BEACH)).dust());
        assertEquals(Twister.Dust.SNOW, Twister.ground(EnumSet.of(Terrain.SNOWY, Terrain.PLAINS)).dust());
        assertEquals(Twister.Dust.AIR, Twister.ground(EnumSet.of(Terrain.PLAINS)).dust());
        // un bioma de otro mod sin etiquetas conocidas
        assertEquals(Twister.Ground.PLAINS, Twister.ground(Set.of()));
        assertEquals(Twister.Ground.PLAINS, Twister.ground(null));
        for (Twister.Ground g : Twister.Ground.values()) {
            assertFalse(g.junk().isEmpty(), g.name());
            assertTrue(g.lootTable() != null && g.lootTable().startsWith("minecraft:"), g.name());
            g.junk().forEach(id -> assertTrue(id.startsWith("minecraft:"), id));
            assertFalse(g.debris().isEmpty(), g.name());
            g.debris().forEach(id -> assertTrue(id.startsWith("minecraft:"), id));
        }
        // los bloques que giran son del lugar: arena roja en la mesa, nieve en la nieve
        assertTrue(Twister.Ground.BADLANDS.debris().contains("minecraft:red_sand"));
        assertTrue(Twister.Ground.SNOWY.debris().contains("minecraft:snow_block"));
    }

    @Test
    void cargoIsMostlyJunkWithSomeLoot() {
        int items = 0;
        int tables = 0;
        for (long seed = 0; seed < 400; seed++) {
            for (Twister.Kind kind : Twister.Kind.values()) {
                List<Twister.Cargo> cargo = Twister.cargo(Twister.Ground.DESERT, kind, rng(seed));
                assertTrue(cargo.size() >= 3, "cargo " + cargo.size());
                for (Twister.Cargo c : cargo) {
                    items++;
                    if (c.table()) {
                        tables++;
                        assertEquals(Twister.Ground.DESERT.lootTable(), c.id());
                    } else {
                        assertTrue(Twister.Ground.DESERT.junk().contains(c.id()), c.id());
                    }
                }
            }
        }
        double share = (double) tables / items;
        assertTrue(share > 0.05 && share < 0.3, "loot share " + share);
        assertTrue(Twister.cargo(Twister.Ground.DESERT, Twister.Kind.TORNADO, rng(1)).size()
                > Twister.cargo(Twister.Ground.DESERT, Twister.Kind.DUST_DEVIL, rng(1)).size());
    }

    @Test
    void sizesStayInTheirRanges() {
        for (long seed = 0; seed < 200; seed++) {
            Twister.Size d = size(Twister.Kind.DUST_DEVIL, seed);
            Twister.Size t = size(Twister.Kind.TORNADO, seed);
            assertTrue(d.radius() >= 2 && d.radius() <= 3, "radius " + d.radius());
            assertTrue(d.height() >= 15 && d.height() <= 20, "height " + d.height());
            assertTrue(d.ticks() >= 60 * 20 && d.ticks() <= 90 * 20, "ticks " + d.ticks());
            assertTrue(t.radius() >= 4 && t.radius() <= 6, "radius " + t.radius());
            assertTrue(t.height() >= 40 && t.height() <= 60, "height " + t.height());
            assertTrue(t.ticks() >= 60 * 20 && t.ticks() <= 90 * 20, "ticks " + t.ticks());
        }
        // un Rng roto (otro mod, o una prueba) que devuelve 1 o NaN no deja tamaños fuera de rango
        Twister.Size one = Twister.roll(Twister.Kind.TORNADO, () -> 1.0);
        assertTrue(one.radius() <= 6 && one.height() <= 60 && one.ticks() <= 90 * 20);
        // una duración enorme (configuración o datos rotos) no deja revisar bloques sin fin
        assertEquals(Twister.MAX_TICKS, new Twister.Size(Twister.Kind.TORNADO, 5, 50, Integer.MAX_VALUE).ticks());
        Twister.Size nan = Twister.roll(Twister.Kind.TORNADO, () -> Double.NaN);
        assertTrue(nan.radius() >= 4 && nan.radius() <= 6 && nan.height() >= 40 && nan.ticks() >= 60 * 20);
    }

    @Test
    void liftsSmallThingsAndBigAnimalsOnlyInATornado() {
        // gallina 0,4 x 0,7; vaca 0,9 x 1,4; caballo 1,4 x 1,6; gólem 1,4 x 2,7; enderman 0,6 x 2,9
        assertTrue(Twister.lifts(Twister.Kind.DUST_DEVIL, 0.4, 0.7));
        assertFalse(Twister.lifts(Twister.Kind.DUST_DEVIL, 0.9, 1.4));
        assertTrue(Twister.lifts(Twister.Kind.TORNADO, 0.9, 1.4));
        assertTrue(Twister.lifts(Twister.Kind.TORNADO, 1.3964844, 1.6));
        assertFalse(Twister.lifts(Twister.Kind.TORNADO, 1.4, 2.7));
        assertFalse(Twister.lifts(Twister.Kind.TORNADO, 0.6, 2.9));
        assertFalse(Twister.lifts(Twister.Kind.TORNADO, Double.NaN, 1));
        // una caja vacía o al revés (datos rotos de otro mod) no se levanta
        assertFalse(Twister.lifts(Twister.Kind.TORNADO, 0, 1));
        assertFalse(Twister.lifts(Twister.Kind.TORNADO, 0.5, -1));
        assertFalse(Twister.lifts(Twister.Kind.TORNADO, 0.5, Double.NaN));
    }

    @Test
    void pathStartsOutOfTheWayAndPassesAtViewingDistance() {
        for (long seed = 0; seed < 300; seed++) {
            for (Twister.Kind kind : Twister.Kind.values()) {
                // también cerca del borde del mundo, donde un float pierde los decimales
                double px = seed % 2 == 0 ? 120.5 : 29_999_000.5;
                double pz = seed % 3 == 0 ? -40 : -29_999_000;
                Twister.Size s = size(kind, seed);
                Twister.Path p = Twister.plan(rng(seed), px, pz, s, 80, 100, kind.passMin, kind.passMax, List.of(),
                        (x, z) -> true).orElseThrow();
                double start = distance(p.at(0), px, pz);
                assertTrue(start >= 80 - p.sway() - 0.01 && start <= 100 + p.sway() + 0.01, "start " + start);
                double closest = Double.MAX_VALUE;
                for (int t = 0; t <= p.ticks(); t += 5) {
                    closest = Math.min(closest, distance(p.at(t), px, pz));
                }
                assertTrue(closest >= kind.passMin - p.sway() - 0.5 && closest <= kind.passMax + p.sway() + 0.5,
                        "closest " + closest);
                assertTrue(p.speed() >= kind.minSpeed - 1e-9 && p.speed() <= kind.maxSpeed + 1e-9, "speed " + p.speed());
                // un tornado rápido termina al alejarse, no sigue cientos de bloques por zonas sin cargar
                assertTrue(p.ticks() <= s.ticks());
                double length = p.speed() * p.ticks();
                assertTrue(length <= 2 * Math.sqrt(100.0 * 100 - kind.passMin * kind.passMin) + Twister.EXIT + 1,
                        "length " + length);
                // termina lejos, no encima del jugador
                assertTrue(distance(p.at(p.ticks()), px, pz) > 48, "end " + distance(p.at(p.ticks()), px, pz));
            }
        }
    }

    @Test
    void pathAtStaysOnTheLineAndClampsTime() {
        Twister.Size s = size(Twister.Kind.TORNADO, 4);
        Twister.Path p = Twister.plan(rng(4), 0, 0, s, 80, 100, 24, 48, List.of(), (x, z) -> true).orElseThrow();
        double[] end = p.at(p.ticks());
        assertEquals(end[0], p.at(Long.MAX_VALUE)[0], 1e-9);
        assertEquals(end[1], p.at(Long.MAX_VALUE)[1], 1e-9);
        assertEquals(p.at(0)[0], p.at(-5)[0], 1e-9);
        double moved = Math.hypot(end[0] - p.startX(), end[1] - p.startZ());
        assertEquals(p.speed() * p.ticks(), moved, p.sway() + 1e-6);
    }

    @Test
    void pathKeepsAwayFromBuildsBellsAndBeds() {
        for (long seed = 0; seed < 100; seed++) {
            for (Twister.Kind kind : Twister.Kind.values()) {
                Twister.Size s = size(kind, seed);
                // una casa de 5 x 5 a 60 bloques al este, y una campana a 70 al oeste
                Twister.Clear noHouse = (x, z) -> !(x >= 58 && x <= 62 && z >= -2 && z <= 2);
                List<Twister.Keep> bell = List.of(new Twister.Keep(-70, 0, 48));
                Optional<Twister.Path> plan = Twister.plan(rng(seed), 0, 0, s, 80, 100, 24, 48, bell, noHouse);
                if (plan.isEmpty()) {
                    continue;
                }
                Twister.Path p = plan.get();
                for (int t = 0; t <= p.ticks(); t += 2) {
                    double[] at = p.at(t);
                    assertTrue(Math.hypot(at[0] - 60, at[1]) > s.radius() + 2, "house at tick " + t);
                    assertTrue(Math.hypot(at[0] + 70, at[1]) >= 48, "bell at tick " + t);
                }
            }
        }
        Twister.Size s = size(Twister.Kind.DUST_DEVIL, 1);
        assertTrue(Twister.plan(rng(1), 0, 0, s, 80, 100, 24, 48, List.of(), (x, z) -> false).isEmpty());
        // la cama del jugador donde está parado: solo vale un camino que pase a más de 32 bloques
        List<Twister.Keep> bed = List.of(new Twister.Keep(0, 0, 32));
        Twister.plan(rng(2), 0, 0, s, 80, 100, 24, 48, bed, (x, z) -> true).ifPresent(p -> {
            for (int t = 0; t <= p.ticks(); t += 2) {
                assertTrue(Math.hypot(p.at(t)[0], p.at(t)[1]) >= 32);
            }
        });
    }

    @Test
    void hostilePlanInputsGiveAValidPathOrNone() {
        Twister.Size s = size(Twister.Kind.TORNADO, 3);
        // rangos al revés y una distancia de paso mayor que la de inicio (geometría imposible: asin de más de 1)
        Optional<Twister.Path> p = Twister.plan(rng(3), 0, 0, s, 100, 80, 200, 150, List.of(), (x, z) -> true);
        p.ifPresent(path -> {
            for (int t = 0; t <= path.ticks(); t += 50) {
                assertTrue(Double.isFinite(path.at(t)[0]) && Double.isFinite(path.at(t)[1]), "tick " + t);
            }
        });
        assertTrue(p.isPresent());
        assertTrue(Twister.plan(rng(3), Double.NaN, 0, s, 80, 100, 24, 48, List.of(), (x, z) -> true).isEmpty());
        Twister.Size zero = new Twister.Size(Twister.Kind.TORNADO, 5, 50, 0);
        Twister.plan(rng(3), 0, 0, zero, 80, 100, 24, 48, List.of(), (x, z) -> true)
                .ifPresent(path -> assertTrue(Double.isFinite(path.speed())));
        // un camino enorme no congela el servidor revisando bloques
        Twister.Size huge = new Twister.Size(Twister.Kind.TORNADO, 5, 50, Integer.MAX_VALUE);
        int[] checks = {0};
        Twister.plan(rng(3), 0, 0, huge, 80, 100, 24, 48, List.of(), (x, z) -> ++checks[0] > 0);
        assertTrue(checks[0] < 100_000, "checks " + checks[0]);
    }

    @Test
    void pushSpinsInLiftsAndLetsGo() {
        Twister.Size s = new Twister.Size(Twister.Kind.TORNADO, 5, 50, 2000);
        Twister.Push p = Twister.push(s, 4, 0, 1, 0, 13, 60);
        assertNotNull(p);
        assertTrue(p.y() > 0, "rises");
        // gira siempre hacia el mismo lado: en (4, 0) la velocidad tangencial es +z
        assertTrue(p.z() > 0.1, "spin " + p.z());
        Twister.Push other = Twister.push(s, 0, 4, 1, 0, 13, 60);
        assertTrue(other.x() < -0.1, "spin " + other.x());
        // arriba del todo ya no sube
        assertTrue(Twister.push(s, 4, 0, 13.5, 10, 13, 60).y() <= 0);
        // desde afuera de la columna, lo atrae hacia adentro
        assertTrue(Twister.push(s, s.radius() + 1, 0, 1, 0, 13, 60).x() < 0);
        // al cumplir el tiempo, lo suelta hacia afuera
        Twister.Push go = Twister.push(s, 4, 0, 13, 60, 13, 60);
        assertTrue(go.release() && go.x() > 0);
        assertNull(Twister.push(s, 30, 0, 1, 0, 13, 60));
        assertNull(Twister.push(s, Double.NaN, 0, 1, 0, 13, 60));
        assertNull(Twister.push(s, 1, 0, Double.POSITIVE_INFINITY, 0, 13, 60));
    }

    @Test
    void pushAtTheExactCenterIsNotNaN() {
        // dividir por la distancia en el centro exacto daría NaN, y un NaN en la velocidad de un jugador lo
        // deja fuera del mundo
        Twister.Size s = new Twister.Size(Twister.Kind.DUST_DEVIL, 2.5, 18, 1500);
        Twister.Push p = Twister.push(s, 0, 0, 0, 0, 3, 30);
        assertNotNull(p);
        assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()));
        Twister.Push go = Twister.push(s, 0, 0, 3, 30, 3, 30);
        assertTrue(Double.isFinite(go.x()) && Double.isFinite(go.z()));
    }

    @Test
    void pushIsNeverFastEnoughToKickOrHurt() {
        for (Twister.Kind kind : Twister.Kind.values()) {
            Twister.Size s = Twister.roll(kind, () -> 1.0);
            for (double dx = -s.radius() - 2; dx <= s.radius() + 2; dx += 0.25) {
                for (int held = 0; held <= 200; held += 20) {
                    Twister.Push p = Twister.push(s, dx, 0.3, 2, held, 15, kind.playerHold());
                    if (p != null) {
                        assertTrue(Math.abs(p.x()) <= 1.5 && Math.abs(p.y()) <= 1.5 && Math.abs(p.z()) <= 1.5,
                                kind + " " + p);
                    }
                }
            }
        }
    }

    @Test
    void playersAreLetGoBeforeTheFlyingKick() {
        // un servidor sin allow-flight echa al jugador que flota más de 80 ticks seguidos
        for (Twister.Kind kind : Twister.Kind.values()) {
            assertTrue(kind.playerHold() > 0 && kind.playerHold() < 80, kind + " " + kind.playerHold());
        }
    }

    @Test
    void tornadoIsFasterAndPassesCloserThanADustDevil() {
        Twister.Kind d = Twister.Kind.DUST_DEVIL;
        Twister.Kind t = Twister.Kind.TORNADO;
        assertTrue(t.passMin < d.passMin && t.passMax < d.passMax);
        assertTrue(t.playerLift >= 18, "lift " + t.playerLift);
        assertTrue(d.maxSpeed < WALK_SPEED, "dust devil " + d.maxSpeed);
        // la velocidad del camino sale de todo el rango, no queda siempre en el mínimo
        for (Twister.Kind kind : List.of(t)) {
            double lo = Double.MAX_VALUE;
            double hi = 0;
            for (long seed = 0; seed < 200; seed++) {
                Twister.Size s = size(kind, seed);
                double v = Twister.plan(rng(seed), 0, 0, s, 80, 100, kind.passMin, kind.passMax, List.of(),
                        (x, z) -> true).orElseThrow().speed();
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            double span = kind.maxSpeed - kind.minSpeed;
            assertTrue(lo < kind.minSpeed + span * 0.2 && hi > kind.maxSpeed - span * 0.2, kind + " " + lo + " " + hi);
        }
    }

    static final double WALK_SPEED = 0.216;
    /** Lo que suma por tick el movimiento del jugador en el suelo (atributo de velocidad). */
    static final double WALK_ACCEL = 0.1;
    static final double SPRINT_ACCEL = 0.13;
    static final double GROUND_DRAG = 0.546;

    /**
     * Un jugador en el suelo que corre en línea recta alejándose del centro, como lo mueve el cliente: la
     * succión le pisa la velocidad un tick sí y uno no (como hace el loader), él suma su aceleración, se
     * mueve y el suelo lo frena. True si llega a la columna.
     */
    static boolean caught(Twister.Size s, double start, double accel) {
        double px = start;
        double pz = 0;
        double vx = 0;
        double vz = 0;
        double far = s.radius() * s.kind().suction;
        for (int tick = 0; tick < 2000; tick++) {
            double d = Math.hypot(px, pz);
            if (d <= s.reach()) {
                return true;
            }
            if (d > far + 1) {
                return false;
            }
            Twister.Push p = Twister.suck(s, px, pz);
            if (p != null && tick % 2 == 0) {
                vx = p.x();
                vz = p.z();
            }
            vx += accel * px / d;
            vz += accel * pz / d;
            px += vx;
            pz += vz;
            vx *= GROUND_DRAG;
            vz *= GROUND_DRAG;
        }
        return false;
    }

    @Test
    void suctionDragsInWhoeverGetsCloseButASprinterOnTheEdgeEscapes() {
        for (double radius : new double[]{4, 5, 6}) {
            Twister.Size t = new Twister.Size(Twister.Kind.TORNADO, radius, 50, 2000);
            double far = radius * Twister.Kind.TORNADO.suction;
            double ring = far - t.reach();
            // alcanza lejos: al menos 3 veces el radio
            assertTrue(far >= radius * 3, "far " + far);
            // cerca de la columna ni corriendo se escapa
            assertTrue(caught(t, t.reach() + ring * 0.3, SPRINT_ACCEL), "sprinter near, radius " + radius);
            // a media distancia, caminando no alcanza
            assertTrue(caught(t, t.reach() + ring * 0.5, WALK_ACCEL), "walker halfway, radius " + radius);
            // quieto lo arrastra desde cualquier parte
            assertTrue(caught(t, far - 0.5, 0), "standing at the edge, radius " + radius);
            // en el borde, corriendo sí
            assertFalse(caught(t, t.reach() + ring * 0.85, SPRINT_ACCEL), "sprinter on the edge, radius " + radius);
        }
        Twister.Size t = new Twister.Size(Twister.Kind.TORNADO, 5, 50, 2000);
        Twister.Push near = Twister.suck(t, t.reach() + 1, 0);
        assertTrue(near.x() < 0 && near.y() == 0, "inward " + near);
        assertTrue(Math.abs(near.x()) > Math.abs(Twister.suck(t, t.reach() + 6, 0).x()), "stronger near");
        assertNull(Twister.suck(t, 5 * Twister.Kind.TORNADO.suction + 1, 0));
        assertNull(Twister.suck(t, 3, 0), "inside the column the lift takes over");
        assertNull(Twister.suck(t, Double.NaN, 0));
        assertNull(Twister.suck(new Twister.Size(Twister.Kind.DUST_DEVIL, 2.5, 18, 1500), 4.5, 0));
    }

    /**
     * Un jugador quieto a {@code behind} bloques detrás de un tornado que se aleja a toda velocidad, movido como
     * en el juego (la succión le pisa la velocidad un tick sí y uno no). True si llega a la columna.
     */
    static boolean caughtFromBehind(Twister.Size s, double behind) {
        double v = s.kind().maxSpeed;
        double px = -behind;
        double vx = 0;
        double vz = 0;
        double pz = 0;
        for (int tick = 0; tick < 2000; tick++) {
            double tx = v * tick;
            if (Math.hypot(px - tx, pz) <= s.reach()) {
                return true;
            }
            Twister.Push p = Twister.suck(s, px - tx, pz, v, 0);
            if (p != null && tick % 2 == 0) {
                vx = p.x();
                vz = p.z();
            }
            px += vx;
            pz += vz;
            vx *= GROUND_DRAG;
            vz *= GROUND_DRAG;
        }
        return false;
    }

    @Test
    void pathChecksBuildingsBeforeItsStartAndPastItsEnd() {
        Twister.Kind kind = Twister.Kind.TORNADO;
        for (long seed = 0; seed < 50; seed++) {
            Twister.Size s = size(kind, seed);
            Twister.Path free = Twister.plan(rng(seed), 0, 0, s, 80, 100, kind.passMin, kind.passMax, List.of(),
                    (x, z) -> true).orElseThrow();
            double length = free.speed() * free.ticks();
            // una casa (5 x 5) justo detrás de donde aparece, y otra justo después de donde se deshace
            for (double along : new double[]{-s.reach(), length + s.reach()}) {
                int bx = (int) Math.floor(free.startX() + free.dirX() * along);
                int bz = (int) Math.floor(free.startZ() + free.dirZ() * along);
                Optional<Twister.Path> p = Twister.plan(rng(seed), 0, 0, s, 80, 100, kind.passMin, kind.passMax,
                        List.of(), (x, z) -> Math.abs(x - bx) > 2 || Math.abs(z - bz) > 2);
                assertTrue(p.isEmpty() || p.get().startX() != free.startX() || p.get().startZ() != free.startZ(),
                        "seed " + seed + " ignored a house at " + along);
            }
        }
    }

    @Test
    void suctionAndLiftMoveWithTheTornado() {
        for (double radius : new double[]{4, 5, 6}) {
            Twister.Size t = new Twister.Size(Twister.Kind.TORNADO, radius, 50, 2000);
            double ring = radius * Twister.Kind.TORNADO.suction - t.reach();
            // detrás también arrastra: el viento va con él
            assertTrue(caughtFromBehind(t, t.reach() + ring * 0.5), "behind, radius " + radius);
            // el que gira adentro avanza con él y no se cae por detrás antes de que lo suelte
            double v = t.kind().maxSpeed;
            double px = 2;
            double pz = 0;
            double above = 0;
            for (int held = 0; held < t.kind().playerHold(); held++) {
                double tx = v * held;
                Twister.Push p = Twister.push(t, px - tx, pz, above, held, t.kind().playerLift,
                        t.kind().playerHold(), v, 0);
                assertNotNull(p, "dropped at tick " + held + ", radius " + radius);
                px += p.x();
                pz += p.z();
                above += p.y();
                // pasado el primer segundo gira centrado, no unos bloques detrás
                if (held >= 20) {
                    double off = Math.hypot(px - v * (held + 1), pz);
                    assertTrue(off <= radius * 0.5 + 0.6, "lagging " + off + " at tick " + held + ", radius " + radius);
                }
            }
        }
        // quieto, nada cambia
        Twister.Size t = new Twister.Size(Twister.Kind.TORNADO, 5, 50, 2000);
        assertEquals(Twister.suck(t, 9, 1), Twister.suck(t, 9, 1, 0, 0));
        // con valores rotos no empuja a lo loco
        Twister.Push broken = Twister.suck(t, 9, 1, Double.NaN, Double.POSITIVE_INFINITY);
        assertTrue(broken == null || Double.isFinite(broken.x()) && Double.isFinite(broken.z()), "" + broken);
    }

    /**
     * Un tornado de {@code ticks} que arranca a 90 bloques de los jugadores y los persigue como en el juego.
     * {@code dodge}: cada jugador, al tenerlo a 18 bloques, corre 3 s de costado. Devuelve cuántas veces
     * atrapó a cada uno (entrar en su alcance tras haber salido).
     */
    static int[] hunt(long seed, int ticks, boolean dodge, double[][] players, List<Twister.Keep> keep,
                      Twister.Clear clear, List<double[]> trail) {
        Random r = new Random(seed);
        Twister.Kind k = Twister.Kind.TORNADO;
        double reach = new Twister.Size(k, 5, 50, ticks).reach();
        double a = r.nextDouble() * 2 * Math.PI;
        double x = players[0][0] + Math.cos(a) * 90;
        double z = players[0][1] + Math.sin(a) * 90;
        double angle = a + Math.PI + (r.nextDouble() - 0.5) * 0.6;
        int[] caught = new int[players.length];
        boolean[] inside = new boolean[players.length];
        int[] running = new int[players.length];
        double[][] run = new double[players.length][];
        for (int t = 0; t < ticks; t++) {
            int i = Twister.prey(x, z, players, k.huntRange);
            Twister.Heading h = Twister.steer(k, x, z, angle, i < 0 ? Double.NaN : players[i][0],
                    i < 0 ? Double.NaN : players[i][1]);
            angle = Twister.avoid(x, z, h.angle(), reach + 4, keep, clear);
            x += Math.cos(angle) * h.speed();
            z += Math.sin(angle) * h.speed();
            if (trail != null) {
                trail.add(new double[]{x, z});
            }
            for (int p = 0; p < players.length; p++) {
                double d = Math.hypot(players[p][0] - x, players[p][1] - z);
                if (dodge && running[p] == 0 && d < 18) {
                    double side = Math.atan2(players[p][1] - z, players[p][0] - x) + Math.PI / 2;
                    run[p] = new double[]{Math.cos(side) * 0.28, Math.sin(side) * 0.28};
                    running[p] = 60;
                }
                if (running[p] > 0) {
                    running[p]--;
                    players[p][0] += run[p][0];
                    players[p][1] += run[p][1];
                }
                if (d < reach && !inside[p]) {
                    caught[p]++;
                    inside[p] = true;
                }
                if (d > reach + 10) {
                    inside[p] = false;
                }
            }
        }
        return caught;
    }

    /**
     * Un jugador que, con el tornado a 8 a 30 bloques (según sus reflejos), corre de costado 2 a 5 s. True si
     * esa pasada no lo atrapa.
     */
    static boolean dodges(long seed) {
        Random r = new Random(seed);
        Twister.Kind k = Twister.Kind.TORNADO;
        double reach = new Twister.Size(k, 4, 50, k.minTicks).reach();
        double a = r.nextDouble() * 2 * Math.PI;
        double x = Math.cos(a) * 90;
        double z = Math.sin(a) * 90;
        double angle = a + Math.PI + (r.nextDouble() - 0.5) * 0.6;
        double react = 8 + r.nextDouble() * 22;
        double side = r.nextBoolean() ? 1 : -1;
        int run = 40 + r.nextInt(61);
        double px = 0;
        double pz = 0;
        double dx = 0;
        double dz = 0;
        int started = -1;
        for (int t = 0; t < k.maxTicks; t++) {
            Twister.Heading h = Twister.steer(k, x, z, angle, px, pz);
            angle = h.angle();
            x += Math.cos(angle) * h.speed();
            z += Math.sin(angle) * h.speed();
            double d = Math.hypot(px - x, pz - z);
            if (d < reach) {
                return false;
            }
            if (started < 0 && d < react) {
                started = t;
                double run0 = Math.atan2(pz - z, px - x) + side * Math.PI / 2;
                dx = Math.cos(run0) * 0.28;
                dz = Math.sin(run0) * 0.28;
            }
            if (started >= 0 && t - started < run) {
                px += dx;
                pz += dz;
            }
            if (started >= 0 && t - started > run && d > reach + 12) {
                return true;
            }
        }
        return true;
    }

    /**
     * Un jugador que corre en línea recta alejándose desde que el tornado aparece a 90 bloques (sin construcciones
     * en el camino). El tick en que lo atrapa, o -1.
     */
    static int flees(long seed) {
        return flees(seed, 90, 0.28, 0);
    }

    /** {@code heading}: cuánto se aparta su rumbo inicial de ir derecho al jugador. */
    static int flees(long seed, double start, double speed, double heading) {
        Random r = new Random(seed);
        Twister.Kind k = Twister.Kind.TORNADO;
        double reach = new Twister.Size(k, 4, 50, k.minTicks).reach();
        double a = r.nextDouble() * 2 * Math.PI;
        double x = Math.cos(a) * start;
        double z = Math.sin(a) * start;
        double angle = a + Math.PI + heading;
        double px = 0;
        double pz = 0;
        double[][] players = new double[1][];
        for (int t = 0; t < k.maxTicks; t++) {
            players[0] = new double[]{px, pz};
            int i = Twister.prey(x, z, players, k.huntRange);
            Twister.Heading h = Twister.steer(k, x, z, angle, i < 0 ? Double.NaN : px, i < 0 ? Double.NaN : pz);
            angle = h.angle();
            x += Math.cos(angle) * h.speed();
            z += Math.sin(angle) * h.speed();
            if (Math.hypot(px - x, pz - z) < reach) {
                return t;
            }
            px += Math.cos(a + Math.PI) * speed;
            pz += Math.sin(a + Math.PI) * speed;
        }
        return -1;
    }

    @Test
    void tornadoComesBackForAPlayerWhoStandsStillAndIsRarelyDodged() {
        Twister.Kind k = Twister.Kind.TORNADO;
        for (long seed = 0; seed < 100; seed++) {
            // en su vida más corta pasa varias veces: al pasar de largo vuelve en curva
            int still = hunt(seed, k.minTicks, false, new double[][]{{0, 0}}, List.of(), (x, z) -> true, null)[0];
            assertTrue(still >= 3, "seed " + seed + " caught " + still);
        }
        // corriendo de costado se salva más o menos 1 de cada 20
        int escaped = 0;
        for (long seed = 0; seed < 1000; seed++) {
            escaped += dodges(seed) ? 1 : 0;
        }
        assertTrue(escaped >= 10 && escaped <= 120, "escaped " + escaped + " of 1000");
        // más rápido que un jugador corriendo (0,28) aun lejos: huir no sirve
        assertTrue(k.minSpeed > 0.28 && k.maxSpeed > k.minSpeed, k.minSpeed + " " + k.maxSpeed);
        for (long seed = 0; seed < 100; seed++) {
            int t = flees(seed);
            assertTrue(t >= 0 && t < k.minTicks, "seed " + seed + " caught at " + t);
            // quien ya estaba lejos (se alejó durante el aviso) tampoco se salva, aunque no lo tenga de frente
            int far = flees(seed, 150, 0, Math.PI / 2);
            assertTrue(far >= 0 && far < k.minTicks, "seed " + seed + " far one caught at " + far);
        }
        assertTrue(k.minTicks >= 60 * 20, "life " + k.minTicks);
    }

    @Test
    void tornadoGoesForTheNearestPlayerEveryTime() {
        double[][] players = {{100, 0}, {10, 5}, {-30, 0}, null, {0}};
        assertEquals(1, Twister.prey(0, 0, players, 96));
        assertEquals(2, Twister.prey(-25, 0, players, 96));
        assertEquals(-1, Twister.prey(500, 500, players, 96));
        assertEquals(-1, Twister.prey(0, 0, null, 96));
        // dos jugadores separados: al pasar a uno sigue con el que le queda más cerca, y atrapa a los dos
        int[] both = hunt(3, 90 * 20, false, new double[][]{{0, 0}, {50, 0}}, List.of(), (x, z) -> true, null);
        assertTrue(both[0] >= 1 && both[1] >= 1, both[0] + " " + both[1]);
    }

    @Test
    void dustDevilPlaysAroundThePlayerWithoutHuntingHim() {
        Twister.Kind k = Twister.Kind.DUST_DEVIL;
        // más lento que un jugador caminando: se lo puede perseguir
        assertTrue(k.maxSpeed < WALK_SPEED && k.turn > 0, k.maxSpeed + " " + k.turn);
        assertTrue(k.playerLift >= 6 && k.playerLift <= 8, "lift " + k.playerLift);
        for (long seed = 0; seed < 100; seed++) {
            Random r = new Random(seed);
            double reach = new Twister.Size(k, 2.5, 18, k.minTicks).reach();
            double a = r.nextDouble() * 2 * Math.PI;
            double x = Math.cos(a) * 40;
            double z = Math.sin(a) * 40;
            double angle = a + Math.PI;
            double phase = r.nextDouble() * 2 * Math.PI;
            int over = 0;
            int near = 0;
            int ticks = 0;
            boolean arrived = false;
            for (int t = 0; t < k.minTicks; t++) {
                double[] lure = Twister.lure(t, 0, 0, phase);
                assertTrue(Math.hypot(lure[0], lure[1]) <= Twister.LURE + 1e-9, "lure " + lure[0] + " " + lure[1]);
                Twister.Heading h = Twister.steer(k, x, z, angle, lure[0], lure[1]);
                angle = h.angle();
                x += Math.cos(angle) * h.speed();
                z += Math.sin(angle) * h.speed();
                double d = Math.hypot(x, z);
                arrived |= d < 24;
                if (arrived) {
                    ticks++;
                    over += d < reach ? 1 : 0;
                    near += d < 30 ? 1 : 0;
                }
            }
            // te pasa por encima alguna vez, pero no se queda encima
            assertTrue(over > 0 && over < ticks * 0.4, "seed " + seed + " over " + over + " of " + ticks);
            // y no se va lejos
            assertTrue(near > ticks * 0.9, "seed " + seed + " near " + near + " of " + ticks);
        }
        // el punto da vueltas: a veces encima del jugador, a veces casi a LURE
        double closest = Double.MAX_VALUE;
        double farthest = 0;
        for (int t = 0; t < 600; t++) {
            double[] p = Twister.lure(t, 100, -50, 1);
            double d = Math.hypot(p[0] - 100, p[1] + 50);
            closest = Math.min(closest, d);
            farthest = Math.max(farthest, d);
        }
        assertTrue(closest < 1 && farthest > Twister.LURE * 0.9, closest + " " + farthest);
        // una fase rota o un tiempo enorme no lo mandan lejos ni dan NaN
        for (double phase : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            double[] broken = Twister.lure(Long.MAX_VALUE, 0, 0, phase);
            assertTrue(Math.hypot(broken[0], broken[1]) <= Twister.LURE + 1e-9, broken[0] + " " + broken[1]);
        }
    }

    @Test
    void steeringTurnsSlowlyAndSpeedsUpNearItsPrey() {
        Twister.Kind k = Twister.Kind.TORNADO;
        // la presa justo detrás: gira solo lo que le permite su curva
        Twister.Heading back = Twister.steer(k, 0, 0, 0, -30, 0.001);
        assertEquals(k.turn, Math.abs(back.angle()), 1e-9);
        Twister.Heading near = Twister.steer(k, 0, 0, 0, 10, 0);
        Twister.Heading far = Twister.steer(k, 0, 0, 0, 90, 0);
        assertEquals(k.maxSpeed, near.speed(), 1e-9);
        assertEquals(k.minSpeed, far.speed(), 1e-9);
        assertEquals(0, near.angle(), 1e-9);
        // sin presa o con valores rotos sigue derecho, sin NaN
        Twister.Heading none = Twister.steer(k, 0, 0, 1, Double.NaN, Double.NaN);
        assertEquals(1, none.angle(), 1e-9);
        Twister.Heading broken = Twister.steer(k, Double.NaN, 0, Double.POSITIVE_INFINITY, 5, 5);
        assertTrue(Double.isFinite(broken.angle()) && Double.isFinite(broken.speed()));
        // muchas vueltas no hacen crecer el ángulo sin fin
        double a = 0;
        for (int t = 0; t < 100_000; t++) {
            a = Twister.steer(k, Math.cos(t * 0.01) * 20, Math.sin(t * 0.01) * 20, a, 0, 0).angle();
        }
        assertTrue(Math.abs(a) <= Math.PI + 1e-9, "angle " + a);
    }

    @Test
    void huntingNeverWalksIntoBuildingsOrBells() {
        // una base al este (x > 20) y una campana al oeste; el jugador entre las dos
        Twister.Clear clear = (x, z) -> x <= 20;
        List<Twister.Keep> keep = List.of(new Twister.Keep(-40, 0, 20));
        for (long seed = 0; seed < 50; seed++) {
            List<double[]> trail = new java.util.ArrayList<>();
            hunt(seed, 90 * 20, false, new double[][]{{0, 0}}, keep, clear, trail);
            // el inicio lo elige el camino revisado; desde que está afuera, no vuelve a entrar
            boolean out = false;
            for (double[] p : trail) {
                // un centímetro de roce no es meterse: el loader deja margen alrededor de cada lugar
                boolean in = p[0] > 20.01 || Math.hypot(p[0] + 40, p[1]) < 19.99;
                assertTrue(!out || !in, "seed " + seed + " walked in at " + p[0] + " " + p[1]);
                out |= !in;
            }
            assertTrue(out, "seed " + seed);
        }
        // rodeado: da media vuelta
        assertEquals(Math.PI, Twister.avoid(0, 0, 0, 5, List.of(), (x, z) -> false), 1e-9);
    }

    @Test
    void debrisHitsNeverLeaveThePlayerLow() {
        assertEquals(2, Twister.hitDamage(20), 1e-6);
        assertEquals(2, Twister.hitDamage(10), 1e-6);
        assertEquals(0, Twister.hitDamage(9.5), 1e-6);
        assertEquals(0, Twister.hitDamage(Double.NaN), 1e-6);
    }

    @Test
    void destructionBreaksOnlyWhatTheServerAllows() {
        Twister.Kind t = Twister.Kind.TORNADO;
        Twister.Kind d = Twister.Kind.DUST_DEVIL;
        Twister.Destruction none = Twister.Destruction.NONE;
        Twister.Destruction natural = Twister.Destruction.NATURAL;
        Twister.Destruction all = Twister.Destruction.ALL;
        for (Twister.Kind k : Twister.Kind.values()) {
            for (Twister.BlockKind b : Twister.BlockKind.values()) {
                assertEquals(Twister.Effect.KEEP, Twister.effect(none, k, b), k + " " + b);
                // cofres, hornos, carteles, roca madre y lo duro nunca, en ningún modo
                assertEquals(Twister.Effect.KEEP, Twister.effect(all, k, Twister.BlockKind.PROTECTED));
                // la piedra, la tierra y los troncos tampoco: no cava zanjas
                assertEquals(Twister.Effect.KEEP, Twister.effect(all, k, Twister.BlockKind.OTHER));
            }
        }
        assertEquals(Twister.Effect.REMOVE, Twister.effect(natural, d, Twister.BlockKind.PLANT));
        assertEquals(Twister.Effect.KEEP, Twister.effect(natural, d, Twister.BlockKind.LEAVES));
        assertEquals(Twister.Effect.KEEP, Twister.effect(natural, d, Twister.BlockKind.GRASS));
        assertEquals(Twister.Effect.REMOVE, Twister.effect(natural, t, Twister.BlockKind.LEAVES));
        assertEquals(Twister.Effect.SCAR, Twister.effect(natural, t, Twister.BlockKind.GRASS));
        assertEquals(Twister.Effect.KEEP, Twister.effect(natural, t, Twister.BlockKind.BUILT));
        // con todo permitido, lo construido se rompe soltando lo que era, para que se pueda recuperar
        assertEquals(Twister.Effect.DROP, Twister.effect(all, t, Twister.BlockKind.BUILT));
        assertEquals(Twister.Effect.KEEP, Twister.effect(all, d, Twister.BlockKind.BUILT));
    }

    @Test
    void fallDamageGrowsWithTheHeightButNeverKills() {
        // de 20 bloques, 17 de daño como en vanilla
        assertEquals(17, Twister.fallDamage(17, 20), 1e-6);
        assertEquals(2, Twister.fallDamage(2, 20), 1e-6);
        // lo que mataría deja medio corazón
        assertEquals(19, Twister.fallDamage(40, 20), 1e-6);
        assertEquals(6, Twister.fallDamage(12, 7), 1e-6);
        assertEquals(0, Twister.fallDamage(12, 1), 1e-6);
        assertEquals(0, Twister.fallDamage(12, 0.5), 1e-6);
        assertEquals(0, Twister.fallDamage(Double.NaN, 20), 1e-6);
        assertEquals(19, Twister.fallDamage(Double.POSITIVE_INFINITY, 20), 1e-6);
        assertEquals(0, Twister.fallDamage(-5, 20), 1e-6);
        assertEquals(0, Twister.fallDamage(10, Double.NaN), 1e-6);
    }

    @Test
    void orbitAndFunnelStayInsideTheColumn() {
        for (Twister.Kind kind : Twister.Kind.values()) {
            Twister.Size s = Twister.roll(kind, rng(9));
            for (long tick : new long[]{0, 1, 777, Integer.MAX_VALUE, Long.MAX_VALUE}) {
                for (int count : new int[]{0, 1, 12}) {
                    for (int i = -1; i <= count; i++) {
                        double[] o = Twister.orbit(s, i, count, tick);
                        assertTrue(Double.isFinite(o[0]) && Double.isFinite(o[1]) && Double.isFinite(o[2]),
                                kind + " orbit " + i + "/" + count);
                        assertTrue(o[1] >= 1 && o[1] <= s.height(), "height " + o[1]);
                        assertTrue(Math.hypot(o[0], o[2]) <= 2 * s.radius() + 1e-6, "radius");
                    }
                    for (int i = -1; i <= count; i++) {
                        for (double grow : new double[]{0, 0.5, 1, Double.NaN, 7}) {
                            double[] f = Twister.funnel(s, tick, i, count, grow);
                            for (double v : f) {
                                assertTrue(Double.isFinite(v), kind + " funnel");
                            }
                            assertTrue(f[1] >= 0 && f[1] <= s.height(), "funnel height " + f[1]);
                            assertTrue(Math.hypot(f[0], f[2]) <= 2 * s.radius() + 1e-6, "funnel radius");
                        }
                    }
                }
            }
        }
    }

    @Test
    void debrisFillsTheWholeFunnelAndNeverBreaks() {
        for (Twister.Kind kind : Twister.Kind.values()) {
            Twister.Size s = Twister.roll(kind, rng(5));
            double low = Double.MAX_VALUE;
            double high = 0;
            for (long tick : new long[]{0, 13, 400, 9999, Integer.MAX_VALUE, Long.MAX_VALUE}) {
                for (int count : new int[]{0, 1, 16}) {
                    for (int i = -1; i <= count; i++) {
                        double[] d = Twister.debris(s, i, count, tick);
                        for (double v : d) {
                            assertTrue(Double.isFinite(v), kind + " debris " + i + "/" + count + " at " + tick);
                        }
                        assertTrue(d[1] >= 0.5 && d[1] <= s.height(), "height " + d[1]);
                        assertTrue(Math.hypot(d[0], d[2]) <= 2 * s.radius() + 1e-6, "radius");
                        if (count == 16) {
                            low = Math.min(low, d[1]);
                            high = Math.max(high, d[1]);
                        }
                    }
                }
            }
            // de abajo hasta cerca de la punta, no amontonados en una franja
            assertTrue(low < s.height() * 0.25 && high > s.height() * 0.7, kind + " " + low + " to " + high);
        }
    }

    @Test
    void scenesAreRegisteredWithTheConfiguredRain() {
        List<String> ids = Scenes.all().stream().map(sc -> sc.id()).toList();
        assertTrue(ids.contains(DustDevil.ID) && ids.contains(Tornado.ID));
        Context clear = day(Weather.CLEAR, Terrain.PLAINS);
        double off = Scenes.all(false).stream().filter(sc -> sc.id().equals(Tornado.ID)).findFirst().orElseThrow()
                .weight(clear, memory);
        assertEquals(0, off);
        assertTrue(new Tornado(true).cooldown() > new DustDevil().cooldown() && new DustDevil().cooldown() > 0);
    }
}
