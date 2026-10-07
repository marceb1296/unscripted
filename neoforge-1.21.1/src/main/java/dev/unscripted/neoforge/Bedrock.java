package dev.unscripted.neoforge;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Jugadores que entran desde Bedrock por Geyser. Bedrock no tiene algunos sonidos y partículas de Java (el
 * aullido, el polvo de color grande): a ellos se les manda algo que sí dibuja.
 */
public final class Bedrock {
    private static final Set<UUID> PLAYERS = ConcurrentHashMap.newKeySet();

    private Bedrock() {
    }

    // llega desde el hilo de red, antes de que exista el jugador
    public static void brand(UUID id, String brand) {
        Unscripted.LOGGER.debug("Client brand of {}: {}", id, brand);
        if (brand != null && brand.toLowerCase(Locale.ROOT).contains("geyser")) {
            PLAYERS.add(id);
        } else {
            PLAYERS.remove(id);
        }
    }

    public static void forget(UUID id) {
        PLAYERS.remove(id);
    }

    public static boolean is(ServerPlayer p) {
        return PLAYERS.contains(p.getUUID());
    }

    public static void howl(ServerLevel level, double x, double y, double z, float volume, float pitch) {
        double range = SoundEvents.WOLF_HOWL.getRange(volume);
        long seed = level.getRandom().nextLong();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(x, y, z) > range * range) {
                continue;
            }
            SoundEvent sound = is(p) ? SoundEvents.WOLF_AMBIENT : SoundEvents.WOLF_HOWL;
            p.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                    SoundSource.NEUTRAL, x, y, z, volume, pitch, seed));
        }
    }
}
