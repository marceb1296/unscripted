package dev.unscripted.neoforge;

import dev.unscripted.core.Config;
import dev.unscripted.core.Memory;
import dev.unscripted.core.scene.TraderInTrouble;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.UseItemGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Pillagers persiguen a un comerciante ambulante. Si el jugador ayuda y el comerciante sobrevive, le
 * rebaja todo a la mitad y le regala algo; si el jugador ataca al comerciante, los saqueadores se vuelven
 * contra él y no hay nada.
 */
final class TraderScene implements ActiveScene {
    static final int RING = 8;
    static final int LLAMAS = 2;
    static final int MIN_RAIDERS = 2;
    static final int MAX_RAIDERS = 3;
    static final int RAIDER_MIN = 24;
    static final int RAIDER_MAX = 32;
    static final double FLEE_SPEED = 0.75;
    static final int FLEE_DISTANCE = 12;
    static final int FLEE_CLOSE = 4;
    static final int FLEE_RANGE = 20;
    static final int FLEE_TRIES = 5;
    static final int AROUND_MIN = 6;
    static final int AROUND_MAX = 10;
    static final int AROUND_POINTS = 12;
    static final int AROUND_REACH = 48;
    static final int ATTACK_TIMEOUT = 2400;
    static final int THANK_TIMEOUT = 300;
    static final int DELIVER_RANGE = 32;
    static final int HAND_OVER = 3;
    static final double WALK = 0.6;
    static final int STAY = 1200;
    static final int AFTER_TRADE = 600;
    static final int FIGHT = 600;
    static final int FIGHT_RANGE = 32;
    static final int FOLLOW_RANGE = 64;
    /** Por si el comerciante queda en el mundo sin la escena (cierre abrupto): vanilla lo quita solo. */
    static final int DESPAWN_DELAY = 48000;
    static final int LEAVE_VISIBLE = 100;
    static final int LEAVE_TIMEOUT = 6000;
    static final int LOST_DISTANCE = 160;

    private enum Phase { ATTACK, THANK, STAY, FIGHT, LEAVE }

    private final ServerLevel level;
    private final UUID player;
    private final Memory memory;
    private final WanderingTrader trader;
    private final Vec3 home;
    private final List<TraderLlama> llamas;
    private final List<Mob> raiders;
    private final Map<LivingEntity, Integer> hurtSeen = new HashMap<>();
    private final Map<Mob, Fight> engaged = new HashMap<>();
    private final Map<UUID, Integer> helpHits = new HashMap<>();

    private record Fight(Player player, int since) {
    }
    private Phase phase = Phase.ATTACK;
    private int ticks;
    private int phaseTicks;
    private boolean helped;
    private boolean betrayed;
    @Nullable
    private Player attacker;
    private boolean traderGone;
    private int lastTrade = -1;
    private final boolean giveGift;
    private final boolean discount;

    private TraderScene(ServerLevel level, UUID player, Memory memory, WanderingTrader trader, List<TraderLlama> llamas,
                        List<Mob> raiders, Config config) {
        this.level = level;
        this.giveGift = config.traderGift();
        this.discount = config.traderDiscount();
        this.player = player;
        this.memory = memory;
        this.trader = trader;
        this.home = trader.position();
        this.llamas = llamas;
        this.raiders = raiders;
    }

