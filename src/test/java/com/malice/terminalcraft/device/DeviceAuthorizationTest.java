package com.malice.terminalcraft.device;

import java.util.Set;
import java.util.UUID;

/** Headless contract coverage for the central TerminalCraft authorization interpreter. */
public final class DeviceAuthorizationTest {
    private DeviceAuthorizationTest() {}

    public static void main(String[] args) {
        UUID shared = UUID.randomUUID();
        DeviceCallContext reader = DeviceCallContext.player(shared, "reader",
                Set.of(DeviceCallContext.READ));
        DeviceCallContext writer = DeviceCallContext.service(shared, "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        DeviceCallContext administrator = DeviceCallContext.process(UUID.randomUUID(), "recovery",
                Set.of(DeviceCallContext.ESCROW_ADMIN));

        require(DeviceAuthorization.decide(reader, DeviceAuthorization.Action.READ).allowed(),
                "read grant authorizes reads");
        DeviceAuthorization.Decision denied =
                DeviceAuthorization.decide(reader, DeviceAuthorization.Action.MUTATE);
        require(!denied.allowed(), "read-only caller cannot mutate");
        require(denied.requiredPermission().equals(DeviceCallContext.WRITE),
                "denial reports the canonical required permission");
        require(!denied.reason().isBlank(), "denial is diagnosable");
        require(DeviceAuthorization.require(reader, DeviceAuthorization.Action.MUTATE)
                        .error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "central denial maps to a structured device error");

        require(DeviceAuthorization.allows(writer, DeviceAuthorization.Action.MUTATE),
                "write grant authorizes mutation regardless of principal kind");
        require(!DeviceAuthorization.allows(writer, DeviceAuthorization.Action.ESCROW_ADMIN),
                "write does not imply administration");
        require(DeviceAuthorization.allows(administrator, DeviceAuthorization.Action.ESCROW_ADMIN),
                "explicit administration grant is honored");
        require(!DeviceAuthorization.allows(administrator, DeviceAuthorization.Action.READ),
                "administration does not silently imply discovery or read access");

        require(!DeviceAuthorization.decide(null, DeviceAuthorization.Action.READ).allowed(),
                "missing context fails closed");
        require(!DeviceAuthorization.allows(reader, "integration.native.control"),
                "unknown but valid endpoint permission is denied unless explicitly granted");
        DeviceCallContext extension = DeviceCallContext.device(UUID.randomUUID(), "extension",
                Set.of("integration.native.control"));
        require(DeviceAuthorization.allows(extension, "integration.native.control"),
                "endpoint-specific grants use the same central interpreter");

        require(!DeviceAuthorization.decide(reader, DeviceAuthorization.Action.MUTATE,
                        true, "native policy denied").allowed(),
                "secondary policy cannot create a missing TerminalCraft grant");
        DeviceAuthorization.Decision nativeDenied = DeviceAuthorization.decide(
                writer, DeviceAuthorization.Action.MUTATE, false, "native policy denied");
        require(!nativeDenied.allowed() && nativeDenied.reason().equals("native policy denied"),
                "deny-only native policy narrows an existing mutation grant with diagnostics");
        require(DeviceAuthorization.decide(writer, DeviceAuthorization.Action.MUTATE,
                        true, "unused").allowed(),
                "mutation requires both the TerminalCraft grant and secondary authority");

        // Same UUID does not transfer grants: authorization consumes the server-issued context,
        // not UUID possession or a principal display name.
        require(!DeviceAuthorization.allows(reader, DeviceAuthorization.Action.MUTATE)
                        && DeviceAuthorization.allows(writer, DeviceAuthorization.Action.MUTATE),
                "same-UUID cross-kind contexts retain independent grants");
        require(DeviceAuthorization.owns(reader, reader.principal()),
                "exact typed principal owns its resources");
        require(!DeviceAuthorization.owns(writer, reader.principal()),
                "same UUID with another principal kind does not own the resource");
        require(!DeviceAuthorization.owns((DeviceCallContext) null, reader.principal())
                        && !DeviceAuthorization.owns(reader, null),
                "missing ownership inputs fail closed");

        require(reader.allows(DeviceCallContext.READ)
                        && !reader.allows(DeviceCallContext.WRITE),
                "legacy context helper delegates to the central interpreter");

        System.out.println("Device authorization tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
