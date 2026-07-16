package com.malice.terminalcraft.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bounded dimension-local directory mapping unique service names to modem ports. */
final class RednetServiceDirectory {
    static final int MAX_SERVICES = 1024;
    static final int MAX_SERVICES_PER_MODEM = 32;
    static final RednetProtocol LEGACY_PROTOCOL = new RednetProtocol(
            "terminalcraft:rednet-service", 1, NetworkEnvelope.TEXT_PAYLOAD);

    record Service(String name, UUID modemId, int port, RednetProtocol protocol) {}

    private final Map<String, Service> byName = new HashMap<>();
    private final Map<UUID, Map<String, Service>> byModem = new HashMap<>();

    synchronized boolean register(UUID modemId, String requestedName, int port) {
        return register(modemId, requestedName, port, LEGACY_PROTOCOL);
    }

    synchronized boolean register(UUID modemId, String requestedName, int port, RednetProtocol protocol) {
        return registerDetailed(modemId, requestedName, port, protocol).accepted();
    }

    synchronized RednetRegistrationResult registerDetailed(UUID modemId, String requestedName,
                                                            int port, RednetProtocol protocol) {
        Optional<String> normalized = RednetHostName.normalize(requestedName);
        if (modemId == null || port < 0 || port > 65535 || protocol == null || normalized.isEmpty()) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.INVALID, "");
        }
        String name = normalized.get();
        Service existing = byName.get(name);
        if (existing != null && !existing.modemId().equals(modemId)) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.NAME_CONFLICT, name);
        }
        Map<String, Service> owned = byModem.get(modemId);
        boolean creating = existing == null;
        if (creating && byName.size() >= MAX_SERVICES) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.DIRECTORY_FULL, name);
        }
        if (creating && owned != null && owned.size() >= MAX_SERVICES_PER_MODEM) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.OWNER_LIMIT, name);
        }
        if (existing != null && existing.port() == port && existing.protocol().equals(protocol)) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.UNCHANGED, name);
        }
        if (owned == null) owned = byModem.computeIfAbsent(modemId, ignored -> new HashMap<>());
        Service service = new Service(name, modemId, port, protocol);
        byName.put(name, service);
        owned.put(name, service);
        return RednetRegistrationResult.of(creating ? RednetRegistrationResult.Status.CREATED
                : RednetRegistrationResult.Status.UPDATED, name);
    }

    synchronized boolean unregister(UUID modemId, String requestedName) {
        if (modemId == null) return false;
        Optional<String> normalized = RednetHostName.normalize(requestedName);
        if (normalized.isEmpty()) return false;
        String name = normalized.get();
        Service existing = byName.get(name);
        if (existing == null || !existing.modemId().equals(modemId)) return false;
        byName.remove(name);
        Map<String, Service> owned = byModem.get(modemId);
        if (owned != null) {
            owned.remove(name);
            if (owned.isEmpty()) byModem.remove(modemId);
        }
        return true;
    }

    synchronized void unregisterAll(UUID modemId) {
        Map<String, Service> owned = byModem.remove(modemId);
        if (owned != null) owned.keySet().forEach(name -> byName.remove(name));
    }

    synchronized Optional<Service> resolve(String requestedName) {
        return RednetHostName.normalize(requestedName).map(byName::get);
    }

    synchronized List<Service> services(int maximum) {
        int limit = Math.max(0, Math.min(maximum, MAX_SERVICES));
        List<Service> result = new ArrayList<>(byName.values());
        result.sort(Comparator.comparing(Service::name));
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    synchronized List<Service> services(UUID modemId) {
        Map<String, Service> owned = byModem.get(modemId);
        if (owned == null) return List.of();
        return owned.values().stream().sorted(Comparator.comparing(Service::name)).toList();
    }
}