    @Nullable
    static TraderScene start(ServerPlayer target, Memory memory, Config config) {
        ServerLevel level = target.serverLevel();
        RandomSource random = level.getRandom();
        BlockPos center = Spots.stage(level, target, config.distanceMin(), config.distanceMax(), RING, true,
                TraderInTrouble.ID);
        if (center == null || !Spots.fits(level, EntityType.WANDERING_TRADER, center)) {
            return null;
        }
        WanderingTrader trader = EntityType.WANDERING_TRADER.spawn(level, center, MobSpawnType.EVENT);
        if (trader == null) {
            return null;
        }
        trader.setDespawnDelay(DESPAWN_DELAY);
        // su huida de vanilla es lenta y a cualquier lado; la escena lo hace huir de los saqueadores
        trader.goalSelector.removeAllGoals(g -> g instanceof AvoidEntityGoal<?> || g instanceof PanicGoal);
        // de noche toma una poción de invisibilidad y el jugador lo pierde de vista
        trader.goalSelector.removeAllGoals(g -> g instanceof UseItemGoal<?>);
        List<TraderLlama> llamas = new ArrayList<>();
        for (int i = 0; i < LLAMAS; i++) {
            BlockPos pos = Spots.ground(level, center.getX() + random.nextInt(5) - 2, center.getZ() + random.nextInt(5) - 2,
                    center.getY(), 2);
            TraderLlama llama = pos == null || !Spots.fits(level, EntityType.TRADER_LLAMA, pos) ? null
                    : EntityType.TRADER_LLAMA.spawn(level, pos, MobSpawnType.EVENT);
            if (llama != null) {
                llama.setLeashedTo(trader, true);
                llamas.add(llama);
            }
        }
        List<Entity> side = new ArrayList<>(llamas);
        side.add(trader);
        SceneTargets.side(side);
        List<Mob> raiders = new ArrayList<>();
        int size = MIN_RAIDERS + random.nextInt(MAX_RAIDERS - MIN_RAIDERS + 1);
        for (int i = 0; i < size; i++) {
            // solo pillagers: un vindicator con hacha mata al jugador sin armadura de un golpe o dos
            EntityType<? extends Mob> type = EntityType.PILLAGER;
            BlockPos pos = Spots.arrival(level, target, center, RAIDER_MIN, RAIDER_MAX, type, random);
            Mob m = pos == null ? null : type.spawn(level, pos, MobSpawnType.EVENT);
            if (m != null) {
                AttributeInstance follow = m.getAttribute(Attributes.FOLLOW_RANGE);
                if (follow != null) {
                    follow.setBaseValue(FOLLOW_RANGE);
                }
                SceneTargets.claim(m);
                SceneTargets.aim(m, trader);
                raiders.add(m);
            }
        }
        if (raiders.size() < 2) {
            raiders.forEach(SceneTargets::remove);
            llamas.forEach(TraderLlama::discard);
            trader.discard();
            Unscripted.LOGGER.debug("Scene {}: no place for the raiders", TraderInTrouble.ID);
            return null;
        }
        level.playSound(null, trader.getX(), trader.getY(), trader.getZ(), SoundEvents.WANDERING_TRADER_HURT,
                SoundSource.NEUTRAL, 4f, 1f);
        Mob first = raiders.get(0);
        level.playSound(null, first.getX(), first.getY(), first.getZ(), SoundEvents.PILLAGER_CELEBRATE,
                SoundSource.HOSTILE, 4f, 1f);
        Unscripted.LOGGER.debug("Scene {}: trader at {} {} {}, {} raiders", TraderInTrouble.ID,
                center.getX(), center.getY(), center.getZ(), raiders.size());
        return new TraderScene(level, target.getUUID(), memory, trader, llamas, raiders, config);
    }

