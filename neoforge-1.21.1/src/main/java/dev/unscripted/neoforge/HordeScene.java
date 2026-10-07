package dev.unscripted.neoforge;

import dev.unscripted.core.Lang;
import dev.unscripted.core.Config;
import dev.unscripted.core.Memory;
import dev.unscripted.core.Placement;
import dev.unscripted.core.scene.WanderingHorde;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Oleadas de zombies caminan de noche hacia la campana de una aldea; cada una llega desde otro lado. Al
 * acercarse, la campana suena y los aldeanos se esconden. Quien pelea con la horda se la lleva entera
 * detrás. Si los jugadores matan al menos la mitad y no queda ninguno de las tres oleadas, la aldea festeja
 * y los nombra héroes; si muere algún aldeano, queda dañada. Al amanecer se queman o se quitan.
 */
final class HordeScene implements ActiveScene {
    static final int START_CANDIDATES = 24;
    static final int START_STEP = 24;
    static final int SPREAD = 3;
    static final int SPREAD_TRIES = 8;
    static final int MIN_MEMBERS = 3;
    /** Vienen de lejos: vanilla no traza caminos más allá de su alcance de seguimiento (35). */
    static final int FOLLOW_RANGE = 128;
    static final double MARCH = 0.8;
    static final double CATCH_UP = 1.0;
    static final int STRAGGLER = 10;
    static final int FORMATION = 2;
    /** Una oleada que no se acerca a la campana durante este tiempo no puede llegar (agua, murallas). */
    static final int STUCK = 600;
    static final int MARCH_TIMEOUT = 3600;
    static final int GROAN = 160;
    static final int ALARM = 40;
    static final int RINGS = 3;
    static final int RING_GAP = 20;
    static final int HUNT = 16;
    static final int LOSE = 24;
    static final int WANDER = 16;
    static final int PROWL = 200;
    static final int NOTICE = 10;
    static final int JOIN = 64;
    static final int RELEASE = 80;
    static final int GOLEM_RANGE = 32;
    static final int WAVE_PLAYERS = 64;
    static final int WAVE_RETRIES = 10;
    static final int CELEBRATE = 200;
    static final int FIREWORKS = 8;
    static final int CELEBRANTS = 6;
    /** Un héroe más lejos que esto de la campana ve los fuegos cerca de él: la pelea suele terminar afuera. */
    static final int HERO_FAR = 40;
    static final int LOST_DISTANCE = 160;
    static final int BAR_RANGE = 96;
    static final float HORN_VOLUME = 64f;
    static final int LEAVE_VISIBLE = 60;
    static final int LEAVE_TIMEOUT = 6000;

    private enum Phase { RAID, CELEBRATE, LEAVE }

    private static final class Wave {
        final int index;
        final List<Mob> mobs;
        @Nullable
        final BlockPos start;
        boolean arrived;
        int marching;
        int lastProgress;
        double closest = Double.MAX_VALUE;

        Wave(int index, List<Mob> mobs, @Nullable BlockPos start) {
            this.index = index;
            this.mobs = mobs;
            this.start = start;
        }
    }

