package dev.unscripted.neoforge;

import dev.unscripted.core.Config;
import dev.unscripted.core.Memory;
import dev.unscripted.core.scene.WolfHunt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Una manada llega trotando, rodea a un rebaño y lo persigue. Cada lobo caza por su cuenta: el que
 * atrapa una oveja se queda a comer, el que falla se rinde y se suma a comer. Cuando ninguno caza, la
 * manada termina de comer y se va (con hambre si nadie cazó). Un golpe del jugador la espanta; dos, la
 * vuelven contra él.
 */
final class WolfHuntScene implements ActiveScene {
    static final int MIN_FLOCK = 4;
    static final int MAX_FLOCK = 6;
    static final int PACK_RING = 28;
    static final int STALK_RING = 9;
    static final int STALK_TIMEOUT = 600;
    static final double TROT = 1.0;
    static final int FLEE_RADIUS = 10;
    static final int FLEE_DISTANCE = 12;
    static final int FLEE_CLOSE = 4;
    static final int HUNT_TIMEOUT = 900;
    static final int CHASE_LIMIT = 300;
    static final int GIVE_UP_DISTANCE = 20;
    static final int NOTICE_DISTANCE = 24;
    /** La manada se reparte el rebaño: una oveja ya perseguida cuenta como esta distancia más lejos. */
    static final double SHARE_PENALTY = 10;
    static final int MAX_CHASERS = 2;
    static final int ATTEMPTS = 2;
    static final int PANT = 40;
    static final int BURST = 40;
    static final double BURST_SPEED = 1.8;
    static final double TIRED_SPEED = 1.15;
    static final int FEAST = 400;
    static final int SHY_RADIUS = 5;
    static final int FIGHT = 600;
    static final int FIGHT_RANGE = 32;
    /** Pasado este tiempo en retirada, se quitan aunque alguien los vea (casos raros: el jugador los sigue). */
    static final int LEAVE_TIMEOUT = 6000;
    static final int LOST_DISTANCE = 160;
    static final int LEAVE_VISIBLE = 100;

    private enum Phase { STALK, HUNT, FEAST, FIGHT, LEAVE }

    private final ServerLevel level;
    private final UUID player;
    private final Memory memory;
    private final BlockPos center;
    private final List<Wolf> wolves;
    private final List<Sheep> sheep;
    private Phase phase = Phase.STALK;
    private int ticks;
    private int phaseTicks;
    private int killed;
    private final Map<Wolf, Integer> chaseStart = new HashMap<>();
    private final Map<Wolf, Integer> failures = new HashMap<>();
    private final Map<Wolf, Integer> pantUntil = new HashMap<>();
    private final Set<Wolf> spent = new HashSet<>();
    private final Set<Wolf> fed = new HashSet<>();
    private final Map<Sheep, Integer> spooked = new HashMap<>();
    private final List<Vec3> kills = new ArrayList<>();
    private final Map<Wolf, Integer> hurtSeen = new HashMap<>();
    private int hits;
    private String hitSource = "";
    @Nullable
    private Player attacker;

    private WolfHuntScene(ServerLevel level, UUID player, Memory memory, BlockPos center, List<Wolf> wolves,
                          List<Sheep> sheep) {
        this.level = level;
        this.player = player;
        this.memory = memory;
        this.center = center;
        this.wolves = wolves;
        this.sheep = sheep;
    }

    @Nullable
    static WolfHuntScene start(ServerPlayer target, Memory memory, Config config) {
        ServerLevel level = target.serverLevel();
        RandomSource random = level.getRandom();
        BlockPos center = Spots.stage(level, target, config.distanceMin(), config.distanceMax(), STALK_RING, false,
                WolfHunt.ID);
        if (center == null) {
            return null;
        }
        List<Sheep> flock = flock(level, center, random);
        if (flock.size() < MIN_FLOCK) {
            flock.forEach(Sheep::discard);
            return null;
        }
        List<Wolf> pack = pack(level, target, center, flock.size() - 1, random);
        if (pack.size() < MIN_FLOCK - 1) {
            pack.forEach(Wolf::discard);
            flock.forEach(Sheep::discard);
            return null;
        }
        while (flock.size() > pack.size() + 1) {
            flock.remove(flock.size() - 1).discard();
        }
        Wolf first = pack.get(0);
        Bedrock.howl(level, first.getX(), first.getY(), first.getZ(), 6f, 0.9f + random.nextFloat() * 0.2f);
        return new WolfHuntScene(level, target.getUUID(), memory, center, pack, flock);
    }

