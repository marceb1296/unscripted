package dev.unscripted.core.scene;

import dev.unscripted.core.Rng;
import dev.unscripted.core.Terrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class Twister {
    public static final String PASSED = "tornado_passed";
    public static final double REACH = 1.5;
    /** Un camino nunca dura más que esto (20 minutos), para no revisar bloques sin fin. */
    public static final int MAX_TICKS = 24000;
    public static final int EXIT = 48;
    static final double SUCK_MIN = 0.04;
    static final double SUCK_MAX = 0.45;
    /**
     * Cuánto de su avance suma la succión: al jugador se le pisa la velocidad un tick sí y uno no y el suelo
     * lo frena en el otro, así que hace falta algo más que la velocidad del tornado para que no se quede atrás.
     */
    static final double CARRY = 1.3;
    public static final double HIT = 2;
    static final int SWAY_PERIOD = 400;
    static final int STEP = 3;
    static final int TRIES = 16;
    static final double PULL = 0.15;
    static final double FLING = 0.5;
    static final double MAX_PUSH = 1.2;
    public static final double SAFE_HEALTH = 8;
    /** La caída quita según la altura, pero deja al menos medio corazón: una escena no mata a nadie. */
    public static final double LAST_HEALTH = 1;
    static final double TREASURE = 0.15;
    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));

    /**
     * Cuánto levanta y cuánto sostiene. Un servidor sin {@code allow-flight} echa al jugador que flota más
     * de 80 ticks seguidos: {@code playerHold} queda por debajo.
     */
    public enum Kind {
        DUST_DEVIL(2, 3, 15, 20, 60 * 20, 90 * 20, 4, 7, 30, 4, 40, 0.3, 0.3, 0.7, 1.0, 4, 6, 0.1, 0.2, 24, 48, 0, 96, 0.03),
        TORNADO(4, 6, 40, 60, 60 * 20, 90 * 20, 6, 20, 60, 14, 80, 0.55, 0.6, 1.4, 2.0, 8, 12, 0.35, 0.45, 12, 24, 4, 192,
                0.016);

        final double minRadius;
        final double maxRadius;
        final int minHeight;
        final int maxHeight;
        final int minTicks;
        final int maxTicks;
        public final double sway;
        public final double playerLift;
        private final int playerHold;
        public final double mobLift;
        public final int mobHold;
        final double spin;
        final double rise;
        final double maxWidth;
        final double maxMobHeight;
        final int minItems;
        final int maxItems;
        /** Bloques por tick; el que persigue va a {@code minSpeed} lejos de su presa y a {@code maxSpeed} cerca. */
        public final double minSpeed;
        public final double maxSpeed;
        public final int passMin;
        public final int passMax;
        /** Atrae desde este múltiplo del radio (0: no atrae). */
        final double suction;
        /** Persigue al jugador más cercano a {@code huntRange}, girando a lo sumo {@code turn} radianes por tick (0: sigue su camino). */
        public final int huntRange;
        public final double turn;

        Kind(double minRadius, double maxRadius, int minHeight, int maxHeight, int minTicks, int maxTicks,
             double sway, double playerLift, int playerHold, double mobLift, int mobHold, double spin, double rise,
             double maxWidth, double maxMobHeight, int minItems, int maxItems, double minSpeed, double maxSpeed,
             int passMin, int passMax, double suction, int huntRange, double turn) {
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.minTicks = minTicks;
            this.maxTicks = maxTicks;
            this.sway = sway;
            this.playerLift = playerLift;
            this.playerHold = playerHold;
            this.mobLift = mobLift;
            this.mobHold = mobHold;
            this.spin = spin;
            this.rise = rise;
            this.maxWidth = maxWidth;
            this.maxMobHeight = maxMobHeight;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.minSpeed = minSpeed;
            this.maxSpeed = maxSpeed;
            this.passMin = passMin;
            this.passMax = passMax;
            this.suction = suction;
            this.huntRange = huntRange;
            this.turn = turn;
        }

        public int playerHold() {
            return playerHold;
        }

        public int maxCarried() {
            return maxItems * 2;
        }
    }

    public enum Dust { SAND, RED_SAND, SNOW, AIR }

    public enum Ground {
        DESERT(Dust.SAND, "archaeology/desert_pyramid", "sand,sandstone,dead_bush",
                "dead_bush", "stick", "bone", "rabbit_hide", "string"),
        BADLANDS(Dust.RED_SAND, "chests/abandoned_mineshaft", "red_sand,terracotta,orange_terracotta,dead_bush",
                "dead_bush", "stick", "bone", "string"),
        BEACH(Dust.SAND, "archaeology/ocean_ruin_warm", "sand,sandstone,gravel",
                "kelp", "stick", "feather", "bone"),
        SNOWY(Dust.SNOW, "chests/village/village_snowy_house", "snow_block,packed_ice,spruce_leaves",
                "snowball", "stick", "feather", "spruce_sapling"),
        SAVANNA(Dust.AIR, "chests/village/village_savanna_house", "coarse_dirt,dirt,acacia_leaves,short_grass",
                "stick", "acacia_sapling", "wheat_seeds", "feather"),
        JUNGLE(Dust.AIR, "archaeology/trail_ruins_common", "dirt,jungle_leaves,moss_block",
                "stick", "jungle_sapling", "cocoa_beans", "bamboo"),
        SWAMP(Dust.AIR, "archaeology/trail_ruins_common", "mud,dirt,oak_leaves",
                "stick", "lily_pad", "vine", "feather"),
        TAIGA(Dust.AIR, "chests/village/village_taiga_house", "podzol,dirt,spruce_leaves",
                "stick", "spruce_sapling", "sweet_berries", "fern"),
        FOREST(Dust.AIR, "archaeology/trail_ruins_common", "dirt,grass_block,oak_leaves,birch_leaves",
                "stick", "oak_sapling", "birch_sapling", "apple"),
        PLAINS(Dust.AIR, "chests/village/village_plains_house", "dirt,grass_block,short_grass,oak_leaves",
                "stick", "wheat_seeds", "oak_sapling", "feather", "dandelion");

        private final Dust dust;
        private final String lootTable;
        private final List<String> debris;
        private final List<String> junk;

        Ground(Dust dust, String lootTable, String debris, String... junk) {
            this.dust = dust;
            this.lootTable = "minecraft:" + lootTable;
            this.debris = ids(debris.split(","));
            this.junk = ids(junk);
        }

        private static List<String> ids(String[] names) {
            List<String> out = new ArrayList<>();
            for (String n : names) {
                out.add("minecraft:" + n);
            }
            return List.copyOf(out);
        }

        public Dust dust() {
            return dust;
        }

        public List<String> junk() {
            return junk;
        }

        public String lootTable() {
            return lootTable;
        }

        public List<String> debris() {
            return debris;
        }
    }

    public record Size(Kind kind, double radius, int height, int ticks) {
        public Size {
            kind = kind == null ? Kind.DUST_DEVIL : kind;
            radius = Double.isFinite(radius) ? Math.max(1, Math.min(kind.maxRadius, radius)) : kind.minRadius;
            height = Math.max(1, Math.min(kind.maxHeight, height));
            ticks = Math.max(0, Math.min(MAX_TICKS, ticks));
        }

        public double reach() {
            return radius + REACH;
        }
    }

    public record Keep(double x, double z, double radius) {
    }

    /**
     * Una línea recta de {@code speed} bloques por tick, con un zigzag de {@code sway} bloques a los lados.
     */
    public record Path(double startX, double startZ, double dirX, double dirZ, double speed, int ticks, double sway) {
        public double[] at(long tick) {
            long t = Math.max(0, Math.min(ticks, tick));
            double along = speed * t;
            double side = sway * Math.sin(2 * Math.PI * t / SWAY_PERIOD);
            return new double[]{startX + dirX * along - dirZ * side, startZ + dirZ * along + dirX * side};
        }
    }

    public record Push(double x, double y, double z, boolean release) {
    }

    public record Cargo(String id, boolean table) {
    }

    @FunctionalInterface
    public interface Clear {
        boolean clear(int x, int z);
    }

    private Twister() {
    }

    public static Size roll(Kind kind, Rng rng) {
        Kind k = kind == null ? Kind.DUST_DEVIL : kind;
        return new Size(k, k.minRadius + unit(rng) * (k.maxRadius - k.minRadius),
                k.minHeight + (int) (unit(rng) * (k.maxHeight - k.minHeight + 1)),
                k.minTicks + (int) (unit(rng) * (k.maxTicks - k.minTicks + 1)));
    }

    /** El valor del azar entre 0 y 1, aunque la fuente devuelva NaN o se pase. */
    private static double unit(Rng rng) {
        double r = rng.nextDouble();
        return r >= 0 && r < 1 ? r : r >= 1 ? 0.999999 : 0;
    }

    public static Ground ground(Set<Terrain> terrain) {
        if (terrain == null) {
            return Ground.PLAINS;
        }
        if (terrain.contains(Terrain.BADLANDS)) {
            return Ground.BADLANDS;
        }
        if (terrain.contains(Terrain.DESERT)) {
            return Ground.DESERT;
        }
        if (terrain.contains(Terrain.SNOWY)) {
            return Ground.SNOWY;
        }
        if (terrain.contains(Terrain.BEACH)) {
            return Ground.BEACH;
        }
        if (terrain.contains(Terrain.SAVANNA)) {
            return Ground.SAVANNA;
        }
        if (terrain.contains(Terrain.JUNGLE)) {
            return Ground.JUNGLE;
        }
        if (terrain.contains(Terrain.SWAMP)) {
            return Ground.SWAMP;
        }
        if (terrain.contains(Terrain.TAIGA)) {
            return Ground.TAIGA;
        }
        if (terrain.contains(Terrain.FOREST)) {
            return Ground.FOREST;
        }
        return Ground.PLAINS;
    }

    public static boolean lifts(Kind kind, double width, double height) {
        return width > 0 && height > 0 && width <= kind.maxWidth && height <= kind.maxMobHeight;
    }

    /**
     * Un camino que empieza a {@code startMin} a {@code startMax} bloques del jugador y pasa a {@code passMin}
     * a {@code passMax} de él, sin acercarse a los lugares de {@code keep} ni a columnas que {@code clear}
     * rechaza. Vacío si no hay ninguno.
     */
    public static Optional<Path> plan(Rng rng, double px, double pz, Size size, int startMin, int startMax,
                                      int passMin, int passMax, List<Keep> keep, Clear clear) {
        if (!Double.isFinite(px) || !Double.isFinite(pz)) {
            return Optional.empty();
        }
        int lo = Math.max(1, Math.min(startMin, startMax));
        int hi = Math.max(lo, Math.max(startMin, startMax));
        int passLo = Math.max(0, Math.min(passMin, passMax));
        int passHi = Math.max(passLo, Math.max(passMin, passMax));
        Kind kind = size.kind();
        for (int i = 0; i < TRIES; i++) {
            double r = lo + unit(rng) * (hi - lo);
            // más que esto es una geometría imposible: el camino no puede pasar más lejos de lo que empieza
            double d = Math.min(passLo + unit(rng) * (passHi - passLo), r * 0.9);
            double angle = unit(rng) * 2 * Math.PI;
            double side = unit(rng) < 0.5 ? 1 : -1;
            double sx = px + Math.cos(angle) * r;
            double sz = pz + Math.sin(angle) * r;
            double turn = side * Math.asin(d / r);
            double tx = -Math.cos(angle);
            double tz = -Math.sin(angle);
            double dirX = tx * Math.cos(turn) - tz * Math.sin(turn);
            double dirZ = tx * Math.sin(turn) + tz * Math.cos(turn);
            double along = Math.sqrt(r * r - d * d);
            double speed = Math.min(kind.maxSpeed, Math.max(kind.minSpeed + unit(rng) * (kind.maxSpeed - kind.minSpeed),
                    2 * along / Math.max(1, size.ticks())));
            int ticks = (int) Math.min(size.ticks(), Math.ceil((2 * along + EXIT) / speed));
            Path path = new Path(sx, sz, dirX, dirZ, speed, ticks, kind.sway);
            if (free(path, size, keep, clear)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private static boolean free(Path p, Size size, List<Keep> keep, Clear clear) {
        double margin = p.sway() + size.reach() + 2 + STEP;
        double length = p.speed() * p.ticks();
        for (double a = -margin; a <= length + margin + 1e-9; a += STEP) {
            for (double l = -margin; l <= margin + 1e-9; l += STEP) {
                double x = p.startX() + p.dirX() * a - p.dirZ() * l;
                double z = p.startZ() + p.dirZ() * a + p.dirX() * l;
                if (keep != null) {
                    for (Keep k : keep) {
                        if (Math.hypot(x - k.x(), z - k.z()) < k.radius() + margin) {
                            return false;
                        }
                    }
                }
                if (!clear.clear((int) Math.floor(x), (int) Math.floor(z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * La velocidad para algo a ({@code dx}, {@code dz}) del centro y {@code above} bloques sobre el suelo, que
     * lleva {@code held} ticks agarrado: gira, se acerca a la columna y sube hasta {@code maxLift}. Al
     * cumplir {@code maxHold} lo suelta hacia afuera. Null si está fuera de alcance.
     */
    public static Push push(Size s, double dx, double dz, double above, int held, double maxLift, int maxHold) {
        return push(s, dx, dz, above, held, maxLift, maxHold, 0, 0);
    }

    public static Push push(Size s, double dx, double dz, double above, int held, double maxLift, int maxHold,
                            double vx, double vz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || !Double.isFinite(above)) {
            return null;
        }
        double dist = Math.hypot(dx, dz);
        if (dist > s.reach()) {
            return null;
        }
        // en el centro exacto no hay dirección: dividir por cero daría NaN
        double ux = dist < 1e-6 ? 1 : dx / dist;
        double uz = dist < 1e-6 ? 0 : dz / dist;
        double spin = s.kind().spin;
        if (held >= maxHold) {
            return clamp(ux * FLING - uz * spin * 0.5, 0.2, uz * FLING + ux * spin * 0.5, true);
        }
        double orbit = Math.max(1, s.radius() * 0.5);
        double in = Math.max(-0.3, Math.min(0.4, (dist - orbit) * PULL));
        double vy = above < maxLift ? s.kind().rise : above > maxLift + 1 ? -0.1 : 0;
        return clamp(-ux * in - uz * spin + finite(vx), vy, -uz * in + ux * spin + finite(vz), false);
    }

    private static Push clamp(double x, double y, double z, boolean release) {
        return new Push(Math.max(-MAX_PUSH, Math.min(MAX_PUSH, x)), Math.max(-MAX_PUSH, Math.min(MAX_PUSH, y)),
                Math.max(-MAX_PUSH, Math.min(MAX_PUSH, z)), release);
    }

    static double radiusAt(Size s, double h) {
        double f = Math.max(0, Math.min(1, h / s.height()));
        return s.kind() == Kind.TORNADO ? s.radius() * (0.4 + 1.6 * f) : s.radius() * (0.6 + 0.8 * f);
    }

    public static double[] orbit(Size s, int index, int count, long tick) {
        int n = Math.max(1, count);
        int i = Math.floorMod(index, n);
        double top = Math.max(1, s.height() * 0.6);
        double h = Math.min(top, 1 + (top - 1) * (i + 0.5) / n);
        double r = Math.max(1, radiusAt(s, h) * 0.8);
        double a = (tick % 100_000) * s.kind().spin / r + i * GOLDEN;
        return new double[]{Math.cos(a) * r, h, Math.sin(a) * r};
    }

    public static double[] funnel(Size s, long tick, int i, int n, double grow) {
        int count = Math.max(1, n);
        int k = Math.floorMod(i, count);
        double g = Double.isNaN(grow) ? 1 : Math.max(0, Math.min(1, grow));
        double h = s.height() * g * (k + 0.5) / count;
        double r = radiusAt(s, h) * (0.4 + 0.6 * g);
        double a = (tick % 100_000) * 0.35 + k * GOLDEN;
        return new double[]{Math.cos(a) * r, h, Math.sin(a) * r, r};
    }

    public static double[] debris(Size s, int index, int count, long tick) {
        int n = Math.max(1, count);
        int i = Math.floorMod(index, n);
        long t = tick % 100_000;
        double top = s.height() * 0.9;
        double bob = Math.sin(t * 0.03 + i * 1.7) * s.height() * 0.06;
        double h = Math.max(0.5, Math.min(top, 0.5 + (top - 0.5) * (i + 0.5) / n + bob));
        double r = radiusAt(s, h) * (0.7 + 0.3 * Math.sin(i * 2.3));
        double a = t * s.kind().spin * 1.2 / Math.max(r, 1) + i * GOLDEN;
        return new double[]{Math.cos(a) * r, h, Math.sin(a) * r};
    }

    public enum Destruction {
        NONE, NATURAL, ALL
    }

    /**
     * Cómo clasifica el loader un bloque. {@code PROTECTED}: con inventario o datos (cofres, carteles, camas),
     * irrompible o duro. {@code OTHER}: tierra, piedra, troncos.
     */
    public enum BlockKind { PLANT, LEAVES, GRASS, BUILT, OTHER, PROTECTED }

    /** {@code SCAR}: pasto que queda como tierra. {@code DROP}: se rompe soltando el bloque, que se puede recuperar. */
    public enum Effect { KEEP, REMOVE, SCAR, DROP }

    /** Qué le hace al bloque más alto de una columna a su paso. El remolino solo arranca plantas. */
    public static Effect effect(Destruction d, Kind kind, BlockKind block) {
        if (d == Destruction.NONE || block == BlockKind.PROTECTED || block == BlockKind.OTHER) {
            return Effect.KEEP;
        }
        if (block == BlockKind.PLANT) {
            return Effect.REMOVE;
        }
        if (kind != Kind.TORNADO) {
            return Effect.KEEP;
        }
        return switch (block) {
            case LEAVES -> Effect.REMOVE;
            case GRASS -> Effect.SCAR;
            case BUILT -> d == Destruction.ALL ? Effect.DROP : Effect.KEEP;
            default -> Effect.KEEP;
        };
    }

    /** Hasta esta distancia de su presa va a toda velocidad; desde {@link #SLOW} en adelante, a la mínima. */
    public static final double FAST = 16;
    public static final double SLOW = 64;

    public static final double LURE = 16;

    static final int LURE_IN_OUT = 600;
    static final int LURE_AROUND = 900;

    /**
     * El punto que sigue el remolino alrededor del jugador en ({@code px}, {@code pz}): da vueltas a su
     * alrededor y se acerca y se aleja, de encima de él hasta {@link #LURE}. Así ronda sin cazarlo.
     */
    public static double[] lure(long tick, double px, double pz, double phase) {
        double p = Double.isFinite(phase) ? phase : 0;
        long t = Math.floorMod(tick, (long) LURE_IN_OUT * LURE_AROUND);
        double r = LURE / 2 * (1 + Math.sin(2 * Math.PI * t / LURE_IN_OUT + p));
        double a = p + 2 * Math.PI * t / LURE_AROUND;
        return new double[]{px + Math.cos(a) * r, pz + Math.sin(a) * r};
    }

    public record Heading(double angle, double speed) {
    }

    public static int prey(double x, double z, double[][] players, double range) {
        int best = -1;
        double bestD = range;
        if (players == null) {
            return -1;
        }
        for (int i = 0; i < players.length; i++) {
            double[] p = players[i];
            if (p == null || p.length < 2) {
                continue;
            }
            double d = Math.hypot(p[0] - x, p[1] - z);
            if (d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * Gira de a poco hacia ({@code tx}, {@code tz}): lento lejos y rápido cerca. Al pasar de largo no puede dar
     * la vuelta en el lugar, así que vuelve en curva. Sin presa (NaN) sigue derecho a la velocidad mínima.
     */
    public static Heading steer(Kind kind, double x, double z, double angle, double tx, double tz) {
        double a = Double.isFinite(angle) ? angle : 0;
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(tx) || !Double.isFinite(tz)) {
            return new Heading(a, kind.minSpeed);
        }
        double want = Math.atan2(tz - z, tx - x);
        double diff = Math.IEEEremainder(want - a, 2 * Math.PI);
        a += Math.max(-kind.turn, Math.min(kind.turn, diff));
        double f = Math.max(0, Math.min(1, (SLOW - Math.hypot(tx - x, tz - z)) / (SLOW - FAST)));
        return new Heading(Math.IEEEremainder(a, 2 * Math.PI), kind.minSpeed + (kind.maxSpeed - kind.minSpeed) * f);
    }

    /**
     * El rumbo más parecido a {@code angle} con los próximos {@code ahead} bloques libres (columnas aceptadas
     * por {@code clear} y fuera de los lugares de {@code keep}): esquiva construcciones aunque tenga que girar
     * fuerte. Si todo está tomado, da media vuelta.
     */
    public static double avoid(double x, double z, double angle, double ahead, List<Keep> keep, Clear clear) {
        for (int i = 0; i <= 8; i++) {
            for (int side = 1; side >= -1; side -= 2) {
                double a = angle + side * i * Math.PI / 12;
                if (clear(x, z, a, ahead, keep, clear)) {
                    return a;
                }
                if (i == 0) {
                    break;
                }
            }
        }
        return angle + Math.PI;
    }

    private static boolean clear(double x, double z, double angle, double ahead, List<Keep> keep, Clear clear) {
        // cada bloque del trayecto: un giro cerrado puede cortar el borde de un círculo
        int n = (int) Math.max(1, Math.ceil(ahead));
        for (int i = 1; i <= n; i++) {
            if (!free(x + Math.cos(angle) * ahead * i / n, z + Math.sin(angle) * ahead * i / n, keep, clear)) {
                return false;
            }
        }
        return true;
    }

    private static boolean free(double x, double z, List<Keep> keep, Clear clear) {
        if (keep != null) {
            for (Keep k : keep) {
                if (Math.hypot(x - k.x(), z - k.z()) < k.radius()) {
                    return false;
                }
            }
        }
        return clear.clear((int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * Velocidad hacia adentro y girando para algo que está fuera de la columna pero dentro de la succión:
     * suave en el borde y más fuerte que un jugador corriendo cerca de la columna. Null fuera de ese anillo o
     * si no atrae.
     */
    public static Push suck(Size s, double dx, double dz) {
        return suck(s, dx, dz, 0, 0);
    }

    public static Push suck(Size s, double dx, double dz, double vx, double vz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
            return null;
        }
        double far = s.radius() * s.kind().suction;
        double dist = Math.hypot(dx, dz);
        if (dist <= s.reach() || dist > far) {
            return null;
        }
        double f = 1 - (dist - s.reach()) / (far - s.reach());
        double k = SUCK_MIN + (SUCK_MAX - SUCK_MIN) * f * Math.sqrt(f);
        double ux = dx / dist;
        double uz = dz / dist;
        return clamp(-ux * k - uz * k * 0.5 + finite(vx) * CARRY, 0, -uz * k + ux * k * 0.5 + finite(vz) * CARRY,
                false);
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    public static float hitDamage(double health) {
        return health - HIT >= SAFE_HEALTH ? (float) HIT : 0;
    }

    public static float fallDamage(double amount, double health) {
        if (!(amount > 0) || Double.isNaN(health)) {
            return 0;
        }
        return (float) Math.max(0, Math.min(amount, health - LAST_HEALTH));
    }

    public static List<Cargo> cargo(Ground ground, Kind kind, Rng rng) {
        int n = kind.minItems + (int) (unit(rng) * (kind.maxItems - kind.minItems + 1));
        List<Cargo> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (unit(rng) < TREASURE) {
                out.add(new Cargo(ground.lootTable(), true));
            } else {
                List<String> junk = ground.junk();
                out.add(new Cargo(junk.get((int) (unit(rng) * junk.size())), false));
            }
        }
        return out;
    }
}