    private final ServerLevel level;
    private final UUID player;
    private final Memory memory;
    private final BlockPos bell;
    private final Vec3 bellCenter;
    private final boolean desert;
    private final boolean fullMoon;
    private final boolean small;
    private final WanderingHorde.Size size;
    private final boolean heroEffect;
    private final IntSupplier room;
    private boolean waitingRoom;
    private final List<Wave> waves = new ArrayList<>();
    private final Map<Mob, Wave> waveOf = new HashMap<>();
    private final List<Mob> alive = new ArrayList<>();
    private final List<Mob> withdrawn = new ArrayList<>();
    private final Set<Mob> members = new HashSet<>();
    private final WanderingHorde.Tally tally = new WanderingHorde.Tally();
    private final Map<LivingEntity, Integer> hurtSeen = new HashMap<>();
    private final Set<Player> fighters = new LinkedHashSet<>();
    private final Map<Mob, IronGolem> golemFights = new HashMap<>();
    private final Set<Mob> busy = new HashSet<>();
    private final Set<UUID> heroes = new HashSet<>();
    private Phase phase = Phase.RAID;
    private int ticks;
    private int phaseTicks;
    private int lastWaveAt;
    private int waveRetries;
    private int ringsLeft;
    private int nextRing;
    private int waypointAt = Integer.MIN_VALUE / 2;
    private boolean judged;
    /** Una barra por idioma: el nombre es el mismo para todos los que la ven. */
    private final ServerBossEvent barEn = new ServerBossEvent(Component.literal("Horde"), BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private final ServerBossEvent barEs = new ServerBossEvent(Component.literal("Horda"), BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private int barMax = 1;

    private HordeScene(ServerLevel level, UUID player, Memory memory, BlockPos bell, boolean small, Config config,
                       IntSupplier room) {
        this.level = level;
        this.player = player;
        this.memory = memory;
        this.bell = bell;
        this.bellCenter = Vec3.atBottomCenterOf(bell);
        this.desert = level.getBiome(bell).is(Tags.Biomes.IS_DESERT);
        this.fullMoon = level.getMoonPhase() == 0;
        this.small = small;
        this.size = config.horde();
        this.heroEffect = config.heroEffect();
        this.room = room;
    }

    @Nullable
    static HordeScene start(ServerPlayer target, Memory memory, boolean small, Config config, IntSupplier room) {
        ServerLevel level = target.serverLevel();
        int left = WanderingHorde.nightLeft(level.getDayTime());
        if (left < WanderingHorde.MIN_NIGHT_LEFT || level.getDifficulty() == Difficulty.PEACEFUL) {
            Unscripted.LOGGER.debug("Scene {}: not night, too little night left ({} ticks) or peaceful",
                    WanderingHorde.ID, left);
            return null;
        }
        BlockPos bell = WorldContext.bell(level, target.blockPosition()).orElse(null);
        if (bell == null || !level.isPositionEntityTicking(bell)) {
            Unscripted.LOGGER.debug("Scene {}: no village bell nearby{}", WanderingHorde.ID,
                    bell == null ? "" : " in an active area");
            return null;
        }
        HordeScene scene = new HordeScene(level, target.getUUID(), memory, bell, small, config, room);
        return scene.spawnWave(target) ? scene : null;
    }

    private boolean spawnWave(Player focus) {
        int index = waves.size();
        int extra = extraPlayers();
        List<WanderingHorde.Kind> kinds = size.wave(index, desert, fullMoon, extra);
        if (small) {
            // uno por jugador cerca: así se ve que la horda crece con más jugadores
            kinds = kinds.subList(0, Math.min(kinds.size(), 1 + extra));
        }
        int min = small ? 1 : Math.min(kinds.size(), MIN_MEMBERS);
        kinds = kinds.subList(0, Math.min(kinds.size(), room.getAsInt()));
        BlockPos start = startSpot(focus);
        if (start == null) {
            Unscripted.LOGGER.debug("Scene {}: no place for wave {} around the bell at {} {} {}",
                    WanderingHorde.ID, index + 1, bell.getX(), bell.getY(), bell.getZ());
            return false;
        }
        RandomSource random = level.getRandom();
        List<Mob> mobs = new ArrayList<>();
        for (WanderingHorde.Kind kind : kinds) {
            BlockPos pos = mobs.isEmpty() ? start : near(start, random);
            Mob m = pos == null ? null : spawn(kind, pos);
            if (m != null) {
                mobs.add(m);
            }
        }
        if (mobs.isEmpty() || mobs.size() < min) {
            mobs.forEach(SceneTargets::remove);
            Unscripted.LOGGER.debug("Scene {}: the zombies of wave {} did not fit around {} {} {}",
                    WanderingHorde.ID, index + 1, start.getX(), start.getY(), start.getZ());
            return false;
        }
        Wave w = new Wave(index, mobs, start);
        waves.add(w);
        for (Mob m : mobs) {
            waveOf.put(m, w);
        }
        alive.addAll(mobs);
        members.addAll(mobs);
        tally.spawned(mobs.size());
        lastWaveAt = ticks;
        barMax = Math.max(1, alive.size());
        barEn.setName(Component.literal("Horde: wave " + (index + 1) + " of " + size.waves()));
        barEs.setName(Component.literal("Horda: oleada " + (index + 1) + " de " + size.waves()));
        horn(start);
        announce(index);
        for (Mob m : mobs.subList(0, Math.min(2, mobs.size()))) {
            level.playSound(null, m.getX(), m.getY(), m.getZ(), groan(m), SoundSource.HOSTILE, 4f,
                    0.8f + random.nextFloat() * 0.2f);
        }
        Unscripted.LOGGER.debug("Scene {}: wave {} of {}, {} zombies{} ({} extra players) at {} {} {}, {} blocks from the bell and {} from {}",
                WanderingHorde.ID, index + 1, size.waves(), mobs.size(), desert ? " (husks)" : "", extra,
                start.getX(), start.getY(), start.getZ(), Math.round(Math.sqrt(start.distSqr(bell))),
                Math.round(Math.sqrt(start.distSqr(focus.blockPosition()))), focus.getName().getString());
        return true;
    }

    /** Una oleada que no pudo aparecer en varios intentos se salta: la escena no queda esperándola. */
    private void skipWave() {
        Unscripted.LOGGER.debug("Scene {}: skipping wave {}", WanderingHorde.ID, waves.size() + 1);
        Wave w = new Wave(waves.size(), new ArrayList<>(), null);
        w.arrived = true;
        waves.add(w);
        lastWaveAt = ticks;
    }

    private int extraPlayers() {
        int n = 0;
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(bellCenter) < (double) WAVE_PLAYERS * WAVE_PLAYERS) {
                n++;
            }
        }
        return Math.max(0, n - 1);
    }