    /**
     * Rebaño propio: nunca usar ovejas del mundo, que pueden ser de la granja de alguien. Son de la escena
     * hasta el final: solo quedan en el mundo las que el jugador salvó.
     */
    private static List<Sheep> flock(ServerLevel level, BlockPos center, RandomSource random) {
        List<Sheep> out = new ArrayList<>();
        int size = MIN_FLOCK + random.nextInt(MAX_FLOCK - MIN_FLOCK + 1);
        for (int i = 0; out.size() < size && i < size * 3; i++) {
            BlockPos pos = Spots.ground(level, center.getX() + random.nextInt(7) - 3, center.getZ() + random.nextInt(7) - 3,
                    center.getY(), 3);
            Sheep s = pos == null || !Spots.fits(level, EntityType.SHEEP, pos) ? null
                    : EntityType.SHEEP.spawn(level, pos, MobSpawnType.EVENT);
            if (s != null) {
                SceneTargets.claim(s);
                out.add(s);
            }
        }
        return out;
    }

    private static List<Wolf> pack(ServerLevel level, ServerPlayer target, BlockPos center, int size,
                                   RandomSource random) {
        List<Wolf> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BlockPos pos = Spots.arrival(level, target, center, PACK_RING, PACK_RING, EntityType.WOLF, random);
            Wolf w = pos == null ? null : EntityType.WOLF.spawn(level, pos, MobSpawnType.EVENT);
            if (w != null) {
                SceneTargets.claim(w);
                out.add(w);
            }
        }
        return out;
    }

    @Override
    public String id() {
        return WolfHunt.ID;
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public Vec3 center() {
        return Vec3.atCenterOf(center);
    }

    @Override
    public String phase() {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public int mobs() {
        return wolves.size() + sheep.size();
    }

    @Override
    public String describe() {
        return String.format(java.util.Locale.ROOT, "%s, %d s, %d wolves (%d eating, %d gave up), %d sheep, %d killed",
                phase.name().toLowerCase(java.util.Locale.ROOT), phaseTicks / 20, wolves.size(), fed.size(),
                spent.size(), sheep.size(), killed);
    }

    @Override
    public boolean tick() {
        ticks++;
        phaseTicks++;
        countLosses();
        Player owner = Spots.focus(level, player, Vec3.atCenterOf(center), LOST_DISTANCE);
        if (owner == null) {
            if (phase != Phase.LEAVE) {
                enter(Phase.LEAVE, "no players nearby");
            }
        }
        Player hitter = newHit();
        if (hitter != null) {
            hits++;
            attacker = hitter;
            if (hits == 1) {
                memory.add(WolfHunt.DRIVEN_OFF, center.getX(), center.getZ(), level.getGameTime());
                Wolf w = wolves.get(0);
                level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WOLF_GROWL, SoundSource.NEUTRAL, 1.5f, 1f);
                if (phase != Phase.LEAVE) {
                    enter(Phase.LEAVE, "hit by " + hitter.getName().getString() + " (" + hitSource + ")");
                }
            } else if (phase != Phase.FIGHT) {
                enter(Phase.FIGHT, "second hit by " + hitter.getName().getString() + " (" + hitSource + ")");
            }
        }
        wolves.removeIf(w -> !w.isAlive());
        sheep.removeIf(s -> !s.isAlive());
        chaseStart.keySet().removeIf(w -> !w.isAlive());
        spent.removeIf(w -> !w.isAlive());
        fed.removeIf(w -> !w.isAlive());
        failures.keySet().removeIf(w -> !w.isAlive());
        pantUntil.keySet().removeIf(w -> !w.isAlive());
        hurtSeen.keySet().removeIf(w -> !w.isAlive());
        spooked.keySet().removeIf(sh -> !sh.isAlive());
        switch (phase) {
            case STALK -> stalk();
            case HUNT -> hunt();
            case FEAST -> feast();
            case FIGHT -> fight();
            case LEAVE -> {
                return leave(owner == null ? null : owner.position());
            }
        }
        return false;
    }

    private void stalk() {
        calm();
        if (wolves.isEmpty() || sheep.isEmpty()) {
            enter(Phase.LEAVE, "no wolves or sheep");
            return;
        }
        Vec3 flock = flockCenter();
        long arrived = wolves.stream().filter(w -> w.position().distanceToSqr(flock) < sq(STALK_RING + 3)).count();
        if (arrived >= Math.max(1, wolves.size() - 1) || phaseTicks >= STALK_TIMEOUT) {
            Wolf w = wolves.get(0);
            Bedrock.howl(level, w.getX(), w.getY(), w.getZ(), 6f, 1.1f);
            enter(Phase.HUNT, "the pack arrived");
            return;
        }
        if (ticks % 20 != 0) {
            return;
        }
        for (Wolf w : wolves) {
            Vec3 dir = w.position().subtract(flock).multiply(1, 0, 1);
            dir = dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
            Vec3 to = flock.add(dir.scale(STALK_RING));
            w.getNavigation().moveTo(to.x, to.y, to.z, TROT);
            w.getLookControl().setLookAt(flock.x, flock.y, flock.z);
        }
    }

    private static double sq(double d) {
        return d * d;
    }

    private Vec3 flockCenter() {
        Vec3 sum = Vec3.ZERO;
        for (Sheep s : sheep) {
            sum = sum.add(s.position());
        }
        return sum.scale(1.0 / sheep.size());
    }

    private void hunt() {
        if (wolves.isEmpty()) {
            enter(Phase.LEAVE, "no wolves");
            return;
        }
        boolean timeUp = phaseTicks >= HUNT_TIMEOUT;
        if (sheep.isEmpty() || fed.size() + spent.size() >= wolves.size() || timeUp) {
            String why = sheep.isEmpty() ? "no sheep left" : timeUp ? "time is up" : "none still hunting";
            if (killed > 0) {
                memory.add(WolfHunt.FED, center.getX(), center.getZ(), level.getGameTime());
                enter(Phase.FEAST, why);
            } else {
                enter(Phase.LEAVE, why + ", no prey");
            }
            return;
        }
        for (Wolf w : wolves) {
            if (!hunting(w) || pantUntil.getOrDefault(w, 0) > ticks) {
                aim(w, null);
            }
        }
        if (ticks % 10 != 0) {
            return;
        }
        for (Wolf w : wolves) {
            if (!hunting(w)) {
                eat(w);
            } else if (w.getTarget() instanceof Sheep prey && prey.isAlive()) {
                int since = ticks - chaseStart.getOrDefault(w, ticks);
                if (since > CHASE_LIMIT || w.distanceToSqr(prey) > sq(GIVE_UP_DISTANCE)) {
                    aim(w, null);
                    chaseStart.remove(w);
                    int failed = failures.merge(w, 1, Integer::sum);
                    if (failed >= ATTEMPTS) {
                        spent.add(w);
                    } else {
                        pantUntil.put(w, ticks + PANT + level.getRandom().nextInt(PANT));
                        w.getNavigation().stop();
                    }
                }
            } else if (pantUntil.getOrDefault(w, 0) > ticks) {
                w.getNavigation().stop();
            } else {
                sheep.stream()
                        .filter(sh -> w.distanceToSqr(sh) < sq(NOTICE_DISTANCE) && chasers(sh) < MAX_CHASERS)
                        .min(Comparator.comparingDouble(sh -> Math.sqrt(w.distanceToSqr(sh)) + SHARE_PENALTY * chasers(sh)))
                        .ifPresentOrElse(prey -> {
                            aim(w, prey);
                            chaseStart.put(w, ticks);
                        }, () -> spent.add(w));
            }
        }
        flee(w -> hunting(w) && pantUntil.getOrDefault(w, 0) <= ticks ? FLEE_RADIUS : SHY_RADIUS);
    }

    private int chasers(Sheep sh) {
        int n = 0;
        for (Wolf w : wolves) {
            if (w.getTarget() == sh) {
                n++;
            }
        }
        return n;
    }

    private boolean hunting(Wolf w) {
        return !fed.contains(w) && !spent.contains(w);
    }

    private void eat(Wolf w) {
        Vec3 site = kills.stream().min(Comparator.comparingDouble(k -> k.distanceToSqr(w.position())))
                .orElseGet(this::restingCenter);
        if (w.position().distanceToSqr(site) > 4) {
            w.getNavigation().moveTo(site.x, site.y, site.z, TROT);
        }
        w.getLookControl().setLookAt(site.x, site.y, site.z);
    }

    private void feast() {
        calm();
        if (phaseTicks >= FEAST || wolves.isEmpty()) {
            enter(Phase.LEAVE, "the feast is over");
            return;
        }
        if (ticks % 10 != 0) {
            return;
        }
        wolves.forEach(this::eat);
        flee(w -> SHY_RADIUS);
    }

    private void fight() {
        Player target = attacker;
        boolean over = target == null || !target.isAlive() || wolves.isEmpty() || phaseTicks >= FIGHT
                || target.position().distanceToSqr(packCenter()) > sq(FIGHT_RANGE);
        if (over) {
            enter(Phase.LEAVE, "the fight is over");
            return;
        }
        for (Wolf w : wolves) {
            if (w.getTarget() != target) {
                aim(w, target);
            }
        }
    }

    private void flee(java.util.function.ToIntFunction<Wolf> radius) {
        for (Sheep sh : sheep) {
            Wolf threat = wolves.stream()
                    .filter(w -> w.distanceToSqr(sh) < sq(radius.applyAsInt(w)))
                    .min(Comparator.comparingDouble(sh::distanceToSqr)).orElse(null);
            if (threat == null) {
                spooked.remove(sh);
                continue;
            }
            boolean fresh = !spooked.containsKey(sh);
            int since = ticks - spooked.computeIfAbsent(sh, k -> ticks);
            if (!fresh && !sh.getNavigation().isDone() && threat.distanceToSqr(sh) > sq(FLEE_CLOSE)) {
                continue;
            }
            // un punto alcanzable lejos de la amenaza; en línea recta puede quedar contra una colina
            Vec3 to = DefaultRandomPos.getPosAway(sh, FLEE_DISTANCE, 7, threat.position());
            if (to == null) {
                Vec3 away = sh.position().subtract(threat.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize().scale(FLEE_DISTANCE);
                to = sh.position().add(away);
            }
            sh.getNavigation().moveTo(to.x, to.y, to.z, since < BURST ? BURST_SPEED : TIRED_SPEED);
        }
    }

    private Vec3 packCenter() {
        return centerOf(wolves);
    }

    private Vec3 restingCenter() {
        List<Wolf> idle = wolves.stream().filter(w -> !hunting(w)).toList();
        return centerOf(idle.isEmpty() ? wolves : idle);
    }

    private static Vec3 centerOf(List<Wolf> group) {
        Vec3 sum = Vec3.ZERO;
        for (Wolf w : group) {
            sum = sum.add(w.position());
        }
        return sum.scale(1.0 / group.size());
    }

    /** Se alejan del jugador hasta perderse de vista; nunca quedan como lobos comunes, que vanilla alertaría. */
    private boolean leave(@Nullable Vec3 owner) {
        calm();
        if (owner != null) {
            Vec3 from = owner;
            for (Wolf w : wolves) {
                if (!Spots.turn(ticks, w, 20) || !w.getNavigation().isDone()) {
                    continue;
                }
                Vec3 away = w.position().subtract(from).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                w.getNavigation().moveTo(w.getX() + away.x * 24, w.getY(), w.getZ() + away.z * 24, 1.2);
            }
        }
        if (ticks % 10 == 0) {
            flee(w -> SHY_RADIUS);
        }
        if (phaseTicks >= LEAVE_VISIBLE) {
            wolves.removeIf(w -> Spots.turn(ticks, w, 20) && gone(w));
            if (!saved()) {
                sheep.removeIf(s -> Spots.turn(ticks, s, 20) && gone(s));
            }
        }
        if (phaseTicks >= LEAVE_TIMEOUT) {
            stop();
        }
        if (wolves.isEmpty() && (saved() || sheep.isEmpty())) {
            Unscripted.LOGGER.debug("Scene {}: ended ({} sheep stay in the world)", WolfHunt.ID,
                    saved() ? sheep.size() : 0);
            stop();
            return true;
        }
        return false;
    }

    private boolean saved() {
        return hits > 0;
    }

    private boolean gone(Mob m) {
        if (Spots.unseen(level, m)) {
            SceneTargets.remove(m);
            return true;
        }
        return false;
    }

    private static void aim(Wolf w, @Nullable LivingEntity target) {
        SceneTargets.aim(w, target);
    }

    private void calm() {
        for (Wolf w : wolves) {
            aim(w, null);
            w.stopBeingAngry();
            w.setLastHurtByMob(null);
        }
    }

    @Nullable
    private Player newHit() {
        Player out = null;
        for (Wolf w : wolves) {
            int stamp = w.getLastHurtByMobTimestamp();
            if (w.getLastHurtByMob() instanceof Player p && hurtSeen.getOrDefault(w, Integer.MIN_VALUE) != stamp) {
                out = p;
                hitSource = w.getLastDamageSource() == null ? "?" : w.getLastDamageSource().getMsgId();
            }
            hurtSeen.put(w, stamp);
        }
        return out;
    }

    private void countLosses() {
        for (Sheep s : sheep) {
            if (!s.isAlive() && s.isDeadOrDying() && s.getLastHurtByMob() instanceof Wolf w && wolves.contains(w)) {
                killed++;
                kills.add(s.position());
                fed.add(w);
            }
        }
    }

    private void enter(Phase next, String why) {
        Unscripted.LOGGER.debug("Scene {}: {} to {} because {} ({} wolves, {} eating, {} gave up, {} sheep, {} killed)",
                WolfHunt.ID, phase, next, why, wolves.size(), fed.size(), spent.size(), sheep.size(), killed);
        phase = next;
        phaseTicks = 0;
    }

    @Override
    public void stop() {
        wolves.forEach(SceneTargets::remove);
        wolves.clear();
        sheep.forEach(saved() ? SceneTargets::release : SceneTargets::remove);
        sheep.clear();
    }
}
