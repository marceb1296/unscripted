package dev.unscripted.core.scene;

import dev.unscripted.core.Context;
import dev.unscripted.core.Difficulty;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Placement;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Time;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WanderingHorde implements Scene {
    public static final String ID = "wandering_horde";
    public static final String VILLAGE_DAMAGED = "village_damaged";
    public static final String VILLAGE_DEFENDED = "village_defended";
    static final int VILLAGE_RANGE = 160;
    static final int NIGHT_START = 13000;
    static final int NIGHT_END = 23000;
    public static final int MIN_NIGHT_LEFT = 6000;
    public static final int NEXT_WAVE_ALIVE = 3;
    public static final int WAVE_GAP = 1200;
    public static final int MAX_ALIVE = 16;
    public static final int START_MIN = 64;
    public static final int START_MAX = 96;
    public static final int SEEN_MIN = 30;
    public static final int SEEN_MAX = 60;
    public static final int HERO_TICKS = 48000;

    public enum Kind { ZOMBIE, HUSK, ZOMBIE_VILLAGER }

    /**
     * Cuántas oleadas y de qué tamaño: la primera de {@code first}, cada una {@code growth} más grande, más
     * {@code perPlayer} por cada jugador de más cerca de la aldea (hasta {@code maxExtraPlayers}) y
     * {@code fullMoonExtra} con luna llena. Valores de un archivo de configuración: se acotan.
     */
    public record Size(int waves, int first, int growth, int perPlayer, int maxExtraPlayers, int fullMoonExtra) {
        public static final int MAX_WAVES = 10;
        public static final int MAX_WAVE = 64;
        public static final Size DEFAULT = new Size(3, 6, 2, 2, 4, 2);

        public Size {
            waves = clamp(waves, 1, MAX_WAVES);
            first = clamp(first, 1, MAX_WAVE);
            growth = clamp(growth, 0, MAX_WAVE);
            perPlayer = clamp(perPlayer, 0, MAX_WAVE);
            maxExtraPlayers = clamp(maxExtraPlayers, 0, MAX_WAVE);
            fullMoonExtra = clamp(fullMoonExtra, 0, MAX_WAVE);
        }

        public int size(int index, boolean fullMoon, int extraPlayers) {
            long n = first + (long) growth * clamp(index, 0, waves - 1)
                    + (long) perPlayer * clamp(extraPlayers, 0, maxExtraPlayers)
                    + (fullMoon ? fullMoonExtra : 0);
            return (int) Math.min(n, MAX_WAVE);
        }

        public List<Kind> wave(int index, boolean desert, boolean fullMoon, int extraPlayers) {
            int n = size(index, fullMoon, extraPlayers);
            List<Kind> out = new ArrayList<>(n);
            out.add(Kind.ZOMBIE_VILLAGER);
            while (out.size() < n) {
                out.add(desert ? Kind.HUSK : Kind.ZOMBIE);
            }
            return out;
        }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    public record Outcome(boolean defended, boolean damaged) {
    }

    /**
     * Lo que le pasó a la horda y a la aldea. Cada miembro y cada aldeano cuenta una sola vez: el evento de
     * muerte de NeoForge puede llegar dos veces para el mismo mob.
     */
    public static final class Tally {
        private int members;
        private final Set<String> dead = new HashSet<>();
        private final Set<String> lost = new HashSet<>();
        private int playerKills;

        /** Entraron al mundo; con negativos, salieron sin morir (una oleada que no pudo llegar). */
        public void spawned(int n) {
            members = Math.max(0, members + n);
        }

        public int members() {
            return members;
        }

        /** Un miembro murió, o el jugador lo curó (aldeano zombie): cuenta como baja del jugador. */
        public void memberGone(String id, boolean byPlayer) {
            if (id != null && dead.add(id) && byPlayer) {
                playerKills++;
            }
        }

        public void villagerLost(String id) {
            if (id != null) {
                lost.add(id);
            }
        }

        public int deaths() {
            return dead.size();
        }

        public int playerKills() {
            return playerKills;
        }

        public int villagersLost() {
            return lost.size();
        }

        /**
         * Resistir hasta el amanecer también defiende la aldea: los jugadores mataron al menos la mitad de los
         * que llegaron y no se perdió ningún aldeano. Matar a uno y esconderse no alcanza.
         */
        public Outcome outcomeAtDawn() {
            return new Outcome(members > 0 && 2L * playerKills >= members && lost.isEmpty(), !lost.isEmpty());
        }

        /** Sin todas las oleadas adentro no hay defensa, aunque hayan muerto los que llegaron. */
        public Outcome outcome(boolean allWaves) {
            Outcome o = judge(members, dead.size(), playerKills, lost.size());
            return new Outcome(allWaves && o.defended(), o.damaged());
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int intensity() {
        return 3;
    }

    @Override
    public long cooldown() {
        return 48000;
    }

    @Override
    public double weight(Context c, Memory m) {
        if (!canHappen(c.dayTime(), c.difficulty()) || c.villageDistance() > VILLAGE_RANGE) {
            return 0;
        }
        long since = Time.ago(c.gameTime(), 2 * 24000L);
        if (m.count(VILLAGE_DAMAGED, c.x(), c.z(), 256, since) + m.count(VILLAGE_DEFENDED, c.x(), c.z(), 256, since) > 0) {
            return 0;
        }
        double w = 8;
        if (c.isFullMoon()) {
            w *= 2;
        }
        if (c.armor() < 5) {
            w *= 0.5;
        }
        return w;
    }

    /** Sin contar la aldea. El loader lo usa para no buscar campanas cuando la horda no puede ocurrir. */
    public static boolean canHappen(long dayTime, Difficulty difficulty) {
        return difficulty != Difficulty.PEACEFUL && nightLeft(dayTime) >= MIN_NIGHT_LEFT;
    }

    public static int nightLeft(long dayTime) {
        int t = (int) Math.floorMod(dayTime, 24000L);
        return t >= NIGHT_START && t < NIGHT_END ? NIGHT_END - t : 0;
    }

    public static boolean nextWaveDue(int alive, int nextSize, long sinceLastWave) {
        if (alive <= NEXT_WAVE_ALIVE) {
            return true;
        }
        return sinceLastWave >= WAVE_GAP && (long) alive + nextSize <= Math.max(MAX_ALIVE, nextSize);
    }

    public static List<Placement.Offset> rankStarts(List<Placement.Offset> aroundBell, int bellX, int bellZ,
                                                    int playerX, int playerZ, List<Placement.Offset> before) {
        double mid = (SEEN_MIN + SEEN_MAX) / 2.0;
        List<Placement.Offset> out = new ArrayList<>(aroundBell);
        out.sort(Comparator.comparingDouble(o -> {
            double dx = (double) bellX + o.dx() - playerX;
            double dz = (double) bellZ + o.dz() - playerZ;
            return sameSide(o, before) * 1e6 + Math.abs(Math.sqrt(dx * dx + dz * dz) - mid);
        }));
        return out;
    }

    public static int sameSide(Placement.Offset o, List<Placement.Offset> before) {
        int n = 0;
        for (Placement.Offset b : before) {
            if ((long) o.dx() * b.dx() + (long) o.dz() * b.dz() > 0) {
                n++;
            }
        }
        return n;
    }

    public static Outcome judge(int members, int deaths, int playerKills, int villagersLost) {
        boolean allDead = members > 0 && deaths >= members;
        return new Outcome(allDead && 2L * playerKills >= members, villagersLost > 0);
    }
}
