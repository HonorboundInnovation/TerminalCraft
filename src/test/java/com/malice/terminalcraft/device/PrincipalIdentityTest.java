package com.malice.terminalcraft.device;

import java.util.Set;
import java.util.UUID;

/** Contract coverage for kind-scoped player, device, service and process identities. */
public final class PrincipalIdentityTest {
    private PrincipalIdentityTest() {}

    public static void main(String[] args) {
        UUID shared = UUID.fromString("00000000-0000-0000-0000-000000000401");
        PrincipalIdentity player = PrincipalIdentity.player(shared, "alice");
        PrincipalIdentity device = PrincipalIdentity.device(shared, "terminal-1");
        PrincipalIdentity service = PrincipalIdentity.service(shared, "storage-indexer");
        PrincipalIdentity process = PrincipalIdentity.process(shared, "index-worker");

        require(!player.equals(device) && !device.equals(service) && !service.equals(process),
                "identity kind is authoritative even when UUIDs collide");
        require("player:".concat(shared.toString()).equals(player.authorityKey()),
                "player authority key is stable and namespaced");
        require("process:".concat(shared.toString()).equals(process.authorityKey()),
                "process authority key is stable and namespaced");
        require(PrincipalIdentity.Kind.parse("SERVICE") == PrincipalIdentity.Kind.SERVICE,
                "kind parser is case insensitive");

        DeviceCallContext legacy = new DeviceCallContext(shared, "alice", Set.of(DeviceCallContext.READ));
        require(legacy.principal().equals(player), "compatibility constructor creates a player identity");
        DeviceCallContext processContext = DeviceCallContext.process(shared, "index-worker",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        require(processContext.principalKind() == PrincipalIdentity.Kind.PROCESS,
                "typed process context retains kind");
        require(processContext.principalId().equals(shared)
                        && processContext.principalName().equals("index-worker"),
                "legacy accessors project the typed principal");
        require(!legacy.authorityKey().equals(processContext.authorityKey()),
                "quota and authorization keys cannot collide across kinds");

        reject(() -> new PrincipalIdentity(null, shared, "bad"), "missing kind");
        reject(() -> PrincipalIdentity.player(shared, " "), "blank name");
        reject(() -> PrincipalIdentity.Kind.parse("root"), "unknown kind");

        System.out.println("Principal identity tests: OK");
    }

    private static void reject(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + ": expected rejection");
        } catch (IllegalArgumentException | NullPointerException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
