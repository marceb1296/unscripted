package dev.unscripted.core;

public interface Scene {
    String id();

    /** 1 tranquila, 2 tensa, 3 peligrosa. */
    int intensity();

    /** Ticks antes de que la misma escena vuelva a ocurrir para el mismo jugador o cerca del mismo lugar. */
    long cooldown();

    /** Peso en el sorteo; 0 si no puede ocurrir. */
    double weight(Context context, Memory memory);
}