    /**
     * A la distancia de la campana que obliga a caminar y del lado opuesto a las oleadas anteriores; mejor
     * donde el jugador los vea pasar, y entre esos, donde nadie los vea aparecer. Esconderse nunca le gana
     * al lado ni a la franja: solo desempata entre lugares igual de buenos.
     */
    @Nullable
    private BlockPos startSpot(Player focus) {
        BlockPos at = focus.blockPosition();
        List<Placement.Offset> before = new ArrayList<>();
        for (Wave w : waves) {
            if (w.start != null) {
                before.add(new Placement.Offset(w.start.getX() - bell.getX(), w.start.getZ() - bell.getZ()));
            }
        }
        List<Placement.Offset> ranked = WanderingHorde.rankStarts(Placement.candidates(level.getRandom()::nextDouble,
                WanderingHorde.START_MIN, WanderingHorde.START_MAX, START_CANDIDATES), bell.getX(), bell.getZ(),
                at.getX(), at.getZ(), before);
        BlockPos best = null;
        int bestSide = 0;
        boolean bestSeen = false;
        for (Placement.Offset o : ranked) {
            BlockPos p = Spots.ground(level, bell.getX() + o.dx(), bell.getZ() + o.dz(), bell.getY(), START_STEP);
            if (p == null || !Spots.fits(level, EntityType.ZOMBIE, p) || !level.isPositionEntityTicking(p)) {
                continue;
            }
            int side = WanderingHorde.sameSide(o, before);
            if (best == null) {
                best = p;
                bestSide = side;
                bestSeen = seen(at, p);
            } else if (side != bestSide || seen(at, p) != bestSeen) {
                continue;
            }
            if (Spots.hidden(level, p)) {
                return p;
            }
        }
        return best;
    }

    private static boolean seen(BlockPos player, BlockPos p) {
        double d = Math.sqrt(player.distSqr(new BlockPos(p.getX(), player.getY(), p.getZ())));
        return d >= WanderingHorde.SEEN_MIN && d <= WanderingHorde.SEEN_MAX;
    }

    @Nullable
    private BlockPos near(BlockPos start, RandomSource random) {
        for (int t = 0; t < SPREAD_TRIES; t++) {
            BlockPos p = Spots.ground(level, start.getX() + random.nextInt(2 * SPREAD + 1) - SPREAD,
                    start.getZ() + random.nextInt(2 * SPREAD + 1) - SPREAD, start.getY(), 2);
            if (p != null && Spots.fits(level, EntityType.ZOMBIE, p)) {
                return p;
            }
        }
        return null;
    }

