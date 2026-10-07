package dev.unscripted.core;

public final class Limits {
    private Limits() {
    }

    /**
     * Por qué no puede empezar una escena nueva ahora, o null si puede.
     *
     * @param needed mobs que la escena pone en el mundo como mucho
     * @param mspt   milisegundos por tick promedio del servidor
     */
    public static String blocked(Config c, int activeScenes, int sceneMobs, int needed, double mspt) {
        if (!c.enabled()) {
            return "disabled in the config";
        }
        if (activeScenes >= c.maxActiveScenes()) {
            return "active scene limit (" + c.maxActiveScenes() + ")";
        }
        if ((long) Math.max(0, sceneMobs) + Math.max(0, needed) > c.maxSceneMobs()) {
            return "scene mob limit (" + c.maxSceneMobs() + ")";
        }
        if (c.msptLimit() > 0 && mspt > c.msptLimit()) {
            return String.format(java.util.Locale.ROOT, "server is slow (%.1f ms per tick)", mspt);
        }
        return null;
    }

    public static int room(Config c, int sceneMobs) {
        return (int) Math.max(0, c.maxSceneMobs() - (long) Math.max(0, sceneMobs));
    }
}
