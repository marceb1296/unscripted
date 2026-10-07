package dev.unscripted.neoforge;

import com.mojang.logging.LogUtils;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(Unscripted.MODID)
public final class Unscripted {
    public static final String MODID = "unscripted";
    public static final Logger LOGGER = LogUtils.getLogger();
    static final Set<EntityType<?>> BIG_ANIMALS = Set.of(EntityType.COW, EntityType.MOOSHROOM, EntityType.SHEEP,
            EntityType.PIG, EntityType.GOAT, EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.LLAMA,
            EntityType.CAMEL, EntityType.POLAR_BEAR, EntityType.PANDA, EntityType.SNIFFER);

    public Unscripted(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, UnscriptedConfig.SPEC);
        // NeoForge avisa desde el hilo que vigila el archivo: el cambio se aplica en el hilo del servidor
        modBus.addListener(ModConfigEvent.Reloading.class, event -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (event.getConfig().getSpec() == UnscriptedConfig.SPEC && server != null) {
                server.execute(() -> {
                    SceneManager m = SceneManager.get();
                    if (m != null) {
                        m.apply(UnscriptedConfig.read());
                    }
                });
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            SceneManager.start(event.getServer());
            LOGGER.info("Unscripted ready on the server");
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> SceneManager.stop());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            SceneManager m = SceneManager.get();
            if (m != null) {
                m.tick(event.getServer());
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            Bedrock.forget(event.getEntity().getUUID());
            SceneManager m = SceneManager.get();
            if (m != null) {
                m.forget(event.getEntity().getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingChangeTargetEvent.class, event -> {
            if (SceneTargets.vetoes(event.getEntity(), event.getNewAboutToBeSetTarget())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, event -> {
            if (SceneTargets.allies(event.getSource().getEntity(), event.getEntity())) {
                event.setCanceled(true);
                return;
            }
            if (event.getSource().is(DamageTypeTags.IS_FALL)) {
                Float cushioned = TwisterScene.cushion(event.getEntity(), event.getAmount());
                if (cushioned != null && cushioned <= 0) {
                    event.setCanceled(true);
                } else if (cushioned != null) {
                    event.setAmount(cushioned);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class, event -> {
            SceneManager m = SceneManager.get();
            LivingEntity dead = event.getEntity();
            if (m != null) {
                m.died(dead, event.getSource());
            }
            if (m != null && dead.level() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD
                    && BIG_ANIMALS.contains(dead.getType())) {
                m.bigDeath(dead.getBlockX(), dead.getBlockZ(), level.getGameTime());
            }
        });
        // un zombie de escena bajo el agua se volvería un ahogado común, fuera de la escena; la cura del
        // aldeano zombie sí se permite
        NeoForge.EVENT_BUS.addListener(LivingConversionEvent.Pre.class, event -> {
            if (SceneTargets.claimed(event.getEntity()) && event.getOutcome() != EntityType.VILLAGER) {
                event.setCanceled(true);
                event.setConversionTimer(300);
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingConversionEvent.Post.class, event -> {
            SceneManager m = SceneManager.get();
            if (m != null) {
                m.converted(event.getEntity(), event.getOutcome());
            }
        });
        // un mob de escena guardado con el mundo (cierre abrupto, zona descargada) ya no tiene quien lo maneje
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (event.loadedFromDisk()) {
                TwisterScene.landed(event.getEntity());
            }
            if (event.loadedFromDisk() && SceneTargets.claimed(event.getEntity())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> UnscriptedCommand.register(event.getDispatcher()));
    }
}
