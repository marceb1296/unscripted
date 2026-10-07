package dev.unscripted.neoforge;

import dev.unscripted.core.Config;
import dev.unscripted.core.Context;
import dev.unscripted.core.Memory;
import dev.unscripted.core.scene.DustDevil;
import dev.unscripted.core.scene.Tornado;
import dev.unscripted.core.scene.Twister;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.joml.Vector3f;

/**
 * Un remolino o un tornado que cruza a la vista del jugador: avisa con viento, aparece lejos, pasa a
 * caminata, levanta lo que encuentra y se deshace soltándolo todo. Nunca mata.
 */
final class TwisterScene implements ActiveScene {
    static final int START_MIN = 80;
    static final int START_MAX = 100;
    static final int BELL_KEEP = 48;
    static final int BED_KEEP = 32;
    static final int BELL_SEARCH = 192;
    static final int WARN_MIN = 400;
    static final int WARN_MAX = 600;
    static final int FORM = 100;
    static final int FOCUS = 192;
    static final int VIEW = 256;
    static final int FLEE = 40;
    static final int REGRAB = 60;
    /** Una caída dentro de este tiempo desde el último empujón es culpa del torbellino. */
    static final int CUSHION = 200;
    static final int CARGO_EVERY = 15;
    /** Bloques que giran adentro (entidades de bloque de vanilla: el cliente las dibuja sin el mod). */
    static final int DEBRIS_DUST_DEVIL = 6;
    static final int DEBRIS_TORNADO = 16;
    static final int DEBRIS_EVERY = 2;
    static final int CLEAR_CACHE = 100;
    static final int DETAIL = 64;
    /** Más cerca que esto, dentro o junto a la columna, las partículas tapan la pantalla: un tercio. */
    static final int CLOSE = 16;
    static final int RAVAGE_TORNADO = 10;
    static final int RAVAGE_DUST_DEVIL = 3;
    static final int LIGHTNING_EVERY = 120;
    static final int HIT_EVERY = 20;
    /** Objeto que gira adentro; si el mundo se guarda así, al cargarse vuelve a caer. */
    static final String CARRIED = "unscripted.carried";

    private static final Map<Entity, Long> FALLING = new WeakHashMap<>();

    private enum Phase { WARN, PASS }

    private final ServerLevel level;
    private final UUID owner;
    private final Twister.Size size;
    private final Twister.Path path;
    private final Twister.Ground ground;
    private final boolean liftPlayers;
    private final Twister.Destruction destruction;
    private final int warnTicks;
    private final List<Twister.Cargo> cargo;
    private final List<ItemEntity> carried = new ArrayList<>();
    private final List<Display.BlockDisplay> debris = new ArrayList<>();
    private final Map<Entity, Integer> held = new HashMap<>();
    private final Map<Entity, Long> letGo = new WeakHashMap<>();
    private Phase phase = Phase.WARN;
    private int ticks;
    private int phaseTicks;
    private double x;
    private double z;
    private double baseY;
    private int lifted;
    private int broken;
    private final List<Twister.Keep> keep;
    private final Map<Long, Boolean> clearCache = new HashMap<>();
    private double angle;
    private final double lurePhase;
    /** Cuánto avanzó en el último tick: lo que agarra se mueve con él. */
    private double moveX;
    private double moveZ;
    @Nullable
    private ServerPlayer hunted;
    private boolean swerved;

    private TwisterScene(ServerLevel level, UUID owner, Twister.Size size, Twister.Path path, Twister.Ground ground,
                         boolean liftPlayers, Twister.Destruction destruction, int warnTicks,
                         List<Twister.Cargo> cargo, double y, List<Twister.Keep> keep) {
        this.level = level;
        this.owner = owner;
        this.size = size;
        this.path = path;
        this.ground = ground;
        this.liftPlayers = liftPlayers;
        this.destruction = destruction;
        this.warnTicks = warnTicks;
        this.cargo = new ArrayList<>(cargo);
        this.keep = List.copyOf(keep);
        double[] at = path.at(0);
        x = at[0];
        z = at[1];
        angle = Math.atan2(path.dirZ(), path.dirX());
        lurePhase = level.getRandom().nextDouble() * 2 * Math.PI;
        baseY = y;
    }

