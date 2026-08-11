package com.malice.terminalcraft.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded in-memory name directory for one RedNet dimension. */
final class RednetHostDirectory {
    static final int MAX_HOSTS = 1024;

    private final Map<String, UUID> byName = new HashMap<>();
    private final Map<UUID, String> byId = new HashMap<>();
    private final Map<UUID, Set<String>> aliases = new HashMap<>();

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
        Set<String> ownedAliases = aliases.get(id);
        if (ownedAliases != null) ownedAliases.remove(name);
        byName.put(name, id);
        return RednetRegistrationResult.of(
                previous == null ? RednetRegistrationResult.Status.CREATED
                        : RednetRegistrationResult.Status.UPDATED,
                name);
    }

    /** Registers an additional DNS alias without changing the endpoint's primary hostname. */
    synchronized RednetRegistrationResult registerAliasDetailed(UUID id, String requestedName) {
        Optional<String> normalized = RednetHostName.normalize(requestedName);
        if (id == null || normalized.isEmpty()) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.INVALID, "");
        }
        String name = normalized.get();
        UUID existing = byName.get(name);
        if (existing != null && !existing.equals(id)) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.NAME_CONFLICT, name);
        }
        if (existing != null) {
            aliases.computeIfAbsent(id, ignored -> new HashSet<>()).add(name);
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.UNCHANGED, name);
        }
        if (byName.size() >= MAX_HOSTS) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.DIRECTORY_FULL, name);
        }
        byName.put(name, id);
        aliases.computeIfAbsent(id, ignored -> new HashSet<>()).add(name);
        byId.putIfAbsent(id, name);
        return RednetRegistrationResult.of(RednetRegistrationResult.Status.CREATED, name);
    }

    /** Removes only one explicit alias, promoting another alias if it was the primary name. */
    synchronized boolean unregisterAlias(UUID id, String requestedName) {
        if (id == null) return false;
        Optional<String> normalized = RednetHostName.normalize(requestedName);
        if (normalized.isEmpty()) return false;
        String name = normalized.get();
        if (!id.equals(byName.get(name))) return false;
        Set<String> ownedAliases = aliases.get(id);
        boolean explicitAlias = ownedAliases != null && ownedAliases.remove(name);
        String primary = byId.get(id);
        if (!explicitAlias && !name.equals(primary)) return false;
        byName.remove(name, id);
        if (name.equals(primary)) {
            String replacement = ownedAliases == null ? null : ownedAliases.stream().sorted().findFirst().orElse(null);
            if (replacement == null) byId.remove(id);
            else byId.put(id, replacement);
        }
        if (ownedAliases != null && ownedAliases.isEmpty()) aliases.remove(id);
        return true;
    }

    synchronized void unregister(UUID id) {
        if (id == null) return;
        String name = byId.remove(id);
        if (name != null) byName.remove(name, id);
        Set<String> ownedAliases = aliases.remove(id);
        if (ownedAliases != null) {
            for (String alias : ownedAliases) byName.remove(alias, id);
        }
    }

    synchronized Optional<UUID> resolve(String requestedName) {
        return RednetHostName.normalize(requestedName).map(byName::get);
    }

    /**
     * Resolves a user supplied destination selector.  Host aliases remain the normal form, but a
     * stable UUID (or the explicit {@code rednet:<uuid>[/alias]} representation) is also accepted.
     * The UUID path is deliberately independent of the alias index so renaming a modem never
     * invalidates scripts that stored its stable identity.
     */
    synchronized Optional<UUID> resolveDestination(String selector) {
        if (selector == null || selector.isBlank()) return Optional.empty();
        Optional<RednetAddress> encoded = RednetAddress.parse(selector.trim());
        if (encoded.isPresent()) return encoded.map(RednetAddress::deviceId);
        try {
            return Optional.of(UUID.fromString(selector.trim()));
        } catch (IllegalArgumentException ignored) {
            return resolve(selector);
        }
    }

    synchronized String name(UUID id) { return byId.getOrDefault(id, ""); }

    synchronized List<String> names(int maximum) {
        int limit = Math.max(0, Math.min(maximum, MAX_HOSTS));
        List<String> result = new ArrayList<>(byName.keySet());
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    synchronized List<RednetAddress> addresses(int maximum) {
        int limit = Math.max(0, Math.min(maximum, MAX_HOSTS));
        List<RednetAddress> result = new ArrayList<>(byName.size());
        byName.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(limit)
                .forEach(entry -> result.add(new RednetAddress(entry.getValue(), entry.getKey())));
        return List.copyOf(result);
    }

    synchronized boolean isEmpty() { return byName.isEmpty(); }
}
