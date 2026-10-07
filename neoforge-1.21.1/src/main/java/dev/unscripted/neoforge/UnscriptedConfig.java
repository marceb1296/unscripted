package dev.unscripted.neoforge;

import dev.unscripted.core.Config;
import dev.unscripted.core.Scene;
import dev.unscripted.core.Settings;
import dev.unscripted.core.scene.Scenes;
import dev.unscripted.core.scene.Twister;
import dev.unscripted.core.scene.WanderingHorde;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/** config/unscripted-server.toml (o world/serverconfig/ para un solo mundo): NeoForge lo vuelve a leer al guardarlo. */
final class UnscriptedConfig {
    static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final Map<String, ModConfigSpec.BooleanValue> SCENE_ON = new LinkedHashMap<>();
    private static final Map<String, ModConfigSpec.DoubleValue> SCENE_WEIGHT = new LinkedHashMap<>();
    private static final ModConfigSpec.DoubleValue MIN_MINUTES;
    private static final ModConfigSpec.DoubleValue MAX_MINUTES;
    private static final ModConfigSpec.IntValue DISTANCE_MIN;
    private static final ModConfigSpec.IntValue DISTANCE_MAX;
    private static final ModConfigSpec.IntValue WAVES;
    private static final ModConfigSpec.IntValue FIRST_WAVE;
    private static final ModConfigSpec.IntValue WAVE_GROWTH;
    private static final ModConfigSpec.IntValue PER_PLAYER;
    private static final ModConfigSpec.IntValue MAX_EXTRA_PLAYERS;
    private static final ModConfigSpec.IntValue FULL_MOON;
    private static final ModConfigSpec.BooleanValue TRADER_GIFT;
    private static final ModConfigSpec.BooleanValue TRADER_DISCOUNT;
    private static final ModConfigSpec.BooleanValue HERO;
    private static final ModConfigSpec.BooleanValue LIFT_PLAYERS;
    private static final ModConfigSpec.BooleanValue START_RAIN;
    private static final ModConfigSpec.EnumValue<Twister.Destruction> DESTRUCTION;
    private static final ModConfigSpec.IntValue MAX_ACTIVE;
    private static final ModConfigSpec.IntValue MAX_MOBS;
    private static final ModConfigSpec.DoubleValue MAX_TICK_MS;