    @Nullable
    private Mob spawn(WanderingHorde.Kind kind, BlockPos pos) {
        EntityType<? extends Zombie> type = switch (kind) {
            case ZOMBIE -> EntityType.ZOMBIE;
            case HUSK -> EntityType.HUSK;
            case ZOMBIE_VILLAGER -> EntityType.ZOMBIE_VILLAGER;
        };
        Zombie z = type.create(level);
        if (z == null) {
            return null;
        }
        z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
        EventHooks.finalizeMobSpawn(z, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT,
                new Zombie.ZombieGroupData(false, false));
        z.setBaby(false);
        if (!level.addFreshEntity(z)) {
            return null;
        }
        // lejos de todo jugador vanilla los quitaría a mitad de camino
        z.setPersistenceRequired();
        AttributeInstance follow = z.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) {
            follow.setBaseValue(FOLLOW_RANGE);
        }
        // pasear al azar o por la aldea les cambia el camino que les da la escena
        z.goalSelector.removeAllGoals(g -> g instanceof RandomStrollGoal || g instanceof MoveThroughVillageGoal);
        SceneTargets.claim(z);
        return z;
    }

    private static SoundEvent groan(Mob m) {
        return m instanceof ZombieVillager ? SoundEvents.ZOMBIE_VILLAGER_AMBIENT
                : m instanceof Husk ? SoundEvents.HUSK_AMBIENT : SoundEvents.ZOMBIE_AMBIENT;
    }

    @Override
    public String id() {
        return WanderingHorde.ID;
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public Vec3 center() {
        return bellCenter;
    }

    @Override
    public String phase() {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public int mobs() {
        return alive.size() + withdrawn.size();
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s, %d s, wave %d/%d, %d/%d alive, %d killed by players, %d villagers lost, %d fighting",
                phase.name().toLowerCase(Locale.ROOT), phaseTicks / 20, waves.size(), size.waves(), alive.size(),
                tally.members(), tally.playerKills(), tally.villagersLost(), fighters.size());
    }

    @Override
    public void died(LivingEntity dead, DamageSource source) {
        if (dead instanceof Mob m && members.contains(m)) {
            tally.memberGone(m.getStringUUID(), source.getEntity() instanceof Player);
            if (source.getEntity() instanceof Player p) {
                heroes.add(p.getUUID());
            }
        } else if (dead instanceof Villager && source.getEntity() instanceof Mob m && members.contains(m)) {
            int before = tally.villagersLost();
            tally.villagerLost(dead.getStringUUID());
            if (tally.villagersLost() > before) {
                Unscripted.LOGGER.debug("Scene {}: the horde killed a villager", WanderingHorde.ID);
            }
        }
    }

    @Override
    public void converted(LivingEntity from, LivingEntity to) {
        // solo un jugador cura a un aldeano zombie: cuenta como suya
        if (from instanceof Mob m && members.contains(m) && to instanceof Villager) {
            tally.memberGone(m.getStringUUID(), true);
            Unscripted.LOGGER.debug("Scene {}: a zombie villager from the horde was cured", WanderingHorde.ID);
        }
    }

    @Override
    public boolean tick() {
        boolean done = step();
        if (done || phase != Phase.RAID) {
            barEn.removeAllPlayers();
            barEs.removeAllPlayers();
        } else if (ticks % 20 == 0) {
            bars();
        }
        return done;
    }

    private boolean step() {
        ticks++;
        phaseTicks++;
        alive.removeIf(m -> !m.isAlive());
        withdrawn.removeIf(m -> !m.isAlive());
        for (Wave w : waves) {
            w.mobs.removeIf(m -> !m.isAlive());
        }
        hurtSeen.keySet().removeIf(e -> !e.isAlive());
        golemFights.keySet().removeIf(m -> !m.isAlive());
        Player focus = Spots.focus(level, player, bellCenter, LOST_DISTANCE);
        if (phase == Phase.RAID) {
            if (focus == null) {
                leave("no players nearby");
            } else if (WanderingHorde.nightLeft(level.getDayTime()) == 0) {
                dawn();
            } else if (alive.isEmpty() && waves.size() >= size.waves()) {
                if (!judge("the horde is gone", false)) {
                    return finish();
                }
                enter(Phase.CELEBRATE, "village defended");
            } else if (waves.size() < size.waves() && ticks % 20 == 0) {
                nextWave(focus);
            }
        }
        switch (phase) {
            case RAID -> {
                fights();
                march();
                village();
                rings();
                groans();
                walkAway(withdrawn, focus == null ? bellCenter : focus.position(), true);
            }
            case CELEBRATE -> {
                celebrate();
                walkAway(withdrawn, focus == null ? bellCenter : focus.position(), true);
                if (phaseTicks >= CELEBRATE) {
                    return finish();
                }
            }
            case LEAVE -> {
                fights();
                return leaving(focus == null ? null : focus.position());
            }
        }
        return false;
    }

    private void dawn() {
        if (!judge("dawn", true)) {
            leave("dawn");
            return;
        }
        alive.forEach(m -> SceneTargets.aim(m, null));
        withdrawn.addAll(alive);
        alive.clear();
        enter(Phase.CELEBRATE, "held out until dawn");
    }

    private void horn(BlockPos from) {
        long seed = level.getRandom().nextLong();
        for (ServerPlayer p : level.players()) {
            double dx = from.getX() + 0.5 - p.getX();
            double dz = from.getZ() + 0.5 - p.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 1e-3 || p.distanceToSqr(bellCenter) > (double) LOST_DISTANCE * LOST_DISTANCE) {
                continue;
            }
            p.connection.send(new ClientboundSoundPacket(SoundEvents.RAID_HORN, SoundSource.NEUTRAL,
                    p.getX() + 13 / d * dx, p.getY(), p.getZ() + 13 / d * dz, HORN_VOLUME, 1f, seed));
        }
    }

    private void bars() {
        Set<ServerPlayer> near = new HashSet<>();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(bellCenter) < (double) BAR_RANGE * BAR_RANGE) {
                near.add(p);
                boolean es = Lang.spanish(p.clientInformation().language());
                (es ? barEs : barEn).addPlayer(p);
                (es ? barEn : barEs).removePlayer(p);
            }
        }
        for (ServerBossEvent bar : List.of(barEn, barEs)) {
            for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
                if (!near.contains(p)) {
                    bar.removePlayer(p);
                }
            }
            bar.setProgress(Math.max(0f, Math.min(1f, alive.size() / (float) barMax)));
        }
    }

    private void nextWave(Player focus) {
        int next = size.size(waves.size(), fullMoon, extraPlayers());
        if (!WanderingHorde.nextWaveDue(alive.size(), next, ticks - lastWaveAt)) {
            return;
        }
        // con el tope de mobs de escenas lleno, la oleada espera en vez de perderse
        boolean full = room.getAsInt() < Math.max(1, Math.min(next, MIN_MEMBERS));
        if (full != waitingRoom) {
            waitingRoom = full;
            Unscripted.LOGGER.debug("Scene {}: wave {} {}", WanderingHorde.ID, waves.size() + 1,
                    full ? "waits: scene mob limit reached" : "has room now");
        }
        if (full) {
            return;
        }
        long t0 = System.nanoTime();
        boolean spawned = spawnWave(focus);
        SceneManager m = SceneManager.get();
        if (m != null) {
            m.timings().add("wandering_horde wave", System.nanoTime() - t0);
        }
        if (spawned) {
            waveRetries = 0;
        } else if (++waveRetries >= WAVE_RETRIES) {
            waveRetries = 0;
            skipWave();
        }
    }

    private void fights() {
        for (Mob m : alive) {
            LivingEntity hit = newHit(m);
            if (hit instanceof Player p && fighter(p)) {
                startFight(p, "hit a zombie");
            } else if (hit instanceof IronGolem g) {
                golemFights.put(m, g);
            }
        }
        if (ticks % 10 == 0) {
            for (Mob m : alive) {
                Player near = level.getNearestPlayer(m.getX(), m.getY(), m.getZ(), NOTICE,
                        EntitySelector.NO_CREATIVE_OR_SPECTATOR);
                if (near != null && m.hasLineOfSight(near)) {
                    startFight(near, "came close");
                }
            }
            fighters.removeIf(p -> {
                String why = p.isRemoved() || !fighter(p) ? "died, left or is in creative"
                        : alive.stream().noneMatch(m -> m.distanceToSqr(p) < (double) RELEASE * RELEASE)
                        ? "moved away from the horde" : null;
                if (why != null) {
                    Unscripted.LOGGER.debug("Scene {}: {} leaves the fight: {}", WanderingHorde.ID, p.getName().getString(), why);
                }
                return why != null;
            });
        }
        busy.clear();
        for (Mob m : alive) {
            LivingEntity target = nearestFighter(m);
            if (target == null) {
                IronGolem g = golemFights.get(m);
                if (g != null && g.isAlive() && m.distanceToSqr(g) < (double) GOLEM_RANGE * GOLEM_RANGE) {
                    target = g;
                } else {
                    golemFights.remove(m);
                }
            }
            if (target != null) {
                busy.add(m);
                if (m.getTarget() != target) {
                    // también cuando vanilla borra el objetivo al terminar una de sus metas internas
                    SceneTargets.aim(m, target);
                }
            }
        }
    }

    private void startFight(Player p, String why) {
        if (fighters.add(p)) {
            Unscripted.LOGGER.debug("Scene {}: {} fights the horde: {}", WanderingHorde.ID, p.getName().getString(), why);
        }
    }

    @Nullable
    private Player nearestFighter(Mob m) {
        Player best = null;
        double bestD = (double) JOIN * JOIN;
        for (Player p : fighters) {
            double d = m.distanceToSqr(p);
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    private static boolean fighter(Player p) {
        return p.isAlive() && !p.isCreative() && !p.isSpectator();
    }

    @Nullable
    private LivingEntity newHit(LivingEntity e) {
        int stamp = e.getLastHurtByMobTimestamp();
        Integer seen = hurtSeen.put(e, stamp);
        return seen != null && seen != stamp ? e.getLastHurtByMob() : null;
    }

    private void march() {
        for (Wave w : waves) {
            if (w.arrived) {
                continue;
            }
            if (w.mobs.isEmpty()) {
                w.arrived = true;
                continue;
            }
            List<Mob> free = w.mobs.stream().filter(m -> !busy.contains(m)).toList();
            if (free.isEmpty()) {
                w.lastProgress = w.marching;
                continue;
            }
            Mob head = free.get(0);
            double d = head.distanceToSqr(bellCenter);
            if (d < ALARM * ALARM) {
                w.arrived = true;
                ringsLeft = RINGS;
                nextRing = ticks;
                Unscripted.LOGGER.debug("Scene {}: wave {} reached the village ({} zombies)", WanderingHorde.ID,
                        w.index + 1, w.mobs.size());
                continue;
            }
            w.marching++;
            if (d < w.closest - 1) {
                w.closest = d;
                w.lastProgress = w.marching;
            }
            if (w.marching - w.lastProgress >= STUCK || w.marching >= MARCH_TIMEOUT) {
                withdraw(w, String.format(Locale.ROOT, "cannot reach the bell (%.0f blocks away)", Math.sqrt(d)));
                continue;
            }
            if (ticks % 20 == 0) {
                boolean wait = free.stream().anyMatch(m -> m != head && m.distanceToSqr(head) > STRAGGLER * STRAGGLER);
                SceneTargets.aim(head, null);
                if (wait) {
                    head.getNavigation().stop();
                } else {
                    head.getNavigation().moveTo(bellCenter.x, bellCenter.y, bellCenter.z, MARCH);
                }
                follow(head, free);
            }
        }
    }

    /** La oleada se va sin contar: no sirve que el terreno le niegue la defensa al jugador. */
    private void withdraw(Wave w, String why) {
        w.arrived = true;
        List<Mob> out = new ArrayList<>(w.mobs);
        w.mobs.clear();
        alive.removeAll(out);
        members.removeAll(out);
        out.forEach(m -> SceneTargets.aim(m, null));
        withdrawn.addAll(out);
        tally.spawned(-out.size());
        Unscripted.LOGGER.debug("Scene {}: wave {} retreats, {} ({} zombies)", WanderingHorde.ID, w.index + 1,
                why, out.size());
    }

    private void follow(Mob head, List<Mob> group) {
        int slot = 0;
        for (Mob m : group) {
            if (m == head) {
                continue;
            }
            double a = slot++ * 2 * Math.PI / Math.max(1, group.size() - 1);
            SceneTargets.aim(m, null);
            m.getNavigation().moveTo(head.getX() + Math.cos(a) * FORMATION, head.getY(),
                    head.getZ() + Math.sin(a) * FORMATION, CATCH_UP);
        }
    }

    /**
     * Los que llegaron rondan la aldea en manada detrás del primero; un zombie se separa por un aldeano
     * cercano que ve. Por uno escondido solo va el que puede romper la puerta (en Difícil, como en vanilla):
     * los demás se quedarían parados frente a la casa, lejos de la vista del jugador.
     */
    private void village() {
        if (ticks % 20 != 0) {
            return;
        }
        List<Mob> pack = new ArrayList<>();
        for (Mob m : alive) {
            Wave w = waveOf.get(m);
            if (w == null || !w.arrived || busy.contains(m)) {
                continue;
            }
            LivingEntity current = m.getTarget();
            boolean keep = current instanceof Villager && current.isAlive() && m.distanceToSqr(current) < LOSE * LOSE
                    && (m.hasLineOfSight(current) || m instanceof Zombie z && z.canBreakDoors()
                    && level.getDifficulty() == Difficulty.HARD);
            Villager v = keep ? (Villager) current : prey(m);
            if (v != null) {
                if (v != current) {
                    SceneTargets.aim(m, v);
                }
            } else {
                pack.add(m);
            }
        }
        if (pack.isEmpty()) {
            return;
        }
        Mob head = pack.get(0);
        SceneTargets.aim(head, null);
        if (head.getNavigation().isDone() || ticks - waypointAt > PROWL) {
            waypointAt = ticks;
            RandomSource random = level.getRandom();
            double a = random.nextDouble() * 2 * Math.PI;
            double r = random.nextDouble() * WANDER;
            head.getNavigation().moveTo(bellCenter.x + Math.cos(a) * r, bellCenter.y, bellCenter.z + Math.sin(a) * r, MARCH);
        }
        follow(head, pack);
    }

    @Nullable
    private Villager prey(Mob m) {
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, m.getBoundingBox().inflate(HUNT),
                v -> v.isAlive() && v.distanceToSqr(m) < HUNT * HUNT);
        Comparator<Villager> nearest = Comparator.comparingDouble(m::distanceToSqr);
        Villager seen = villagers.stream().filter(m::hasLineOfSight).min(nearest).orElse(null);
        boolean breaker = m instanceof Zombie z && z.canBreakDoors() && level.getDifficulty() == Difficulty.HARD;
        return seen != null || !breaker ? seen : villagers.stream().min(nearest).orElse(null);
    }

    private void rings() {
        if (ringsLeft > 0 && ticks >= nextRing) {
            ringsLeft--;
            nextRing = ticks + RING_GAP;
            ring();
        }
    }

    /** Como si alguien la tocara: los aldeanos a 32 bloques se esconden (vanilla), y se oye de lejos. */
    private void ring() {
        BlockState state = level.getBlockState(bell);
        if (state.getBlock() instanceof BellBlock && level.getBlockEntity(bell) instanceof BellBlockEntity be) {
            be.onHit(state.getValue(BellBlock.FACING));
            level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 5f, 1f);
        }
    }

    private void groans() {
        if (ticks % GROAN == 0 && !alive.isEmpty()) {
            Mob m = alive.get(level.getRandom().nextInt(alive.size()));
            level.playSound(null, m.getX(), m.getY(), m.getZ(), groan(m), SoundSource.HOSTILE, 3f,
                    0.8f + level.getRandom().nextFloat() * 0.2f);
        }
    }

    private boolean judge(String why, boolean dawn) {
        if (judged) {
            return false;
        }
        judged = true;
        WanderingHorde.Outcome o = dawn ? tally.outcomeAtDawn() : tally.outcome(waves.size() >= size.waves());
        long now = level.getGameTime();
        if (o.defended()) {
            memory.add(WanderingHorde.VILLAGE_DEFENDED, bell.getX(), bell.getZ(), now);
            for (UUID id : heroes) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                if (p != null && p.isAlive()) {
                    if (heroEffect) {
                        p.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, WanderingHorde.HERO_TICKS, 0,
                                false, false, true));
                    }
                    hail(p);
                }
            }
        }
        if (o.damaged()) {
            memory.add(WanderingHorde.VILLAGE_DAMAGED, bell.getX(), bell.getZ(), now);
        }
        Unscripted.LOGGER.debug("Scene {}: {} in wave {}: {}{} ({} of {} dead, {} by players, {} villagers lost, {} heroes)",
                WanderingHorde.ID, why, waves.size(), o.defended() ? "village defended" : "village not defended",
                o.damaged() ? ", damaged" : "", tally.deaths(), tally.members(), tally.playerKills(),
                tally.villagersLost(), o.defended() ? heroes.size() : 0);
        return o.defended();
    }

    private static void hail(ServerPlayer p) {
        String lang = p.clientInformation().language();
        title(p, Component.literal(Lang.pick(lang, "Village defended!", "¡Aldea defendida!")).withStyle(ChatFormatting.GOLD),
                Component.literal(Lang.pick(lang, "The villagers name you their hero", "Los aldeanos te nombran héroe")));
        p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1f, 1f);
    }

    private static void title(ServerPlayer p, Component title, Component subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
    }

    private void announce(int index) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(bellCenter) > (double) LOST_DISTANCE * LOST_DISTANCE) {
                continue;
            }
            String lang = p.clientInformation().language();
            if (index == 0) {
                title(p, Component.literal(Lang.pick(lang, "A horde is coming!", "¡Se acerca una horda!"))
                                .withStyle(ChatFormatting.RED),
                        Component.literal(Lang.pick(lang, "Defend the village", "Defiende la aldea")));
            } else {
                p.displayClientMessage(Component.literal(Lang.pick(lang,
                        "Wave " + (index + 1) + " of " + size.waves() + " is coming",
                        "Llega la oleada " + (index + 1) + " de " + size.waves())).withStyle(ChatFormatting.RED), true);
            }
        }
    }

    private void celebrate() {
        RandomSource random = level.getRandom();
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(48), Villager::isAlive);
        if (phaseTicks == 1) {
            villagers.stream().limit(CELEBRANTS).forEach(v -> level.playSound(null, v.getX(), v.getY(), v.getZ(),
                    SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 3f, 0.9f + random.nextFloat() * 0.2f));
            if (villagers.isEmpty()) {
                level.playSound(null, bell, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 3f, 1f);
            }
            ringsLeft = RINGS;
            nextRing = ticks;
        }
        rings();
        if (phaseTicks % 25 != 1 || phaseTicks / 25 >= FIREWORKS) {
            return;
        }
        List<Vec3> spots = new ArrayList<>();
        List<Villager> outside = villagers.stream().filter(v -> level.canSeeSky(v.blockPosition())).toList();
        if (!outside.isEmpty()) {
            Villager v = outside.get(random.nextInt(outside.size()));
            spots.add(new Vec3(v.getX(), v.getEyeY(), v.getZ()));
        } else {
            spots.add(skyAbove(bell.getX() + random.nextInt(13) - 6, bell.getZ() + random.nextInt(13) - 6));
        }
        List<String> far = new ArrayList<>();
        for (UUID id : heroes) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null && p.level() == level && p.distanceToSqr(bellCenter) > (double) HERO_FAR * HERO_FAR) {
                double a = random.nextDouble() * 2 * Math.PI;
                double r = 6 + random.nextDouble() * 6;
                spots.add(skyAbove((int) Math.floor(p.getX() + Math.cos(a) * r), (int) Math.floor(p.getZ() + Math.sin(a) * r)));
                far.add(p.getName().getString() + " at " + Math.round(Math.sqrt(p.distanceToSqr(bellCenter))));
            }
        }
        int added = 0;
        for (Vec3 at : spots) {
            DyeColor color = DyeColor.values()[random.nextInt(DyeColor.values().length)];
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + random.nextInt(2), List.of(new FireworkExplosion(
                    FireworkExplosion.Shape.BURST, IntList.of(color.getFireworkColor()), IntList.of(), false, false))));
            if (level.addFreshEntity(new FireworkRocketEntity(level, at.x, at.y, at.z, rocket))) {
                added++;
            }
        }
        if (phaseTicks == 1) {
            Vec3 first = spots.get(0);
            Player near = level.getNearestPlayer(first.x, first.y, first.z, -1, false);
            Unscripted.LOGGER.debug("Scene {}: celebration, {} villagers nearby ({} under open sky), rockets {} of {}, first at {} {} {} ({}), heroes far from the bell: {}",
                    WanderingHorde.ID, villagers.size(), outside.size(), added, spots.size(), Math.round(first.x),
                    Math.round(first.y), Math.round(first.z), near == null ? "no players"
                    : near.getName().getString() + " " + Math.round(Math.sqrt(near.distanceToSqr(first))) + " blocks away",
                    far.isEmpty() ? "none" : String.join(", ", far));
        }
    }

    private Vec3 skyAbove(int x, int z) {
        return new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1, z + 0.5);
    }

    private boolean finish() {
        if (withdrawn.isEmpty()) {
            Unscripted.LOGGER.debug("Scene {}: ended", WanderingHorde.ID);
            return true;
        }
        alive.addAll(withdrawn);
        withdrawn.clear();
        enter(Phase.LEAVE, "ended with zombies retreating");
        return false;
    }

    private void leave(String why) {
        judge(why, false);
        alive.addAll(withdrawn);
        withdrawn.clear();
        enter(Phase.LEAVE, why);
    }

    private boolean leaving(@Nullable Vec3 owner) {
        walkAway(alive, owner == null ? bellCenter : owner, phaseTicks >= LEAVE_VISIBLE);
        if (phaseTicks >= LEAVE_TIMEOUT) {
            stop();
        }
        if (alive.isEmpty()) {
            Unscripted.LOGGER.debug("Scene {}: ended", WanderingHorde.ID);
            return true;
        }
        return false;
    }

    private void walkAway(List<Mob> mobs, Vec3 from, boolean removeUnseen) {
        for (Mob m : mobs) {
            if (busy.contains(m) || !Spots.turn(ticks, m, 20)) {
                continue;
            }
            SceneTargets.aim(m, null);
            if (!m.getNavigation().isDone()) {
                continue;
            }
            Vec3 away = m.position().subtract(from).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            m.getNavigation().moveTo(m.getX() + away.x * 24, m.getY(), m.getZ() + away.z * 24, MARCH);
        }
        if (removeUnseen) {
            mobs.removeIf(m -> {
                if (!busy.contains(m) && Spots.turn(ticks, m, 20) && Spots.unseen(level, m)) {
                    SceneTargets.remove(m);
                    return true;
                }
                return false;
            });
        }
    }

    private void enter(Phase next, String why) {
        Unscripted.LOGGER.debug("Scene {}: {} to {} because {} ({} alive of {})", WanderingHorde.ID, phase, next, why,
                alive.size(), tally.members());
        phase = next;
        phaseTicks = 0;
        busy.clear();
    }

    @Override
    public void stop() {
        barEn.removeAllPlayers();
        barEs.removeAllPlayers();
        alive.forEach(SceneTargets::remove);
        alive.clear();
        withdrawn.forEach(SceneTargets::remove);
        withdrawn.clear();
    }
}
