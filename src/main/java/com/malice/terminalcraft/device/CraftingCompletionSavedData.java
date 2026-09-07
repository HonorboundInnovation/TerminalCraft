package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded server-private receipts from explicit native task completion/cancellation signals. */
public final class CraftingCompletionSavedData extends SavedData {
    private static final int MAX_RECEIPTS = 4096;
    private final LinkedHashMap<UUID, Boolean> receipts = new LinkedHashMap<>();

    public static CraftingCompletionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CraftingCompletionSavedData::load,
                CraftingCompletionSavedData::new, "terminalcraft_crafting_completions");
    }

    /** True is completed, false is cancelled, null is unobserved. Never infer success from absence. */
    public Boolean outcome(UUID nativeId) { return receipts.get(nativeId); }

    public void record(UUID nativeId, boolean completed) {
        if (receipts.putIfAbsent(nativeId, completed) != null) return;
        while (receipts.size() > MAX_RECEIPTS) receipts.remove(receipts.keySet().iterator().next());
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag root) {
        ListTag values = new ListTag();
        receipts.forEach((id, completed) -> {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", id);
            value.putBoolean("Completed", completed);
            values.add(value);
        });
        root.put("Receipts", values);
        return root;
    }

    public static CraftingCompletionSavedData load(CompoundTag root) {
        CraftingCompletionSavedData data = new CraftingCompletionSavedData();
        ListTag values = root.getList("Receipts", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, values.size() - MAX_RECEIPTS); i < values.size(); i++) {
            CompoundTag value = values.getCompound(i);
            if (value.hasUUID("Id") && value.contains("Completed", Tag.TAG_BYTE))
                data.receipts.putIfAbsent(value.getUUID("Id"), value.getBoolean("Completed"));
        }
        return data;
    }
}
