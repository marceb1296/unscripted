package dev.unscripted.neoforge;

import dev.unscripted.core.Placement;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class Spots {
    static final double VIEW_HALF_ANGLE = Math.toRadians(70);
    static final double ARRIVAL_ARC = Math.toRadians(60);
    static final int ARRIVAL_TRIES = 6;
    static final int ARRIVAL_STEP = 12;
    /** Un mob se quita solo si ningún jugador lo ve, o si todos están más lejos que esto. */
    static final int FAR = 96;
    static final int CLOSE = 24;
    static final int STAGE_CANDIDATES = 24;
    static final int MIN_OPENNESS = 3;

    private Spots() {
    }

    /**
     * Reparte el trabajo caro (calcular caminos, trazar líneas de vista) entre los ticks: cada mob tiene su
     * turno según su id, en vez de todos en el mismo tick.
     */
    static boolean turn(int ticks, Entity e, int period) {
        return Math.floorMod(ticks + e.getId(), period) == 0;
    }

    @Nullable
    static BlockPos ground(ServerLevel level, int x, int z, int nearY, int maxStep) {
        if (!level.hasChunkAt(new BlockPos(x, nearY, z))) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - nearY) > maxStep) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockPos below = pos.below();
        boolean solid = level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        boolean dry = level.getFluidState(pos).isEmpty() && level.getFluidState(below).isEmpty();
        boolean free = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
        return solid && dry && free ? pos : null;
    }

    static boolean visible(ServerLevel level, ServerPlayer player, BlockPos ground) {
        Vec3 target = Vec3.atBottomCenterOf(ground).add(0, 0.8, 0);
        return level.clip(new ClipContext(player.getEyePosition(), target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    /**
     * Qué tan a la vista está una escena centrada en {@code center}: el centro debe verse y estar a
     * cielo abierto; suma uno por cada punto visible del anillo de {@code ring} bloques. -1 si no sirve.
     */
    static int openness(ServerLevel level, ServerPlayer player, BlockPos center, int ring) {
        if (!level.canSeeSky(center) || !visible(level, player, center)) {
            return -1;
        }
        int score = 0;
        int[][] around = {{ring, 0}, {-ring, 0}, {0, ring}, {0, -ring}};
        for (int[] o : around) {
            BlockPos p = ground(level, center.getX() + o[0], center.getZ() + o[1], center.getY(), 4);
            if (p != null && visible(level, player, p)) {
                score++;
            }
        }
        return score;
    }

    static boolean fits(ServerLevel level, EntityType<?> type, BlockPos pos) {
        return level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
    }

    /**
     * El lugar más a la vista para una escena a {@code min} a {@code max} bloques del jugador. Con
     * {@code ahead}, mejor delante de él; si no, mejor fuera de su mirada (el aviso sonoro lo hace girar).
     * Null si ninguno sirve.
     */
    @Nullable
    static BlockPos stage(ServerLevel level, ServerPlayer player, int min, int max, int ring, boolean ahead,
                          String scene) {
        BlockPos at = player.blockPosition();
        Vec3 look = player.getLookAngle();
        RandomSource random = level.getRandom();
        BlockPos center = null;
        int best = MIN_OPENNESS - 1;
        int[] scores = new int[7];
        for (Placement.Offset o : Placement.candidates(random::nextDouble, min, max, STAGE_CANDIDATES)) {
            BlockPos c = ground(level, at.getX() + o.dx(), at.getZ() + o.dz(), at.getY(), 16);
            int score = c == null ? -2 : openness(level, player, c, ring);
            scores[score + 2]++;
            int rank = score >= MIN_OPENNESS && inView(at, look, c) == ahead ? score + 10 : score;
            if (rank > best) {
                best = rank;
                center = c;
            }
        }
        if (center == null) {
            Unscripted.LOGGER.debug("Scene {}: no place found (no ground {}, covered {}, ring points seen 0-4: {})",
                    scene, scores[0], scores[1], Arrays.toString(Arrays.copyOfRange(scores, 2, 7)));
        }
        return center;
    }

    static boolean inView(BlockPos from, Vec3 look, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz) * Math.sqrt(look.x * look.x + look.z * look.z);
        return len < 1e-6 || (dx * look.x + dz * look.z) / len > Math.cos(VIEW_HALF_ANGLE);
    }

    /**
     * Dónde aparece un mob que llega a {@code center}: a {@code minRing} a {@code maxRing} bloques, del lado
     * opuesto al jugador, mejor donde ningún jugador lo vea aparecer. Null si no hay suelo donde entre.
     */
    @Nullable
    static BlockPos arrival(ServerLevel level, ServerPlayer player, BlockPos center, int minRing, int maxRing,
                            EntityType<?> type, RandomSource random) {
        BlockPos at = player.blockPosition();
        double away = Math.atan2(center.getZ() - at.getZ(), center.getX() - at.getX());
        BlockPos pos = null;
        for (int t = 0; t < ARRIVAL_TRIES; t++) {
            double angle = away - ARRIVAL_ARC + 2 * ARRIVAL_ARC * random.nextDouble();
            double ring = minRing + random.nextDouble() * (maxRing - minRing);
            BlockPos p = ground(level, center.getX() + (int) Math.round(Math.cos(angle) * ring),
                    center.getZ() + (int) Math.round(Math.sin(angle) * ring), center.getY(), ARRIVAL_STEP);
            if (p == null || !fits(level, type, p)) {
                continue;
            }
            pos = p;
            if (hidden(level, p)) {
                break;
            }
        }
        return pos;
    }

    static boolean hidden(ServerLevel level, BlockPos p) {
        for (ServerPlayer pl : level.players()) {
            if (!pl.isSpectator() && pl.blockPosition().distSqr(p) < (double) FAR * FAR
                    && inView(pl.blockPosition(), pl.getLookAngle(), p) && visible(level, pl, p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Para quién sigue la escena: el dueño si está a {@code range} bloques; si no, el jugador más cercano que
     * lo esté (un amigo que se quedó peleando). Null si no queda nadie. Un jugador muerto cuenta hasta que
     * reaparece.
     */
    @Nullable
    static Player focus(ServerLevel level, UUID owner, Vec3 center, int range) {
        double r2 = (double) range * range;
        Player o = level.getPlayerByUUID(owner);
        if (o != null && !o.isSpectator() && o.distanceToSqr(center) <= r2) {
            return o;
        }
        Player best = null;
        double bestD = r2;
        for (Player p : level.players()) {
            double d = p.distanceToSqr(center);
            if (!p.isSpectator() && d <= bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    static boolean unseen(ServerLevel level, Entity e) {
        for (Player p : level.players()) {
            double d = p.distanceToSqr(e);
            if (d < (double) CLOSE * CLOSE || d < (double) FAR * FAR && p.hasLineOfSight(e)) {
                return false;
            }
        }
        return true;
    }
}
