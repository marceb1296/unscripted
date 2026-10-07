package dev.unscripted.neoforge;

import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.scene.Scavengers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Carroñeros llegan a donde murió un animal grande, olfatean un rato y se van. Zorros y lobos huyen si
 * el jugador se acerca o los golpea; los zombies se vuelven zombies comunes.
 */
final class ScavengersScene implements ActiveScene {
    static final int MAX_MOBS = 4;
    static final int MIN_RING = 24;
    static final int MAX_RING = 32;
    static final int ARRIVE_TIMEOUT = 600;
    static final int ARRIVED = 3;
    static final int SNIFF = 400;
    static final int SNIFF_RADIUS = 4;
    static final double WALK = 1.0;
    static final int SHY = 10;
    static final double RUN = 1.5;
    static final int LEAVE_VISIBLE = 60;
    static final int LEAVE_TIMEOUT = 6000;
    static final int LOST_DISTANCE = 160;
    /** Metas de vanilla del zorro que lo echan a dormir de día o lo dejan agachado y le ganan a la caminata. Son privadas. */
    private static final Set<String> FOX_IDLE =
            Set.of("SleepGoal", "StalkPreyGoal", "FoxPounceGoal", "PerchAndSearchGoal", "SeekShelterGoal");

    private enum Phase { ARRIVE, SNIFF, LEAVE }

    private final ServerLevel level;
    private final UUID player;
    private final Scavengers.Variant variant;
    private final Vec3 site;
    private final List<Mob> mobs;
    private Phase phase = Phase.ARRIVE;
    /** Un jugador mató a uno de un golpe: ya no está en la lista para notar el golpe. */
    @Nullable
    private String killedBy;
    private int ticks;
    private int phaseTicks;
    private double leaveSpeed = WALK;

    private ScavengersScene(ServerLevel level, UUID player, Scavengers.Variant variant, Vec3 site, List<Mob> mobs) {
        this.level = level;
        this.player = player;
        this.variant = variant;
        this.site = site;
        this.mobs = mobs;
    }

