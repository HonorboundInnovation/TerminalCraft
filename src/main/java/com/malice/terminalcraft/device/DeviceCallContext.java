package com.malice.terminalcraft.device;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-created identity and grants for one device API invocation chain.
 * Callers received from the network must be constructed from the authenticated server player,
 * never from client-supplied fields.
 */
public record DeviceCallContext(PrincipalIdentity principal, Set<String> permissions) {
    public static final String READ = "device.read";
    public static final String WRITE = "device.write";
    public static final String ESCROW_ADMIN = "device.escrow.admin";

    public DeviceCallContext {
        principal = Objects.requireNonNull(principal, "principal");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        permissions.forEach(value -> DeviceMethodDescriptor.requireIdentifier(value, "permission"));
    }

    /** Compatibility constructor: existing authenticated callers are players unless typed explicitly. */
    public DeviceCallContext(UUID principalId, String principalName, Set<String> permissions) {
        this(PrincipalIdentity.player(principalId, principalName), permissions);
    }

    public static DeviceCallContext player(UUID id, String name, Set<String> permissions) {
        return new DeviceCallContext(PrincipalIdentity.player(id, name), permissions);
    }

    public static DeviceCallContext device(UUID id, String name, Set<String> permissions) {
        return new DeviceCallContext(PrincipalIdentity.device(id, name), permissions);
    }

    public static DeviceCallContext service(UUID id, String name, Set<String> permissions) {
        return new DeviceCallContext(PrincipalIdentity.service(id, name), permissions);
    }

    public static DeviceCallContext process(UUID id, String name, Set<String> permissions) {
        return new DeviceCallContext(PrincipalIdentity.process(id, name), permissions);
    }

    public static DeviceCallContext readOnly(String name) {
        String boundedName = Objects.requireNonNull(name, "name");
        UUID id = UUID.nameUUIDFromBytes(("terminalcraft:readonly:" + boundedName)
                .getBytes(StandardCharsets.UTF_8));
        return service(id, boundedName, Set.of(READ));
    }

    public UUID principalId() {
        return principal.id();
    }

    public String principalName() {
        return principal.name();
    }

    public PrincipalIdentity.Kind principalKind() {
        return principal.kind();
    }

    public String authorityKey() {
        return principal.authorityKey();
    }

    public boolean allows(String permission) {
        return DeviceAuthorization.allows(this, Objects.requireNonNull(permission, "permission"));
    }
}
