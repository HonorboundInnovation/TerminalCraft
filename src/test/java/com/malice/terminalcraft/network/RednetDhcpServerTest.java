package com.malice.terminalcraft.network;

import java.util.List;
import java.util.UUID;

/** Headless coverage for stable bounded RedNet DHCP-equivalent address allocation. */
public final class RednetDhcpServerTest {
    private RednetDhcpServerTest() {}

    public static void main(String[] args) {
        RednetDhcpServer server = new RednetDhcpServer();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID router = UUID.fromString("00000000-0000-0000-0000-000000000099");

        RednetLease one = server.ensure(first, "minecraft:overworld@1,2,3", router, 10);
        RednetLease renewed = server.ensure(first, "minecraft:overworld@1,2,3", router, 11);
        RednetLease two = server.ensure(second, "minecraft:overworld@1,2,3", router, 11);
        check(one != null && renewed != null && two != null, "leases must be admitted");
        check(one.address().equals(renewed.address()), "renewal must preserve a stable address");
        check(!one.address().equals(two.address()), "two modems must not share an address");
        check(renewed.routerIssued() && renewed.routerId().equals(router),
                "router-issued leases must retain their authority identity");
        check(renewed.expiresAt() > renewed.issuedAt(), "lease must have a bounded lifetime");

        RednetLease moved = server.ensure(first, "other-lan", null, 12);
        check(moved != null && !moved.networkId().equals(one.networkId()),
                "moving networks must obtain a new pool lease");
        check(!moved.routerIssued() && moved.source().equals("link-local"),
                "isolated networks must use the link-local fallback");
        check(server.leases(1).size() == 1, "lease listing must honor its bound");
        server.release(first);
        check(server.lease(first) == null, "release must remove the lease");
        check(RednetDhcpServer.networkId("same-network").equals(
                        RednetDhcpServer.networkId("same-network")),
                "network pool identity must be deterministic");

        System.out.println("RednetDhcpServerTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