    @Nullable
    static ScavengersScene start(ServerPlayer target, Context c, Memory memory) {
        ServerLevel level = target.serverLevel();
        Memory.Event remains = Scavengers.site(c, memory).orElse(null);
        if (remains == null) {
            Unscripted.LOGGER.debug("Scene {}: no recent remains nearby", Scavengers.ID);
            return null;
        }
        if (!level.hasChunkAt(new BlockPos(remains.x(), target.getBlockY(), remains.z()))) {
            Unscripted.LOGGER.debug("Scene {}: the remains are in an unloaded area", Scavengers.ID);
            return null;
        }
        BlockPos at = new BlockPos(remains.x(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, remains.x(), remains.z()), remains.z());
        Scavengers.Variant variant = Scavengers.variant(c, memory, remains);
        EntityType<? extends Mob> type = switch (variant) {
            case FOXES -> EntityType.FOX;
            case WOLVES -> EntityType.WOLF;
            case ZOMBIES -> EntityType.ZOMBIE;
        };
        RandomSource random = level.getRandom();
        int size = variant == Scavengers.Variant.ZOMBIES ? 2 + random.nextInt(MAX_MOBS - 1) : 2 + random.nextInt(2);
        List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BlockPos pos = Spots.arrival(level, target, at, MIN_RING, MAX_RING, type, random);
            Mob m = pos == null ? null : type.spawn(level, pos, MobSpawnType.EVENT);
            if (m != null) {
                if (variant == Scavengers.Variant.FOXES) {
                    m.goalSelector.removeAllGoals(g -> FOX_IDLE.contains(g.getClass().getSimpleName()));
                }
                SceneTargets.claim(m);
                mobs.add(m);
            }
        }
        if (mobs.size() < 2) {
            mobs.forEach(SceneTargets::remove);
            Unscripted.LOGGER.debug("Scene {}: no place for the scavengers around {} {}", Scavengers.ID,
                    remains.x(), remains.z());
            return null;
        }
        memory.add(Scavengers.SCAVENGED, remains.x(), remains.z(), level.getGameTime());
        float pitch = 0.9f + random.nextFloat() * 0.2f;
        switch (variant) {
            case FOXES -> level.playSound(null, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, SoundEvents.FOX_SCREECH,
                    SoundSource.NEUTRAL, 6f, pitch);
            case WOLVES -> Bedrock.howl(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 6f, pitch);
            case ZOMBIES -> level.playSound(null, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, SoundEvents.ZOMBIE_AMBIENT,
                    SoundSource.HOSTILE, 6f, pitch);
        }
        Unscripted.LOGGER.debug("Scene {}: {} {} heading to the remains at {} {} {}", Scavengers.ID, mobs.size(),
                variant.name().toLowerCase(Locale.ROOT), at.getX(), at.getY(), at.getZ());
        return new ScavengersScene(level, target.getUUID(), variant, Vec3.atBottomCenterOf(at), mobs);
    }

    @Override
    public String id() {
        return Scavengers.ID;
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public Vec3 center() {
        return site;
    }

    @Override
    public String phase() {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public int mobs() {
        return mobs.size();
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s, %d s, %d %s", phase.name().toLowerCase(Locale.ROOT), phaseTicks / 20,
                mobs.size(), variant.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean tick() {
        ticks++;
        phaseTicks++;
        mobs.removeIf(m -> !m.isAlive());
        Player owner = Spots.focus(level, player, site, LOST_DISTANCE);
        if (phase != Phase.LEAVE) {
            if (mobs.isEmpty()) {
                return scatter("no scavengers left", false);
            }
            if (owner == null) {
                return scatter("no players nearby", false);
            }
            String why = disturbed();
            if (why != null) {
                return scatter(why, true);
            }
        }
        switch (phase) {
            case ARRIVE -> arrive();
            case SNIFF -> {
                return sniff();
            }
            case LEAVE -> {
                if (ticks % 10 == 0 && leaveSpeed < RUN && disturbed() != null) {
                    leaveSpeed = RUN;
                    // el camino en curso es al paso: que lo recalculen corriendo en su turno
                    mobs.forEach(m -> m.getNavigation().stop());
                }
                return leave(owner == null ? null : owner.position());
            }
        }
        return false;
    }

    @Nullable
    private String disturbed() {
        if (killedBy != null) {
            return "hit by " + killedBy;
        }
        for (Mob m : mobs) {
            if (m.getLastHurtByMob() instanceof Player p) {
                return "hit by " + p.getName().getString();
            }
            Player near = level.getNearestPlayer(m.getX(), m.getY(), m.getZ(), SHY,
                    EntitySelector.NO_CREATIVE_OR_SPECTATOR);
            if (near != null) {
                return near.getName().getString() + " too close";
            }
        }
        return null;
    }

    private boolean scatter(String why, boolean run) {
        if (variant == Scavengers.Variant.ZOMBIES || mobs.isEmpty()) {
            Unscripted.LOGGER.debug("Scene {}: ended in {} because {} ({} released)", Scavengers.ID,
                    phase.name().toLowerCase(Locale.ROOT), why, mobs.size());
            mobs.forEach(SceneTargets::release);
            mobs.clear();
            return true;
        }
        leaveSpeed = run ? RUN : WALK;
        enter(Phase.LEAVE, why);
        return false;
    }

    private void arrive() {
        long arrived = mobs.stream().filter(m -> m.position().distanceToSqr(site) < ARRIVED * ARRIVED).count();
        if (arrived >= Math.max(1, mobs.size() - 1) || phaseTicks >= ARRIVE_TIMEOUT) {
            enter(Phase.SNIFF, arrived + " arrived");
            return;
        }
        if (ticks % 20 == 0) {
            mobs.forEach(m -> {
                SceneTargets.aim(m, null);
                m.getNavigation().moveTo(site.x, site.y, site.z, WALK);
            });
        }
    }

    private boolean sniff() {
        if (phaseTicks >= SNIFF) {
            if (variant == Scavengers.Variant.ZOMBIES) {
                return scatter("done sniffing", false);
            }
            enter(Phase.LEAVE, "done sniffing");
            return false;
        }
        RandomSource random = level.getRandom();
        for (Mob m : mobs) {
            SceneTargets.aim(m, null);
            if ((ticks + m.getId()) % 40 == 0) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = 1 + random.nextDouble() * (SNIFF_RADIUS - 1);
                m.getNavigation().moveTo(site.x + Math.cos(a) * r, site.y, site.z + Math.sin(a) * r, WALK * 0.8);
            }
            m.getLookControl().setLookAt(site.x, site.y - 0.5, site.z);
        }
        return false;
    }

    private boolean leave(@Nullable Vec3 owner) {
        Vec3 from = owner == null ? site : owner;
        for (Mob m : mobs) {
            if (Spots.turn(ticks, m, 20)) {
                SceneTargets.aim(m, null);
            }
            if (Spots.turn(ticks, m, 20) && m.getNavigation().isDone()) {
                Vec3 away = m.position().subtract(from).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                m.getNavigation().moveTo(m.getX() + away.x * 24, m.getY(), m.getZ() + away.z * 24, leaveSpeed);
            }
        }
        if (phaseTicks >= LEAVE_VISIBLE) {
            mobs.removeIf(m -> {
                if (Spots.turn(ticks, m, 20) && Spots.unseen(level, m)) {
                    SceneTargets.remove(m);
                    return true;
                }
                return false;
            });
        }
        if (phaseTicks >= LEAVE_TIMEOUT) {
            stop();
        }
        if (mobs.isEmpty()) {
            Unscripted.LOGGER.debug("Scene {}: ended", Scavengers.ID);
            return true;
        }
        return false;
    }

    private void enter(Phase next, String why) {
        Unscripted.LOGGER.debug("Scene {}: {} to {} because {} ({} {})", Scavengers.ID, phase, next, why, mobs.size(),
                variant.name().toLowerCase(Locale.ROOT));
        phase = next;
        phaseTicks = 0;
    }

    @Override
    public void died(LivingEntity dead, DamageSource source) {
        if (dead instanceof Mob m && mobs.contains(m) && source.getEntity() instanceof Player p) {
            killedBy = p.getName().getString();
        }
    }

    @Override
    public void stop() {
        mobs.forEach(SceneTargets::remove);
        mobs.clear();
    }
}