    static {
        Config d = Config.DEFAULT;
        WanderingHorde.Size h = d.horde();
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        ENABLED = b.comment("Start new scenes. Scenes already running finish normally.")
                .define("enabled", d.enabled());

        b.comment("Each scene can be turned off, or made more or less common than the others.").push("scenes");
        for (Scene scene : Scenes.all()) {
            b.push(scene.id());
            SCENE_ON.put(scene.id(), b.define("enabled", true));
            SCENE_WEIGHT.put(scene.id(), b.comment("How often it is picked compared to the others (0 = never, 1 = normal).")
                    .defineInRange("weight", 1.0, 0.0, Settings.MAX_WEIGHT));
            b.pop();
        }
        b.pop();

        b.comment("Minutes between two scenes for the same player (random in this range).").push("pacing");
        MIN_MINUTES = b.defineInRange("minMinutes", d.minMinutes(), Config.MIN_MINUTES, Config.MAX_MINUTES);
        MAX_MINUTES = b.defineInRange("maxMinutes", d.maxMinutes(), Config.MIN_MINUTES, Config.MAX_MINUTES);
        b.pop();

        b.comment("Blocks from the player where the wolf hunt and the trader scene take place.").push("distance");
        DISTANCE_MIN = b.defineInRange("min", d.distanceMin(), Config.MIN_DISTANCE, Config.MAX_DISTANCE);
        DISTANCE_MAX = b.defineInRange("max", d.distanceMax(), Config.MIN_DISTANCE, Config.MAX_DISTANCE);
        b.pop();

        b.comment("Wandering horde: zombies per wave = first + growth * wave + perExtraPlayer * extra players near the bell + fullMoonExtra.")
                .push("horde");
        WAVES = b.defineInRange("waves", h.waves(), 1, WanderingHorde.Size.MAX_WAVES);
        FIRST_WAVE = b.defineInRange("firstWave", h.first(), 1, WanderingHorde.Size.MAX_WAVE);
        WAVE_GROWTH = b.defineInRange("growth", h.growth(), 0, WanderingHorde.Size.MAX_WAVE);
        PER_PLAYER = b.defineInRange("perExtraPlayer", h.perPlayer(), 0, WanderingHorde.Size.MAX_WAVE);
        MAX_EXTRA_PLAYERS = b.comment("Extra players counted, at most.")
                .defineInRange("maxExtraPlayers", h.maxExtraPlayers(), 0, WanderingHorde.Size.MAX_WAVE);
        FULL_MOON = b.defineInRange("fullMoonExtra", h.fullMoonExtra(), 0, WanderingHorde.Size.MAX_WAVE);
        b.pop();

        b.push("rewards");
        TRADER_GIFT = b.comment("The rescued trader gives a gift to whoever helped the most.")
                .define("traderGift", d.traderGift());
        TRADER_DISCOUNT = b.comment("The rescued trader sells at half price.")
                .define("traderDiscount", d.traderDiscount());
        HERO = b.comment("Hero of the Village for the players who defended a village from the horde.")
                .define("heroOfTheVillage", d.heroEffect());
        b.pop();

        b.comment("Dust devils and tornadoes.").push("weather");
        LIFT_PLAYERS = b.comment("They lift players for a moment. The fall hurts but never kills, and debris never takes a player below 4 hearts.")
                .define("liftPlayers", d.liftPlayers());
        START_RAIN = b.comment("A tornado may start a short rain when it is not raining. Rain is for the whole server, so this happens at most once every 2 days.")
                .define("startRain", d.startRain());
        DESTRUCTION = b.comment("What tornadoes break on their way. NONE: nothing. NATURAL: leaves, grass, flowers and snow, never builds (their path avoids them). ALL: also planks, glass, wool, doors, fences, roofs, torches and crops, dropping them as items; needs the mobGriefing game rule. Never chests or other blocks with contents. Dust devils only pull up plants.")
                .defineEnum("destruction", d.destruction());
        b.pop();

        b.comment("Limits that keep the server fast.").push("limits");
        MAX_ACTIVE = b.comment("Scenes running at the same time on the whole server.")
                .defineInRange("maxActiveScenes", d.maxActiveScenes(), 0, Config.MAX_ACTIVE);
        MAX_MOBS = b.comment("Mobs from all scenes together. Horde waves wait until there is room.")
                .defineInRange("maxSceneMobs", d.maxSceneMobs(), 0, Config.MAX_MOBS);
        MAX_TICK_MS = b.comment("No new scenes while the average server tick takes longer than this (milliseconds; 0 = no limit).")
                .defineInRange("maxTickMs", d.msptLimit(), 0, Config.MAX_MSPT);
        b.pop();

        SPEC = b.build();
    }

    private UnscriptedConfig() {
    }

    static Config read() {
        if (!SPEC.isLoaded()) {
            return Config.DEFAULT;
        }
        Map<String, Boolean> on = new LinkedHashMap<>();
        SCENE_ON.forEach((id, v) -> on.put(id, v.get()));
        Map<String, Double> weights = new LinkedHashMap<>();
        SCENE_WEIGHT.forEach((id, v) -> weights.put(id, v.get()));
        WanderingHorde.Size horde = new WanderingHorde.Size(WAVES.get(), FIRST_WAVE.get(), WAVE_GROWTH.get(),
                PER_PLAYER.get(), MAX_EXTRA_PLAYERS.get(), FULL_MOON.get());
        return new Config(ENABLED.get(), on, weights, MIN_MINUTES.get(), MAX_MINUTES.get(), DISTANCE_MIN.get(),
                DISTANCE_MAX.get(), horde, TRADER_GIFT.get(), TRADER_DISCOUNT.get(), HERO.get(), MAX_ACTIVE.get(),
                MAX_MOBS.get(), MAX_TICK_MS.get(), LIFT_PLAYERS.get(), START_RAIN.get(),
                DESTRUCTION.get());
    }
}
