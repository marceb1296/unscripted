package dev.unscripted.core;

record FixedScene(String id, int intensity, long cooldown, double w) implements Scene {
    FixedScene(String id, double w) {
        this(id, 1, 1000, w);
    }

    @Override
    public double weight(Context context, Memory memory) {
        return w;
    }
}
