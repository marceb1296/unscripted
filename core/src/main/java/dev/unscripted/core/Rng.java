package dev.unscripted.core;

/** Fuente de azar: en el juego envuelve el RandomSource del mundo; en las pruebas, una semilla fija. */
@FunctionalInterface
public interface Rng {
    /** Valor en [0, 1). */
    double nextDouble();
}
