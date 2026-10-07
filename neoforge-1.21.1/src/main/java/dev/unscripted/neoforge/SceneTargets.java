package dev.unscripted.neoforge;

import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Mobs que maneja una escena: solo pueden tener el objetivo que la escena les da. Vanilla elige presas y
 * se enoja con el jugador dentro del tick del mob; vetar el cambio de objetivo es la única forma de que
 * no muerda.
 */
final class SceneTargets {
    static final String TAG = "unscripted.scene";

    private static final Map<Mob, LivingEntity> ALLOWED = new WeakHashMap<>();
    private static final Map<Entity, Object> SIDES = new WeakHashMap<>();

    private SceneTargets() {
    }

    static void claim(Mob mob) {
        mob.addTag(TAG);
    }

    static boolean claimed(Entity entity) {
        return entity.getTags().contains(TAG);
    }

    static void aim(Mob mob, @Nullable LivingEntity target) {
        if (target == null) {
            ALLOWED.remove(mob);
        } else {
            ALLOWED.put(mob, target);
        }
        mob.setTarget(target);
    }

    static boolean vetoes(LivingEntity mob, @Nullable LivingEntity target) {
        return target != null && (claimed(mob) && ALLOWED.get(mob) != target || allies(mob, target));
    }

    /**
     * Un bando no se apunta ni se daña: el escupitajo de una llama que pega al comerciante hace que la otra
     * lo defienda de ella, y las llamas terminan matándose o matándolo.
     */
    static void side(Collection<? extends Entity> members) {
        Object side = new Object();
        members.forEach(e -> SIDES.put(e, side));
    }

    static boolean allies(@Nullable Entity a, @Nullable Entity b) {
        Object side = a == null ? null : SIDES.get(a);
        return side != null && b != null && SIDES.get(b) == side;
    }

    static void release(Mob mob) {
        ALLOWED.remove(mob);
        mob.removeTag(TAG);
    }

    static void remove(Mob mob) {
        ALLOWED.remove(mob);
        mob.discard();
    }
}
