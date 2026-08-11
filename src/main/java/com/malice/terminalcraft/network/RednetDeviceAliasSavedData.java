package com.malice.terminalcraft.network;

import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable, dimension-scoped DNS aliases for non-modem TerminalCraft devices. */
final class RednetDeviceAliasSavedData extends SavedData {
    static final String FILE_ID = "terminalcraft_device_dns";
    static final int MAX_ALIASES = 4096;
    private static final int MAX_DIMENSION_CHARS = 128;

    private final Map<String, Map<UUID, Set<String>>> aliases = new LinkedHashMap<>();

    RednetDeviceAliasSavedData() {}

    static RednetDeviceAliasSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                RednetDeviceAliasSavedData::load, RednetDeviceAliasSavedData::new, FILE_ID);
    }

    synchronized boolean register(String dimension, UUID deviceId, String requestedName) {
        String name = RednetHostName.normalize(requestedName).orElse("");
        if (!validDimension(dimension) || deviceId == null || name.isEmpty()) return false;
        Map<UUID, Set<String>> byDevice = aliases.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
        for (Map.Entry<UUID, Set<String>> entry : byDevice.entrySet()) {
            if (!entry.getKey().equals(deviceId) && entry.getValue().contains(name)) return false;
        }
        Set<String> names = byDevice.computeIfAbsent(deviceId, ignored -> new LinkedHashSet<>());
        if (names.contains(name)) return true;
        if (count() >= MAX_ALIASES) return false;
        names.add(name);
        setDirty();
        return true;
    }

    synchronized boolean contains(String dimension, UUID deviceId, String requestedName) {
        String name = RednetHostName.normalize(requestedName).orElse("");
        return validDimension(dimension) && deviceId != null && !name.isEmpty()
                && aliases.getOrDefault(dimension, Map.of()).getOrDefault(deviceId, Set.of()).contains(name);
    }

    synchronized boolean unregister(String dimension, UUID deviceId, String requestedName) {
        String name = RednetHostName.normalize(requestedName).orElse("");
        if (!validDimension(dimension) || deviceId == null || name.isEmpty()) return false;
        Map<UUID, Set<String>> byDevice = aliases.get(dimension);
        Set<String> names = byDevice == null ? null : byDevice.get(deviceId);
        if (names == null || !names.remove(name)) return false;
        if (names.isEmpty()) byDevice.remove(deviceId);
        if (byDevice.isEmpty()) aliases.remove(dimension);
        setDirty();
        return true;
    }

    synchronized int unregisterAll(String dimension, UUID deviceId) {
        if (!validDimension(dimension) || deviceId == null) return 0;
        Map<UUID, Set<String>> byDevice = aliases.get(dimension);
        Set<String> names = byDevice == null ? null : byDevice.remove(deviceId);
        if (names == null || names.isEmpty()) return 0;
        if (byDevice.isEmpty()) aliases.remove(dimension);
        setDirty();
        return names.size();
    }

    synchronized List<Alias> aliases(String dimension, int maximum) {
        if (!validDimension(dimension)) return List.of();
        int limit = Math.max(0, Math.min(maximum, MAX_ALIASES));
        List<Alias> result = new ArrayList<>();
        Map<UUID, Set<String>> byDevice = aliases.getOrDefault(dimension, Map.of());
        for (Map.Entry<UUID, Set<String>> entry : byDevice.entrySet()) {
            for (String name : entry.getValue()) result.add(new Alias(entry.getKey(), name));
        }
        result.sort(Comparator.comparing(Alias::name).thenComparing(alias -> alias.deviceId().toString()));
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    private synchronized int count() {
        int total = 0;
        for (Map<UUID, Set<String>> byDevice : aliases.values()) {
            for (Set<String> names : byDevice.values()) total += names.size();
        }
        return total;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag root) {
        PersistedDataVersions.stampCurrent(root);
        ListTag saved = new ListTag();
        for (Map.Entry<String, Map<UUID, Set<String>>> dimension : aliases.entrySet()) {
            for (Map.Entry<UUID, Set<String>> device : dimension.getValue().entrySet()) {
                for (String name : device.getValue()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("Dimension", dimension.getKey());
                    entry.putUUID("DeviceId", device.getKey());
                    entry.putString("Name", name);
                    saved.add(entry);
                }
            }
        }
        root.put("Aliases", saved);
        return root;
    }

    static RednetDeviceAliasSavedData load(CompoundTag root) {
        RednetDeviceAliasSavedData data = new RednetDeviceAliasSavedData();
        ListTag saved = root.getList("Aliases", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size() && data.count() < MAX_ALIASES; index++) {
            CompoundTag entry = saved.getCompound(index);
            String dimension = entry.getString("Dimension");
            if (!entry.hasUUID("DeviceId")) continue;
            data.registerLoaded(dimension, entry.getUUID("DeviceId"), entry.getString("Name"));
        }
        return data;
    }

    private synchronized void registerLoaded(String dimension, UUID deviceId, String requestedName) {
        String name = RednetHostName.normalize(requestedName).orElse("");
        if (!validDimension(dimension) || deviceId == null || name.isEmpty() || count() >= MAX_ALIASES) return;
        Map<UUID, Set<String>> byDevice = aliases.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
        if (byDevice.values().stream().anyMatch(names -> names.contains(name))) return;
        byDevice.computeIfAbsent(deviceId, ignored -> new LinkedHashSet<>()).add(name);
    }

    private static boolean validDimension(String dimension) {
        return dimension != null && !dimension.isBlank() && dimension.length() <= MAX_DIMENSION_CHARS;
    }

    record Alias(UUID deviceId, String name) {}
}