    @Override
    public String id() {
        return TraderInTrouble.ID;
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public Vec3 center() {
        return home;
    }

    @Override
    public String phase() {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public int mobs() {
        return (trader.isAlive() ? 1 : 0) + llamas.size() + raiders.size();
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s, %d s, trader %s, %d raiders%s%s", phase.name().toLowerCase(Locale.ROOT),
                phaseTicks / 20, trader.getRemovalReason() == Entity.RemovalReason.DISCARDED ? "gone"
                        : trader.isAlive() ? "alive" : "dead", raiders.size(), helped ? ", helped" : "",
                betrayed ? ", betrayed" : "");
    }

    @Override
    public boolean tick() {
        ticks++;
        phaseTicks++;
        raiders.removeIf(m -> !m.isAlive());
        llamas.removeIf(l -> !l.isAlive());
        hurtSeen.keySet().removeIf(e -> !e.isAlive() && e != trader);
        engaged.keySet().removeIf(m -> !m.isAlive());
        Player owner = Spots.focus(level, player, trader.position(), LOST_DISTANCE);
        if (phase != Phase.LEAVE && owner == null) {
            enter(Phase.LEAVE, "no players nearby");
        }
        hits();
        switch (phase) {
            case ATTACK -> attack();
            case THANK -> thank(helper());
            case STAY -> stay(helper());
            case FIGHT -> fight();
            case LEAVE -> {
                return leave(owner == null ? null : owner.position());
            }
        }
        return false;
    }

    private void hits() {
        if (!betrayed && newHit(trader) instanceof Player p) {
            betrayed = true;
            attacker = p;
            for (MerchantOffer offer : trader.getOffers()) {
                offer.setSpecialPriceDiff(0);
            }
            if (phase != Phase.LEAVE) {
                raiders.forEach(m -> SceneTargets.aim(m, p));
                enter(Phase.FIGHT, p.getName().getString() + " attacked the trader");
            }
        }
        for (Mob m : raiders) {
            if (newHit(m) instanceof Player p) {
                helped = true;
                helpHits.merge(p.getUUID(), 1, Integer::sum);
                SceneTargets.aim(m, p);
                if (engaged.put(m, new Fight(p, ticks)) == null) {
                    Unscripted.LOGGER.debug("Scene {}: {} hit a pillager, now targeting {}", TraderInTrouble.ID,
                            p.getName().getString(), m.getTarget() == null ? "nobody" : m.getTarget().getName().getString());
                }
            }
        }
        engaged.entrySet().removeIf(e -> {
            Mob m = e.getKey();
            Player p = e.getValue().player();
            String why = !p.isAlive() ? "the player died"
                    : p.isCreative() || p.isSpectator() ? "the player is in creative or spectator"
                    : ticks - e.getValue().since() > FIGHT ? "time is up"
                    : m.distanceToSqr(p) > (double) FIGHT_RANGE * FIGHT_RANGE ? "the player moved away" : null;
            if (why == null && m.getTarget() != p) {
                // vanilla borra el objetivo al terminar una de sus metas internas: la escena se lo devuelve
                SceneTargets.aim(m, p);
            }
            if (why != null) {
                SceneTargets.aim(m, null);
                Unscripted.LOGGER.debug("Scene {}: a pillager stops fighting the player: {}", TraderInTrouble.ID, why);
            }
            return why != null;
        });
    }

    /** El golpe que mata a un saqueador también es ayuda: en el tick siguiente ya no está en la lista. */
    @Override
    public void died(LivingEntity dead, DamageSource source) {
        if (dead instanceof Mob m && raiders.remove(m) && source.getEntity() instanceof Player p) {
            helped = true;
            helpHits.merge(p.getUUID(), 1, Integer::sum);
        }
    }

    @Nullable
    private LivingEntity newHit(LivingEntity e) {
        int stamp = e.getLastHurtByMobTimestamp();
        Integer seen = hurtSeen.put(e, stamp);
        return seen != null && seen != stamp ? e.getLastHurtByMob() : null;
    }

    private void attack() {
        if (!trader.isAlive()) {
            enter(Phase.LEAVE, "the trader was killed");
            return;
        }
        if (raiders.isEmpty()) {
            if (helped) {
                memory.add(TraderInTrouble.SAVED, trader.getBlockX(), trader.getBlockZ(), level.getGameTime());
                enter(Phase.THANK, "the player saved him");
            } else {
                enter(Phase.LEAVE, "he survived without the player's help");
            }
            return;
        }
        if (phaseTicks >= ATTACK_TIMEOUT) {
            enter(Phase.LEAVE, "time is up");
            return;
        }
        if (ticks % 10 == 0) {
            flee();
            for (Mob m : raiders) {
                if (!engaged.containsKey(m)) {
                    SceneTargets.aim(m, trader);
                }
            }
        }
    }

    private void flee() {
        Mob threat = raiders.stream().min(java.util.Comparator.comparingDouble(trader::distanceToSqr)).orElse(null);
        if (threat == null || !trader.getNavigation().isDone()
                && threat.distanceToSqr(trader) > FLEE_CLOSE * FLEE_CLOSE) {
            return;
        }
        Player owner = Spots.focus(level, player, trader.position(), AROUND_REACH);
        Vec3 to = owner != null ? around(owner, threat) : null;
        if (to == null) {
            to = nearHome(threat);
        }
        if (to == null) {
            Vec3 away = trader.position().subtract(threat.position()).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize().scale(FLEE_DISTANCE);
            to = trader.position().add(away);
        }
        trader.getNavigation().moveTo(to.x, to.y, to.z, FLEE_SPEED);
    }

    @Nullable
    private Vec3 nearHome(Mob threat) {
        Vec3 best = null;
        for (int i = 0; i < FLEE_TRIES; i++) {
            Vec3 p = DefaultRandomPos.getPosAway(trader, FLEE_DISTANCE, 7, threat.position());
            if (p != null && (best == null || p.distanceToSqr(home) < best.distanceToSqr(home))) {
                best = p;
            }
            if (best != null && best.distanceToSqr(home) < FLEE_RANGE * FLEE_RANGE) {
                break;
            }
        }
        return best;
    }

    @Nullable
    private Vec3 around(Player owner, Mob threat) {
        RandomSource random = level.getRandom();
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < AROUND_POINTS; i++) {
            double a = (i + random.nextDouble()) * 2 * Math.PI / AROUND_POINTS;
            double r = AROUND_MIN + random.nextDouble() * (AROUND_MAX - AROUND_MIN);
            BlockPos p = Spots.ground(level, (int) Math.floor(owner.getX() + Math.cos(a) * r),
                    (int) Math.floor(owner.getZ() + Math.sin(a) * r), owner.getBlockY(), 4);
            if (p == null) {
                continue;
            }
            Vec3 v = Vec3.atBottomCenterOf(p);
            double score = Math.sqrt(v.distanceToSqr(threat.position())) - 0.5 * Math.sqrt(v.distanceToSqr(trader.position()));
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    @Nullable
    private Player helper() {
        Player best = null;
        int most = 0;
        for (Map.Entry<UUID, Integer> e : helpHits.entrySet()) {
            Player p = level.getPlayerByUUID(e.getKey());
            if (p != null && !p.isSpectator() && e.getValue() > most
                    && p.distanceToSqr(trader.position()) <= (double) LOST_DISTANCE * LOST_DISTANCE) {
                best = p;
                most = e.getValue();
            }
        }
        return best != null ? best : Spots.focus(level, player, trader.position(), LOST_DISTANCE);
    }

    private void reward(boolean handed) {
        if (discount) {
            for (MerchantOffer offer : trader.getOffers()) {
                offer.setSpecialPriceDiff(-(offer.getBaseCostA().getCount() / 2));
            }
        }
        Player owner = helper();
        ItemStack gift = giveGift ? gift(owner) : ItemStack.EMPTY;
        boolean far = owner == null || trader.distanceToSqr(owner) > (double) DELIVER_RANGE * DELIVER_RANGE;
        if (!gift.isEmpty() && (handed || far)) {
            BehaviorUtils.throwItem(trader, gift, owner == null ? trader.position() : owner.position());
        } else if (!gift.isEmpty()) {
            ItemEntity item = new ItemEntity(level, owner.getX(), owner.getY() + 0.5, owner.getZ(), gift);
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
        level.playSound(null, trader.getX(), trader.getY(), trader.getZ(), SoundEvents.WANDERING_TRADER_YES,
                SoundSource.NEUTRAL, 1.5f, 1f);
        Unscripted.LOGGER.debug("Scene {}: {} for {} (hits: {})", TraderInTrouble.ID,
                !giveGift ? "no gift (disabled in the config)" : gift.isEmpty() ? "no gift" : "gift " + gift,
                owner == null ? "nobody" : owner.getName().getString(), helpHits.entrySet().stream()
                        .map(e -> {
                            Player p = level.getPlayerByUUID(e.getKey());
                            return (p == null ? e.getKey().toString() : p.getName().getString()) + " " + e.getValue();
                        })
                        .collect(java.util.stream.Collectors.joining(", ")));
    }

    private ItemStack gift(@Nullable Player owner) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SHIPWRECK_TREASURE);
        LootParams.Builder params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, trader.position());
        if (owner != null) {
            params.withParameter(LootContextParams.THIS_ENTITY, owner);
        }
        LootParams built = params.create(LootContextParamSets.CHEST);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < TraderInTrouble.GIFT_ROLLS; i++) {
            items.addAll(table.getRandomItems(built));
        }
        int best = TraderInTrouble.bestGift(items.stream().map(it -> new TraderInTrouble.Loot(
                BuiltInRegistries.ITEM.getKey(it.getItem()).toString(), it.getCount())).toList());
        return best < 0 ? ItemStack.EMPTY : items.get(best);
    }

    private void thank(@Nullable Player owner) {
        if (!trader.isAlive()) {
            enter(Phase.LEAVE, "the trader died");
            return;
        }
        boolean near = owner != null && trader.distanceToSqr(owner) < HAND_OVER * HAND_OVER;
        if (owner == null || near || phaseTicks >= THANK_TIMEOUT) {
            double d = owner == null ? -1 : Math.sqrt(trader.distanceToSqr(owner));
            reward(near);
            enter(Phase.STAY, near ? "gift delivered" : String.format(Locale.ROOT,
                    "did not reach the player (%.0f blocks away, %s)", d,
                    trader.getNavigation().isDone() ? "no path" : "on the way"));
            return;
        }
        if (ticks % 10 == 0) {
            trader.getNavigation().moveTo(owner, WALK);
        }
        trader.getLookControl().setLookAt(owner, 30f, 30f);
    }

    private void stay(@Nullable Player owner) {
        if (!trader.isAlive() || phaseTicks >= STAY) {
            enter(Phase.LEAVE, trader.isAlive() ? "the visit is over" : "the trader died");
            return;
        }
        if (trader.isTrading()) {
            lastTrade = ticks;
            return;
        }
        if (lastTrade >= 0 && ticks - lastTrade > AFTER_TRADE) {
            enter(Phase.LEAVE, "done trading");
            return;
        }
        trader.getNavigation().stop();
        if (owner != null && trader.distanceToSqr(owner) < 16 * 16) {
            trader.getLookControl().setLookAt(owner, 30f, 30f);
        }
    }

    private void fight() {
        Player target = attacker;
        boolean over = target == null || !target.isAlive() || raiders.isEmpty() || phaseTicks >= FIGHT
                || target.isCreative() || target.isSpectator()
                || raiders.stream().noneMatch(m -> m.distanceToSqr(target) < (double) FOLLOW_RANGE * FOLLOW_RANGE);
        if (over) {
            enter(Phase.LEAVE, "the fight is over");
            return;
        }
        for (Mob m : raiders) {
            if (m.getTarget() != target) {
                SceneTargets.aim(m, target);
            }
        }
    }

    private boolean leave(@Nullable Vec3 owner) {
        Vec3 from = owner == null ? trader.position() : owner;
        for (Mob m : raiders) {
            if (!engaged.containsKey(m) && Spots.turn(ticks, m, 20)) {
                SceneTargets.aim(m, null);
                if (m.getNavigation().isDone()) {
                    away(m, from, 1.0);
                }
            }
        }
        if (trader.isAlive() && !trader.isTrading() && Spots.turn(ticks, trader, 20) && trader.getNavigation().isDone()) {
            away(trader, from, 0.6);
        }
        if (phaseTicks >= LEAVE_VISIBLE) {
            raiders.removeIf(m -> {
                if (!engaged.containsKey(m) && Spots.turn(ticks, m, 20) && Spots.unseen(level, m)) {
                    SceneTargets.remove(m);
                    return true;
                }
                return false;
            });
            if (!traderGone && (!trader.isAlive() || !trader.isTrading() && Spots.turn(ticks, trader, 20)
                    && Spots.unseen(level, trader))) {
                removeTrader();
            }
            // una llama a la que se le cortó la cuerda huyendo también se va; queda como llama común si el
            // comerciante murió (quitarlo no cuenta como morir) o si el jugador la domó o la ató
            llamas.removeIf(l -> {
                if (trader.isDeadOrDying() || l.isTamed() || l.getLeashHolder() instanceof Player) {
                    return true;
                }
                if (l.getLeashHolder() != trader && Spots.turn(ticks, l, 20) && Spots.unseen(level, l)) {
                    l.discard();
                    return true;
                }
                return false;
            });
        }
        if (phaseTicks >= LEAVE_TIMEOUT) {
            stop();
        }
        if (raiders.isEmpty() && traderGone && llamas.isEmpty()) {
            Unscripted.LOGGER.debug("Scene {}: ended", TraderInTrouble.ID);
            return true;
        }
        return false;
    }

    private static void away(Mob m, Vec3 from, double speed) {
        Vec3 dir = m.position().subtract(from).multiply(1, 0, 1);
        dir = dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
        m.getNavigation().moveTo(m.getX() + dir.x * 24, m.getY(), m.getZ() + dir.z * 24, speed);
    }

    private void removeTrader() {
        traderGone = true;
        if (trader.isAlive()) {
            llamas.stream().filter(l -> l.getLeashHolder() == trader).forEach(TraderLlama::discard);
            trader.discard();
        }
    }

    private void enter(Phase next, String why) {
        Unscripted.LOGGER.debug("Scene {}: {} to {} because {} (trader {}, {} raiders{})", TraderInTrouble.ID,
                phase, next, why, trader.isAlive() ? "alive" : "dead", raiders.size(), helped ? ", helped" : "");
        phase = next;
        phaseTicks = 0;
    }

    @Override
    public void stop() {
        raiders.forEach(SceneTargets::remove);
        raiders.clear();
        if (!traderGone) {
            removeTrader();
        }
    }
}
