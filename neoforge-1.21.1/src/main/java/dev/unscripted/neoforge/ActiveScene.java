package dev.unscripted.neoforge;

import java.util.UUID;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

interface ActiveScene {
    String id();

    UUID player();

    /** Dónde ocurre: el director no arranca otra escena cerca. */
    Vec3 center();

    default String phase() {
        return "";
    }

    /** Mobs de la escena que siguen en el mundo (para el tope de todas las escenas). */
    int mobs();

    String describe();

    /** Avanza un tick; devuelve true cuando terminó. */
    boolean tick();

    default void died(LivingEntity dead, DamageSource source) {
    }

    default void converted(LivingEntity from, LivingEntity to) {
    }

    void stop();
}