    @Nullable
    static TwisterScene start(ServerPlayer player, Context c, Memory memory, Config config, Twister.Kind kind) {
        String id = id(kind);
        ServerLevel level = player.serverLevel();
        if (!level.canSeeSky(player.blockPosition().above())) {
            Unscripted.LOGGER.debug("Scene {}: the player is under a roof", id);
            return null;
        }
        RandomSource random = level.getRandom();
        Twister.Size size = Twister.roll(kind, random::nextDouble);
        // un servidor con mobGriefing apagado no quiere que nada rompa construcciones
        Twister.Destruction destruction = config.destruction() == Twister.Destruction.ALL
                && !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) ? Twister.Destruction.NATURAL
                : config.destruction();
        boolean all = destruction == Twister.Destruction.ALL;
        List<Twister.Keep> keep = new ArrayList<>();
        if (!all) {
            level.getPoiManager().findAll(type -> type.is(PoiTypes.MEETING), p -> true, player.blockPosition(),
                            BELL_SEARCH, PoiManager.Occupancy.ANY)
                    .forEach(b -> keep.add(new Twister.Keep(b.getX() + 0.5, b.getZ() + 0.5, BELL_KEEP)));
            for (ServerPlayer p : level.players()) {
                BlockPos bed = p.getRespawnPosition();
                if (bed != null && p.getRespawnDimension() == level.dimension()) {
                    keep.add(new Twister.Keep(bed.getX() + 0.5, bed.getZ() + 0.5, BED_KEEP));
                }
            }
        }
        int view = level.getServer().getPlayerList().getViewDistance() * 16 - 16;
        int startMax = Math.max(kind.passMax + 16, Math.min(START_MAX, view));
        int startMin = Math.min(START_MIN, startMax - 10);
        Optional<Twister.Path> plan = Twister.plan(random::nextDouble, player.getX(), player.getZ(), size, startMin,
                startMax, kind.passMin, kind.passMax, keep,
                (bx, bz) -> all ? level.hasChunk(bx >> 4, bz >> 4) : clear(level, bx, bz));
        if (plan.isEmpty()) {
            Unscripted.LOGGER.debug("Scene {}: no clear path around {} {} ({} bells and beds to avoid)", id,
                    player.getBlockX(), player.getBlockZ(), keep.size());
            return null;
        }
        Twister.Path path = plan.get();
        Twister.Ground ground = Twister.ground(c.terrain());
        int warn = WARN_MIN + random.nextInt(WARN_MAX - WARN_MIN + 1);
        boolean rain = false;
        if (kind == Twister.Kind.TORNADO && !level.isRaining() && config.startRain()) {
            // solo lluvia: con truenos vanilla deja aparecer monstruos de día alrededor de todos los jugadores
            level.setWeatherParameters(0, warn + size.ticks() + 1200, true, false);
            memory.add(Tornado.RAIN_STARTED, player.getBlockX(), player.getBlockZ(), level.getGameTime());
            rain = true;
        }
        double[] mid = closest(path, player.getX(), player.getZ());
        memory.add(Twister.PASSED, (int) Math.floor(mid[0]), (int) Math.floor(mid[1]), level.getGameTime());
        double[] at = path.at(0);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(at[0]),
                (int) Math.floor(at[1]));
        List<Twister.Cargo> cargo = Twister.cargo(ground, kind, random::nextDouble);
        boolean hunts = kind.turn > 0;
        Unscripted.LOGGER.debug("Scene {}: {} over {}, from {} {} passing {} blocks from {}, {} blocks/s, {} s, breaks {}{}", id,
                String.format(Locale.ROOT, "radius %.1f height %d", size.radius(), size.height()),
                ground.name().toLowerCase(Locale.ROOT), (int) at[0], (int) at[1],
                (int) Math.hypot(mid[0] - player.getX(), mid[1] - player.getZ()), player.getName().getString(),
                hunts ? String.format(Locale.ROOT, "%.1f to %.1f", kind.minSpeed * 20, kind.maxSpeed * 20)
                        : String.format(Locale.ROOT, "%.1f", path.speed() * 20),
                (hunts ? size.ticks() : path.ticks()) / 20,
                destruction.name().toLowerCase(Locale.ROOT), rain ? ", rain started" : "");
        return new TwisterScene(level, player.getUUID(), size, path, ground, config.liftPlayers(), destruction, warn,
                cargo, y, keep);
    }

    static String id(Twister.Kind kind) {
        return kind == Twister.Kind.TORNADO ? Tornado.ID : DustDevil.ID;
    }

    private static double[] closest(Twister.Path path, double px, double pz) {
        double[] best = path.at(0);
        for (int t = 0; t <= path.ticks(); t += 20) {
            double[] at = path.at(t);
            if (Math.hypot(at[0] - px, at[1] - pz) < Math.hypot(best[0] - px, best[1] - pz)) {
                best = at;
            }
        }
        return best;
    }

    static boolean clear(ServerLevel level, int bx, int bz) {
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            return false;
        }
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= 3; dy++) {
            if (built(level.getBlockState(pos.set(bx, top - dy, bz)))) {
                return false;
            }
        }
        return true;
    }

    static boolean built(BlockState s) {
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.BEDS)
                || s.is(BlockTags.DOORS) || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES)
                || s.is(BlockTags.WALLS) || s.is(BlockTags.ALL_SIGNS) || s.is(BlockTags.STAIRS)
                || s.is(BlockTags.SLABS) || s.is(BlockTags.CROPS) || s.is(BlockTags.CAMPFIRES)
                || s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES) || s.is(Tags.Blocks.CHESTS)
                || s.is(Tags.Blocks.BARRELS) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN)
                || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.FARMLAND);
    }

    @Override
    public String id() {
        return id(size.kind());
    }

    @Override
    public UUID player() {
        return owner;
    }

    @Override
    public Vec3 center() {
        return new Vec3(x, baseY, z);
    }

    @Override
    public String phase() {
        return phase.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public int mobs() {
        return 0;
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s, %d s, at %d %d, %d items, %d blocks, %d lifted, %d broken", phase(),
                phaseTicks / 20, (int) x, (int) z, carried.size(), debris.size(), lifted, broken);
    }

    @Override
    public boolean tick() {
        ticks++;
        phaseTicks++;
        if (Spots.focus(level, owner, center(), FOCUS) == null) {
            finish("no players nearby", false);
            return true;
        }
        if (phase == Phase.WARN) {
            warn();
            if (phaseTicks >= warnTicks) {
                Unscripted.LOGGER.debug("Scene {}: forming at {} {}", id(), (int) x, (int) z);
                phase = Phase.PASS;
                phaseTicks = 0;
            }
            return false;
        }
        return pass();
    }

    private boolean tornado() {
        return size.kind() == Twister.Kind.TORNADO;
    }

    private void warn() {
        RandomSource random = level.getRandom();
        float progress = (float) phaseTicks / warnTicks;
        if (phaseTicks % 40 == 1) {
            level.playSound(null, x, baseY + 4, z, SoundEvents.ELYTRA_FLYING, SoundSource.WEATHER,
                    (tornado() ? 5f : 3f) + 3f * progress, 0.5f + 0.15f * random.nextFloat());
        }
        if (phaseTicks % 60 == 30) {
            level.playSound(null, x, baseY + 2, z, SoundEvents.BREEZE_IDLE_GROUND, SoundSource.WEATHER,
                    4f + 4f * progress, 0.6f);
        }
        if (tornado()) {
            distantThunder();
        }
        if (phaseTicks % 40 == 0) {
            flee();
        }
    }

    private void distantThunder() {
        if (level.isThundering() || ticks % 20 != 0 || level.getRandom().nextInt(8) != 0) {
            return;
        }
        level.playSound(null, x, baseY + 30, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10f,
                0.7f + 0.2f * level.getRandom().nextFloat());
    }

    private boolean pass() {
        boolean hunts = size.kind().turn > 0;
        if (phaseTicks > (hunts ? size.ticks() : path.ticks())) {
            finish(hunts ? "blew itself out" : "passed", true);
            return true;
        }
        double[] at = hunts ? hunt() : path.at(phaseTicks);
        int bx = (int) Math.floor(at[0]);
        int bz = (int) Math.floor(at[1]);
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            finish("reached an unloaded area", true);
            return true;
        }
        // el primer tick salta desde el lugar del aviso: no cuenta como avance
        moveX = phaseTicks == 0 ? 0 : at[0] - x;
        moveZ = phaseTicks == 0 ? 0 : at[1] - z;
        x = at[0];
        z = at[1];
        double floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        baseY += Math.max(-0.5, Math.min(0.5, floor - baseY));
        double grow = Math.min(1, phaseTicks / (double) FORM);
        draw(grow);
        sounds();
        if (grow >= 0.5) {
            lift();
            if (destruction != Twister.Destruction.NONE) {
                ravage(tornado() ? RAVAGE_TORNADO : RAVAGE_DUST_DEVIL);
            }
            if (tornado() && level.getRandom().nextInt(LIGHTNING_EVERY) == 0) {
                lightning();
            }
        }
        whirl(grow);
        if (!cargo.isEmpty() && grow >= 0.3 && phaseTicks % CARGO_EVERY == 0) {
            spawn(cargo.remove(cargo.size() - 1));
        }
        carry();
        if (phaseTicks % 40 == 0) {
            flee();
        }
        return false;
    }

    private double[] hunt() {
        Twister.Kind kind = size.kind();
        List<ServerPlayer> prey = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && !p.isCreative() && !p.isSpectator()) {
                prey.add(p);
            }
        }
        double[][] at = new double[prey.size()][];
        for (int i = 0; i < at.length; i++) {
            at[i] = new double[]{prey.get(i).getX(), prey.get(i).getZ()};
        }
        int i = Twister.prey(x, z, at, kind.huntRange);
        ServerPlayer target = i < 0 ? null : prey.get(i);
        if (target != hunted) {
            hunted = target;
            Unscripted.LOGGER.debug("Scene {}: now after {}", id(), target == null ? "nobody" : target.getName().getString());
        }
        double[] goal = target == null ? new double[]{Double.NaN, Double.NaN}
                : tornado() ? new double[]{target.getX(), target.getZ()}
                : Twister.lure(phaseTicks, target.getX(), target.getZ(), lurePhase);
        Twister.Heading h = Twister.steer(kind, x, z, angle, goal[0], goal[1]);
        if (ticks % CLEAR_CACHE == 0) {
            clearCache.clear();
        }
        boolean all = destruction == Twister.Destruction.ALL;
        angle = Twister.avoid(x, z, h.angle(), size.reach() + 4, keep,
                (bx, bz) -> all ? level.hasChunk(bx >> 4, bz >> 4) : clearAround(bx + 0.5, bz + 0.5));
        if (angle != h.angle() && !swerved) {
            swerved = true;
            Unscripted.LOGGER.debug("Scene {}: swerving around a building at {} {}", id(), (int) x, (int) z);
        }
        return new double[]{x + Math.cos(angle) * h.speed(), z + Math.sin(angle) * h.speed()};
    }

    private boolean clearAround(double cx, double cz) {
        double r = size.reach() + 2;
        for (int i = 0; i <= 8; i++) {
            double a = i * Math.PI / 4;
            double k = i == 8 ? 0 : r;
            int bx = (int) Math.floor(cx + Math.cos(a) * k);
            int bz = (int) Math.floor(cz + Math.sin(a) * k);
            if (!clearCache.computeIfAbsent(BlockPos.asLong(bx, 0, bz), key -> clear(level, bx, bz))) {
                return false;
            }
        }
        return true;
    }

    private void ravage(int budget) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < budget; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * size.radius();
            int bx = (int) Math.floor(x + Math.cos(a) * r);
            int bz = (int) Math.floor(z + Math.sin(a) * r);
            if (!level.hasChunk(bx >> 4, bz >> 4)) {
                continue;
            }
            BlockPos top = new BlockPos(bx, level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1, bz);
            if (top.getY() < baseY - 3 || top.getY() > baseY + size.height() * 0.5) {
                continue;
            }
            BlockState state = level.getBlockState(top);
            switch (Twister.effect(destruction, size.kind(), classify(state, top))) {
                case REMOVE -> {
                    level.destroyBlock(top, false);
                    broken++;
                }
                case SCAR -> {
                    if (r < size.radius() * 0.6) {
                        level.setBlockAndUpdate(top, random.nextInt(3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState()
                                : Blocks.DIRT.defaultBlockState());
                        broken++;
                    }
                }
                case DROP -> {
                    level.destroyBlock(top, true);
                    broken++;
                }
                case KEEP -> {
                }
            }
        }
    }

    private Twister.BlockKind classify(BlockState s, BlockPos pos) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) {
            return Twister.BlockKind.PROTECTED;
        }
        float hardness = s.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 3) {
            return Twister.BlockKind.PROTECTED;
        }
        if (s.is(BlockTags.LEAVES)) {
            // las hojas que puso un jugador no se caen solas: son de una construcción
            return s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT)
                    ? Twister.BlockKind.BUILT : Twister.BlockKind.LEAVES;
        }
        if (built(s)) {
            return Twister.BlockKind.BUILT;
        }
        if (s.is(Blocks.GRASS_BLOCK)) {
            return Twister.BlockKind.GRASS;
        }
        if (s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS) || s.is(Blocks.SNOW)) {
            return Twister.BlockKind.PLANT;
        }
        return Twister.BlockKind.OTHER;
    }

    /** Un rayo solo visual: sin fuego, sin daño y sin convertir mobs. */
    private void lightning() {
        RandomSource random = level.getRandom();
        double a = random.nextDouble() * Math.PI * 2;
        double d = size.radius() + 4 + random.nextDouble() * 16;
        int bx = (int) Math.floor(x + Math.cos(a) * d);
        int bz = (int) Math.floor(z + Math.sin(a) * d);
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            return;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(Vec3.atBottomCenterOf(new BlockPos(bx,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), bz)));
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
    }

    private List<ServerPlayer> viewers() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            if (dx * dx + dz * dz < (double) VIEW * VIEW) {
                out.add(p);
            }
        }
        return out;
    }

    private boolean close(ServerPlayer p) {
        double dx = p.getX() - x;
        double dz = p.getZ() - z;
        return dx * dx + dz * dz < (double) CLOSE * CLOSE;
    }

    private boolean far(ServerPlayer p) {
        double dx = p.getX() - x;
        double dz = p.getZ() - z;
        return dx * dx + dz * dz > (double) DETAIL * DETAIL;
    }

    private void draw(double grow) {
        List<ServerPlayer> viewers = viewers();
        if (viewers.isEmpty()) {
            return;
        }
        ParticleOptions outer = dust(1f);
        ParticleOptions core = dust(0.7f);
        int n = tornado() ? 14 : 7;
        for (int i = 0; i < n; i++) {
            double[] f = Twister.funnel(size, ticks, i, n, grow);
            boolean inner = i % 2 == 0;
            double k = inner ? 0.5 : 1;
            for (ServerPlayer p : viewers) {
                // Bedrock dibuja el polvo de color diminuto: ve el embudo hecho de humo
                if (Bedrock.is(p)) {
                    if ((i + ticks) % 2 == 0 && !(close(p) && i % 3 != 0)) {
                        level.sendParticles(p, ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x + f[0] * k * 0.4,
                                baseY + f[1], z + f[2] * k * 0.4, 0, 0, 0.01, 0, 1);
                    }
                    continue;
                }
                // de lejos el detalle no se distingue, y de cerca cada partícula tapa media pantalla: menos
                if (far(p) && i % 2 == 1 || close(p) && i % 3 != 0) {
                    continue;
                }
                int count = far(p) || close(p) ? 2 : 3;
                level.sendParticles(p, inner ? core : outer, true, x + f[0] * k, baseY + f[1], z + f[2] * k, count,
                        f[3] * 0.25 * k, 0.6, f[3] * 0.25 * k, 0);
            }
        }
        RandomSource random = level.getRandom();
        int smoke = tornado() ? 4 : 8;
        if (ticks % (smoke / 2) == 0) {
            double[] f = Twister.funnel(size, ticks, random.nextInt(n), n, grow);
            for (ServerPlayer p : viewers) {
                if (close(p) || ticks % smoke != 0 && !Bedrock.is(p)) {
                    continue;
                }
                level.sendParticles(p, ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x + f[0], baseY + f[1], z + f[2], 0,
                        -f[2] * 0.01, 0.03 + random.nextDouble() * 0.04, f[0] * 0.01, 1);
            }
        }
        if (ticks % 2 == 1) {
            double[] f = Twister.funnel(size, ticks, n - 1, n, grow);
            ParticleOptions falling = new BlockParticleOption(ParticleTypes.FALLING_DUST, fallingBlock());
            for (ServerPlayer p : viewers) {
                if (close(p)) {
                    continue;
                }
                level.sendParticles(p, falling, true, x, baseY + f[1], z, 4, f[3] * 0.5, 1, f[3] * 0.5, 0);
            }
        }
        if (ticks % 2 == 0) {
            BlockState below = level.getBlockState(BlockPos.containing(x, baseY - 1, z));
            ParticleOptions kick = below.isAir() || !below.getFluidState().isEmpty() ? ParticleTypes.CLOUD
                    : new BlockParticleOption(ParticleTypes.BLOCK, below);
            for (ServerPlayer p : viewers) {
                level.sendParticles(p, kick, true, x, baseY + 0.3, z, 6, size.radius() * 0.4, 0.2,
                        size.radius() * 0.4, 0.05);
            }
        }
        if (tornado() && ticks % 10 == 0) {
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            for (ServerPlayer p : viewers) {
                level.sendParticles(p, ParticleTypes.GUST, true, x + Math.cos(a) * size.radius(), baseY + 1,
                        z + Math.sin(a) * size.radius(), 1, 0, 0, 0, 0);
            }
        }
    }

    private BlockState fallingBlock() {
        return switch (ground.dust()) {
            case SAND -> Blocks.SAND.defaultBlockState();
            case RED_SAND -> Blocks.RED_SAND.defaultBlockState();
            case SNOW -> Blocks.SNOW_BLOCK.defaultBlockState();
            case AIR -> Blocks.DIRT.defaultBlockState();
        };
    }

    private ParticleOptions dust(float shade) {
        float scale = tornado() ? 3.5f : 2.5f;
        Vector3f color = switch (ground.dust()) {
            case SAND -> new Vector3f(0.86f, 0.77f, 0.55f);
            case RED_SAND -> new Vector3f(0.74f, 0.42f, 0.22f);
            case SNOW -> new Vector3f(0.94f, 0.96f, 1f);
            case AIR -> tornado() ? new Vector3f(0.42f, 0.42f, 0.45f) : new Vector3f(0.62f, 0.57f, 0.48f);
        };
        return new DustParticleOptions(color.mul(shade), scale);
    }

    private void sounds() {
        RandomSource random = level.getRandom();
        if (phaseTicks % 30 == 1) {
            level.playSound(null, x, baseY + 3, z, SoundEvents.ELYTRA_FLYING, SoundSource.WEATHER,
                    tornado() ? 6f : 3f, 0.5f + 0.2f * random.nextFloat());
        }
        if (phaseTicks % 50 == 25) {
            level.playSound(null, x, baseY + 2, z, SoundEvents.BREEZE_WHIRL, SoundSource.WEATHER,
                    tornado() ? 5f : 3f, 0.5f + 0.2f * random.nextFloat());
        }
    }

    private void lift() {
        long now = level.getGameTime();
        double reach = Math.max(size.reach(), size.radius() * 2);
        AABB box = new AABB(x - reach, baseY - 2, z - reach, x + reach, baseY + size.height(), z + reach);
        Set<Entity> touched = new HashSet<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof LivingEntity || e instanceof ItemEntity)) {
            if (e instanceof ItemEntity item) {
                take(item);
                continue;
            }
            double maxLift;
            int maxHold;
            if (e instanceof ServerPlayer p) {
                if (!liftable(p)) {
                    continue;
                }
                maxLift = size.kind().playerLift;
                maxHold = size.kind().playerHold();
            } else {
                if (!liftable((LivingEntity) e)) {
                    continue;
                }
                maxLift = size.kind().mobLift;
                maxHold = size.kind().mobHold;
            }
            Long until = letGo.get(e);
            if (until != null && now < until) {
                continue;
            }
            int h = held.getOrDefault(e, 0);
            Twister.Push push = Twister.push(size, e.getX() - x, e.getZ() - z, e.getY() - baseY, h, maxLift, maxHold,
                    moveX, moveZ);
            if (push == null) {
                suck(e);
                continue;
            }
            if (e instanceof ServerPlayer p && h > 0 && h % HIT_EVERY == 0) {
                float damage = Twister.hitDamage(p.getHealth());
                if (damage > 0) {
                    p.hurt(level.damageSources().flyIntoWall(), damage);
                }
            }
            if (h == 0) {
                lifted++;
                Unscripted.LOGGER.debug("Scene {}: lifting {}", id(), e.getName().getString());
            }
            e.setDeltaMovement(push.x(), push.y(), push.z());
            e.hurtMarked = true;
            e.hasImpulse = true;
            FALLING.put(e, now + CUSHION);
            if (e instanceof Mob m) {
                m.getNavigation().stop();
            }
            if (push.release()) {
                held.remove(e);
                letGo.put(e, now + REGRAB);
            } else {
                held.put(e, h + 1);
                touched.add(e);
            }
        }
        held.keySet().retainAll(touched);
    }

    /**
     * Fuera de la columna, el tornado arrastra hacia adentro. Al jugador se le pisa la velocidad un tick sí
     * y uno no: sumada a la del servidor se aceleraría solo, y cada tick no lo dejaría correr.
     */
    private void suck(Entity e) {
        Twister.Push s = Twister.suck(size, e.getX() - x, e.getZ() - z, moveX, moveZ);
        if (s == null) {
            return;
        }
        if (e instanceof ServerPlayer) {
            if (ticks % 2 == 0) {
                // la velocidad vertical que guarda el servidor es la última que le dio el tornado (hacia
                // arriba al soltarlo): reenviarla lo hacía flotar y caer sin daño. Se usa la que trae.
                e.setDeltaMovement(s.x(), e.getY() - e.yo, s.z());
                e.hurtMarked = true;
            }
        } else {
            // sumada cada tick: a la mitad, para que un mob no llegue más rápido que un jugador
            e.setDeltaMovement(e.getDeltaMovement().add(s.x() * 0.5, 0, s.z() * 0.5));
            e.hasImpulse = true;
        }
    }

    private boolean liftable(ServerPlayer p) {
        return liftPlayers && p.isAlive() && !p.isCreative() && !p.isSpectator() && !p.getAbilities().flying
                && !p.isPassenger() && !p.isSleeping() && !p.isFallFlying();
    }

    private boolean liftable(LivingEntity e) {
        return e.isAlive() && !(e instanceof Player) && !e.hasCustomName() && !e.isPassenger() && !e.isVehicle()
                && !(e instanceof OwnableEntity o && o.getOwnerUUID() != null)
                && !(e instanceof Leashable l && l.isLeashed()) && !SceneTargets.claimed(e)
                && !(e instanceof AbstractVillager) && !(e instanceof AbstractGolem) && !(e instanceof Allay)
                && !(e instanceof ArmorStand) && Twister.lifts(size.kind(), e.getBbWidth(), e.getBbHeight());
    }

    private void take(ItemEntity item) {
        if (!item.isAlive() || carried.contains(item) || carried.size() >= size.kind().maxCarried()
                || item.getTags().contains(CARRIED)) {
            return;
        }
        item.addTag(CARRIED);
        item.setNoGravity(true);
        carried.add(item);
    }

    private void spawn(Twister.Cargo c) {
        ItemStack stack = c.table() ? loot(c.id()) : new ItemStack(
                BuiltInRegistries.ITEM.get(ResourceLocation.parse(c.id())));
        if (stack.isEmpty()) {
            stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(ground.junk().get(0))));
        }
        RandomSource random = level.getRandom();
        ItemEntity item = new ItemEntity(level, x + (random.nextDouble() - 0.5) * size.radius(), baseY + 0.5,
                z + (random.nextDouble() - 0.5) * size.radius(), stack);
        item.addTag(CARRIED);
        item.setNoGravity(true);
        item.setGlowingTag(c.table());
        level.addFreshEntity(item);
        carried.add(item);
        if (c.table()) {
            Unscripted.LOGGER.debug("Scene {}: carrying {} from {}", id(), stack, c.id());
        }
    }

    /** Un objeto de la tabla de botín, a lo sumo 4. Vacío si la tabla no existe (otro paquete de datos). */
    private ItemStack loot(String table) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(table));
        LootTable loot = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, center())
                .create(table.contains(":archaeology/") ? LootContextParamSets.ARCHAEOLOGY : LootContextParamSets.CHEST);
        List<ItemStack> items = loot.getRandomItems(params).stream().filter(s -> !s.isEmpty()).toList();
        if (items.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack pick = items.get(level.getRandom().nextInt(items.size())).copy();
        pick.setCount(Math.min(pick.getCount(), 4));
        return pick;
    }

    private void whirl(double grow) {
        int max = tornado() ? DEBRIS_TORNADO : DEBRIS_DUST_DEVIL;
        if (debris.size() < Math.round(grow * max)) {
            List<String> ids = ground.debris();
            String id = ids.get(level.getRandom().nextInt(ids.size()));
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            Display.BlockDisplay d = display(block.defaultBlockState(), debris.size(), max);
            if (d != null) {
                debris.add(d);
            }
        }
        if (ticks % DEBRIS_EVERY != 0) {
            return;
        }
        for (int i = 0; i < debris.size(); i++) {
            double[] o = Twister.debris(size, i, max, ticks);
            float yaw = (ticks * 9 + i * 47) % 360;
            float pitch = (float) Math.sin(ticks * 0.11 + i) * 80;
            debris.get(i).moveTo(x + o[0], baseY + o[1], z + o[2], yaw, pitch);
        }
    }

    /**
     * Una entidad de bloque. Sus valores solo se pueden poner como NBT; con la etiqueta de escena, si el mundo
     * se guarda con ella se quita al cargarse.
     */
    @Nullable
    private Display.BlockDisplay display(BlockState state, int index, int max) {
        float scale = tornado() ? 0.4f + 0.6f * (index % 4) / 3f : 0.3f + 0.2f * (index % 3) / 2f;
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.BLOCK_DISPLAY).toString());
        tag.put(Display.BlockDisplay.TAG_BLOCK_STATE, NbtUtils.writeBlockState(state));
        Transformation t = new Transformation(new Vector3f(-scale / 2), null, new Vector3f(scale), null);
        Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, t).result()
                .ifPresent(n -> tag.put(Display.TAG_TRANSFORMATION, n));
        tag.putInt(Display.TAG_POS_ROT_INTERPOLATION_DURATION, DEBRIS_EVERY + 1);
        tag.putFloat(Display.TAG_VIEW_RANGE, 2.5f);
        Entity e = EntityType.loadEntityRecursive(tag, level, en -> en);
        if (!(e instanceof Display.BlockDisplay d)) {
            return null;
        }
        double[] o = Twister.debris(size, index, max, ticks);
        d.moveTo(x + o[0], baseY + o[1], z + o[2], 0, 0);
        d.addTag(SceneTargets.TAG);
        return level.addFreshEntity(d) ? d : null;
    }

    private void carry() {
        carried.removeIf(item -> !item.isAlive());
        for (int i = 0; i < carried.size(); i++) {
            ItemEntity item = carried.get(i);
            double[] o = Twister.orbit(size, i, carried.size(), ticks);
            Vec3 v = new Vec3(x + o[0] - item.getX(), baseY + o[1] - item.getY(), z + o[2] - item.getZ()).scale(0.25);
            if (v.lengthSqr() > 1) {
                v = v.normalize();
            }
            item.setDeltaMovement(v);
            item.hurtMarked = true;
        }
    }

    private void flee() {
        Vec3 c = center();
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(c, c).inflate(FLEE, 16, FLEE),
                a -> a.isAlive() && !held.containsKey(a) && !SceneTargets.claimed(a) && !a.isLeashed()
                        && !(a instanceof OwnableEntity o && o.getOwnerUUID() != null) && !a.hasCustomName())) {
            Vec3 away = a.position().subtract(c).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            a.getNavigation().moveTo(a.getX() + away.x * 16, a.getY(), a.getZ() + away.z * 16, 1.6);
        }
    }

    private void finish(String why, boolean burst) {
        if (burst && phase == Phase.PASS) {
            for (ServerPlayer p : viewers()) {
                level.sendParticles(p, ParticleTypes.POOF, true, x, baseY + size.height() / 3.0, z, 40,
                        size.radius(), size.height() / 4.0, size.radius(), 0.1);
                level.sendParticles(p, dust(1f), true, x, baseY + size.height() / 3.0, z, 40, size.radius() * 1.5,
                        size.height() / 4.0, size.radius() * 1.5, 0);
            }
            level.playSound(null, x, baseY + 2, z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.WEATHER,
                    tornado() ? 6f : 3f, 0.6f);
        }
        Unscripted.LOGGER.debug("Scene {}: ended in {} because {} ({} items dropped, {} lifted, {} blocks broken)",
                id(), phase(), why, carried.size(), lifted, broken);
        drop();
    }

    private void drop() {
        RandomSource random = level.getRandom();
        for (ItemEntity item : carried) {
            item.removeTag(CARRIED);
            item.setNoGravity(false);
            item.setDeltaMovement((random.nextDouble() - 0.5) * 0.4, 0.1, (random.nextDouble() - 0.5) * 0.4);
            item.hurtMarked = true;
        }
        carried.clear();
        held.clear();
        debris.forEach(Entity::discard);
        debris.clear();
    }

    @Override
    public void stop() {
        drop();
    }

    /**
     * La caída de algo que soltó un torbellino: los mobs no se dañan y el jugador recibe un daño con tope.
     * Null si la caída no es de un torbellino.
     */
    @Nullable
    static Float cushion(LivingEntity e, float amount) {
        Long until = FALLING.remove(e);
        if (until == null || e.level().getGameTime() > until) {
            return null;
        }
        return e instanceof Player ? Twister.fallDamage(amount, e.getHealth()) : 0f;
    }

    static void landed(Entity e) {
        if (e.getTags().contains(CARRIED)) {
            e.removeTag(CARRIED);
            e.setNoGravity(false);
        }
    }
}
