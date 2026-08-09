package com.malice.terminalcraft.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded RedNet DHCP-equivalent allocator.
 *
 * <p>This is an in-world address allocator, not a real network socket. Each logical wired subnet
 * gets a private-looking /24 pool and each modem receives a stable address for the lifetime of
 * the server runtime. A registered router is the lease authority when one is present; isolated
 * cable and wireless links use the same allocator as a safe link-local fallback.</p>
 */
public final class RednetDhcpServer {
    public static final int MAX_LEASES = 4096;
    public static final int MIN_HOST = 2;
    public static final int MAX_HOST = 254;
    public static final long LEASE_TICKS = 1_200;

    private final Map<UUID, RednetLease> byModem = new HashMap<>();
    private final Map<String, Map<Integer, UUID>> ownersByNetwork = new HashMap<>();

    /** Allocates or renews a lease, preserving the address whenever the logical network is stable. */
    public synchronized RednetLease ensure(UUID modemId, String networkIdentity, UUID routerId,
                                            long now) {
        if (modemId == null || networkIdentity == null || networkIdentity.isBlank()) return null;
        String networkId = networkId(networkIdentity);
        RednetLease previous = byModem.get(modemId);
        if (previous != null && now > previous.expiresAt()) {
            release(modemId);
            previous = null;
        }
        if (previous != null && previous.networkId().equals(networkId)) {
            int host = hostOctet(previous.address());
            if (host >= MIN_HOST && host <= MAX_HOST) {
                ownersByNetwork.computeIfAbsent(networkId, ignored -> new HashMap<>()).put(host, modemId);
                return new RednetLease(modemId, networkId, previous.address(), routerId,
                        previous.issuedAt(), safeExpiry(now));
            }
        }
        release(modemId);
        if (byModem.size() >= MAX_LEASES) return null;
        Map<Integer, UUID> owners = ownersByNetwork.computeIfAbsent(networkId, ignored -> new HashMap<>());
        int preferred = MIN_HOST + Math.floorMod(hash(modemId), MAX_HOST - MIN_HOST + 1);
        int host = findFreeHost(owners, preferred);
        if (host < 0) return null;
        owners.put(host, modemId);
        String address = address(networkId, host);
        RednetLease lease = new RednetLease(modemId, networkId, address, routerId, now, safeExpiry(now));
        byModem.put(modemId, lease);
        return lease;
    }

    public synchronized RednetLease lease(UUID modemId) {
        return modemId == null ? null : byModem.get(modemId);
    }

    public synchronized void release(UUID modemId) {
        if (modemId == null) return;
        RednetLease previous = byModem.remove(modemId);
        if (previous == null) return;
        Map<Integer, UUID> owners = ownersByNetwork.get(previous.networkId());
        if (owners != null) {
            int host = hostOctet(previous.address());
            owners.remove(host, modemId);
            if (owners.isEmpty()) ownersByNetwork.remove(previous.networkId());
        }
    }

    public synchronized List<RednetLease> leases(int maximum) {
        int limit = Math.max(0, Math.min(maximum, MAX_LEASES));
        return byModem.values().stream()
                .sorted(Comparator.comparing(RednetLease::networkId)
                        .thenComparing(RednetLease::address)
                        .thenComparing(lease -> lease.modemId().toString()))
                .limit(limit)
                .toList();
    }

    /** Canonical short identifier for a physical subnet or automatic wireless pool. */
    public static String networkId(String networkIdentity) {
        if (networkIdentity == null || networkIdentity.isBlank()) return "rednet-link-local";
        String digest = digest(networkIdentity);
        return "rednet-" + digest.substring(0, 12);
    }

    private static int findFreeHost(Map<Integer, UUID> owners, int preferred) {
        int size = MAX_HOST - MIN_HOST + 1;
        for (int offset = 0; offset < size; offset++) {
            int candidate = MIN_HOST + Math.floorMod(preferred - MIN_HOST + offset, size);
            if (!owners.containsKey(candidate)) return candidate;
        }
        return -1;
    }

    private static int hash(UUID id) {
        return id.hashCode() ^ (int) (id.getMostSignificantBits() >>> 32)
                ^ (int) id.getLeastSignificantBits();
    }

    private static int hostOctet(String address) {
        int separator = address == null ? -1 : address.lastIndexOf('.');
        if (separator < 0) return -1;
        try {
            return Integer.parseInt(address.substring(separator + 1));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static String address(String networkId, int host) {
        byte[] digest = hexBytes(digest(networkId));
        int second = 16 + Byte.toUnsignedInt(digest[0]) % 16;
        int third = Byte.toUnsignedInt(digest[1]);
        return "10." + second + "." + third + "." + host;
    }

    private static long safeExpiry(long now) {
        if (now < 0 || Long.MAX_VALUE - now < LEASE_TICKS) return Long.MAX_VALUE;
        return now + LEASE_TICKS;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) result.append(String.format("%02x", Byte.toUnsignedInt(b)));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
