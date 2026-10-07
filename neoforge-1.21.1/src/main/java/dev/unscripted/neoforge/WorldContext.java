package dev.unscripted.neoforge;

import dev.unscripted.core.Activity;
import dev.unscripted.core.Context;
import dev.unscripted.core.Difficulty;
import dev.unscripted.core.Terrain;
import dev.unscripted.core.Weather;
import dev.unscripted.core.scene.WanderingHorde;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;

final class WorldContext {
    static final int MOB_RADIUS = 48;
    static final int VILLAGE_RADIUS = 160;
    static final int UNDERGROUND_DEPTH = 6;
    static final int FIGHT_TICKS = 200;

    private static final Map<TagKey<Biome>, Terrain> TERRAIN = Map.ofEntries(
            Map.entry(BiomeTags.IS_FOREST, Terrain.FOREST),
            Map.entry(BiomeTags.IS_TAIGA, Terrain.TAIGA),
            Map.entry(Tags.Biomes.IS_PLAINS, Terrain.PLAINS),
            Map.entry(BiomeTags.IS_SAVANNA, Terrain.SAVANNA),
            Map.entry(BiomeTags.IS_MOUNTAIN, Terrain.MOUNTAIN),
            Map.entry(Tags.Biomes.IS_SNOWY, Terrain.SNOWY),
            Map.entry(Tags.Biomes.IS_DESERT, Terrain.DESERT),
            Map.entry(BiomeTags.IS_BADLANDS, Terrain.BADLANDS),
            Map.entry(Tags.Biomes.IS_SWAMP, Terrain.SWAMP),
            Map.entry(BiomeTags.IS_JUNGLE, Terrain.JUNGLE),
            Map.entry(BiomeTags.IS_BEACH, Terrain.BEACH),
            Map.entry(BiomeTags.IS_OCEAN, Terrain.OCEAN),
            Map.entry(BiomeTags.IS_RIVER, Terrain.RIVER));

    private WorldContext() {
    }

    /**
     * Con {@code village} en false, la campana (la búsqueda más cara) solo se busca si la horda, la única
     * escena que la usa, puede ocurrir.
     */
    static Context read(ServerPlayer player, boolean moved, boolean village) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        Weather weather = level.isThundering() ? Weather.THUNDER : level.isRaining() ? Weather.RAIN : Weather.CLEAR;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        Difficulty difficulty = difficulty(level);
        boolean overworld = level.dimension() == Level.OVERWORLD;
        boolean bell = village || overworld && WanderingHorde.canHappen(level.getDayTime(), difficulty);
        return new Context(level.getGameTime(), (int) Math.floorMod(level.getDayTime(), 24000L), level.getMoonPhase(),
                weather, difficulty, overworld, pos.getY() < surface - UNDERGROUND_DEPTH,
                terrain(level.getBiome(pos)), mobs(level, player), activity(player, moved),
                player.getHealth() / Math.max(1f, player.getMaxHealth()), player.getArmorValue(), pos.getX(),
                pos.getZ(), bell ? villageDistance(level, pos) : Context.NO_VILLAGE);
    }

    private static Difficulty difficulty(ServerLevel level) {
        return switch (level.getDifficulty()) {
            case PEACEFUL -> Difficulty.PEACEFUL;
            case EASY -> Difficulty.EASY;
            case NORMAL -> Difficulty.NORMAL;
            case HARD -> Difficulty.HARD;
        };
    }

    static Set<Terrain> terrain(Holder<Biome> biome) {
        Set<Terrain> out = EnumSet.noneOf(Terrain.class);
        TERRAIN.forEach((tag, t) -> {
            if (biome.is(tag)) {
                out.add(t);
            }
        });
        return out;
    }

    private static Map<String, Integer> mobs(ServerLevel level, ServerPlayer player) {
        Map<String, Integer> out = new HashMap<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(MOB_RADIUS))) {
            out.merge(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), 1, Integer::sum);
        }
        return out;
    }

    private static Activity activity(ServerPlayer player, boolean moved) {
        int now = player.tickCount;
        boolean hurt = player.getLastHurtByMob() != null && now - player.getLastHurtByMobTimestamp() < FIGHT_TICKS;
        boolean hit = player.getLastHurtMob() != null && now - player.getLastHurtMobTimestamp() < FIGHT_TICKS;
        if (hurt || hit) {
            return Activity.FIGHTING;
        }
        return moved ? Activity.EXPLORING : Activity.IDLE;
    }

    static Optional<BlockPos> bell(ServerLevel level, BlockPos pos) {
        return level.getPoiManager().findClosest(type -> type.is(PoiTypes.MEETING), pos, VILLAGE_RADIUS,
                PoiManager.Occupancy.ANY);
    }

    private static int villageDistance(ServerLevel level, BlockPos pos) {
        return bell(level, pos).map(b -> (int) Math.sqrt(Math.pow(b.getX() - pos.getX(), 2) + Math.pow(b.getZ() - pos.getZ(), 2)))
                .orElse(Context.NO_VILLAGE);
    }
}
