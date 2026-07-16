package com.malice.terminalcraft.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bounded in-memory name directory for one RedNet dimension. */
final class RednetHostDirectory {
    static final int MAX_HOSTS = 1024;

    private final Map<String, UUID> byName = new HashMap<>();
    private final Map<UUID, String> byId = new HashMap<>();

    synchronized boolean register(UUID id, String requestedName) {
        return registerDetailed(id, requestedName).accepted();
    }

    synchronized RednetRegistrationResult registerDetailed(UUID id, String requestedName) {
        Optional<String> normalized = RednetHostName.normalize(requestedName);
        if (id == null || normalized.isEmpty()) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.INVALID, "");
        }
        String name = normalized.get();
        UUID existing = byName.get(name);
        if (existing != null && !existing.equals(id)) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.NAME_CONFLICT, name);
        }
        String previous = byId.get(id);
        if (existing == null && previous == null && byName.size() >= MAX_HOSTS) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.DIRECTORY_FULL, name);
        }
        if (name.equals(previous)) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.UNCHANGED, name);
        }

        byId.put(id, name);
        if (previous != null) byName.remove(previous, id);
        byName.put(name, id);
        return RednetRegistrationResult.of(
                previous == null ? RednetRegistrationResult.Status.CREATED
                        : RednetRegistrationResult.Status.UPDATED,
                name);
    }

    synchronized void unregister(UUID id) {
        if (id == null) return;
        String name = byId.remove(id);
        if (name != null) byName.remove(name, id);
    }

    synchronized Optional<UUID> resolve(String requestedName) {
        return RednetHostName.normalize(requestedName).map(byName::get);
    }

    synchronized String name(UUID id) { return byId.getOrDefault(id, ""); }

    synchronized List<String> names(int maximum) {
        int limit = Math.max(0, Math.min(maximum, MAX_HOSTS));
        List<String> result = new ArrayList<>(byName.keySet());
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    synchronized boolean isEmpty() { return byName.isEmpty(); }
}
