package dev.unscripted.neoforge;

import dev.unscripted.core.Director;
import dev.unscripted.core.Memory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

final class UnscriptedData extends SavedData {
    static final String NAME = "unscripted";
    static final SavedData.Factory<UnscriptedData> FACTORY = new SavedData.Factory<>(UnscriptedData::new,
            UnscriptedData::load);

    List<Memory.Event> events = new ArrayList<>();
    Map<String, Director.PlayerSnapshot> players = new HashMap<>();
    /** Mientras el servidor corre, la fuente de verdad es el director. */
    Director director;

    static UnscriptedData load(CompoundTag tag, HolderLookup.Provider registries) {
        UnscriptedData data = new UnscriptedData();
        for (Tag t : tag.getList("events", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            data.events.add(new Memory.Event(e.getString("type"), e.getInt("x"), e.getInt("z"), e.getLong("time")));
        }
        CompoundTag players = tag.getCompound("players");
        for (String id : players.getAllKeys()) {
            CompoundTag p = players.getCompound(id);
            CompoundTag scenes = p.getCompound("scenes");
            Map<String, Long> last = new HashMap<>();
            for (String scene : scenes.getAllKeys()) {
                last.put(scene, scenes.getLong(scene));
            }
            data.players.put(id, new Director.PlayerSnapshot(p.getLong("next"), p.getInt("intensity"), last));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (director != null) {
            events = director.memory().events();
            players = director.snapshot();
        }
        ListTag list = new ListTag();
        for (Memory.Event e : events) {
            CompoundTag t = new CompoundTag();
            t.putString("type", e.type());
            t.putInt("x", e.x());
            t.putInt("z", e.z());
            t.putLong("time", e.time());
            list.add(t);
        }
        tag.put("events", list);
        CompoundTag all = new CompoundTag();
        players.forEach((id, snap) -> {
            CompoundTag p = new CompoundTag();
            p.putLong("next", snap.nextAt());
            p.putInt("intensity", snap.lastIntensity());
            CompoundTag scenes = new CompoundTag();
            snap.lastScene().forEach(scenes::putLong);
            p.put("scenes", scenes);
            all.put(id, p);
        });
        tag.put("players", all);
        return tag;
    }
}
