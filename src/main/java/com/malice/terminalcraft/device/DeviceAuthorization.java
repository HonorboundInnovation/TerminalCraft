package com.malice.terminalcraft.device;

import java.util.Objects;

/**
 * Single policy boundary for TerminalCraft-owned authorization decisions.
 *
 * <p>Callers may carry server-issued grants, but production code asks this service to interpret
 * those grants. Native mod security, claims, endpoint visibility, and lifecycle checks remain
 * additional mandatory authorities and are never replaced by this decision.</p>
 */
public final class DeviceAuthorization {
    private DeviceAuthorization() {}

    public enum Action {
        DISCOVER(DeviceCallContext.READ, "device discovery requires device.read"),
        READ(DeviceCallContext.READ, "device read requires device.read"),
        MUTATE(DeviceCallContext.WRITE, "device mutation requires device.write"),
        ESCROW_ADMIN(DeviceCallContext.ESCROW_ADMIN,
                "escrow administration requires device.escrow.admin");

        private final String permission;
        private final String denialReason;

        Action(String permission, String denialReason) {
            this.permission = permission;
            this.denialReason = denialReason;
        }

        public String permission() { return permission; }
        public String denialReason() { return denialReason; }
    }

    public record Decision(boolean allowed, String requiredPermission, String reason) {
        public Decision {
            requiredPermission = DeviceMethodDescriptor.requireIdentifier(
                    Objects.requireNonNull(requiredPermission, "requiredPermission"),
                    "required permission");
            reason = Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) throw new IllegalArgumentException("decision reason is required");
        }
    }

    public static Decision decide(DeviceCallContext context, Action action) {
        Objects.requireNonNull(action, "action");
        return decide(context, action.permission(), action.denialReason());
    }

    /** Supports namespaced endpoint permissions while retaining one fail-closed interpreter. */
    public static Decision decide(DeviceCallContext context, String requiredPermission) {
        String permission = DeviceMethodDescriptor.requireIdentifier(
                Objects.requireNonNull(requiredPermission, "requiredPermission"),
                "required permission");
        return decide(context, permission, "permission required: " + permission);
    }

    public static boolean allows(DeviceCallContext context, Action action) {
        return decide(context, action).allowed();
    }

    public static boolean allows(DeviceCallContext context, String requiredPermission) {
        return decide(context, requiredPermission).allowed();
    }

    /**
     * Composes a TerminalCraft grant with an additional deny-only authority.
     * The secondary authority can narrow access but can never create a missing grant.
     */
    public static Decision decide(DeviceCallContext context, Action action,
                                  boolean secondaryAllowed, String secondaryDenialReason) {
        Decision primary = decide(context, action);
        if (!primary.allowed()) return primary;
        if (secondaryAllowed) return primary;
        String reason = Objects.requireNonNull(secondaryDenialReason, "secondaryDenialReason");
        if (reason.isBlank()) throw new IllegalArgumentException("secondary denial reason is required");
        return new Decision(false, action.permission(), reason);
    }

    /** Exact typed-principal ownership; UUID or display-name possession never implies ownership. */
    public static boolean owns(DeviceCallContext context, PrincipalIdentity owner) {
        return context != null && owner != null && owner.equals(context.principal());
    }

    /** Exact typed-principal comparison for persistence layers that already extracted a caller. */
    public static boolean owns(PrincipalIdentity principal, PrincipalIdentity owner) {
        return principal != null && owner != null && owner.equals(principal);
    }

    public static DeviceResult require(DeviceCallContext context, Action action) {
        Decision decision = decide(context, action);
        return decision.allowed() ? DeviceResult.success()
                : DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED, decision.reason(), false);
    }

    public static DeviceResult require(DeviceCallContext context, String requiredPermission) {
        Decision decision = decide(context, requiredPermission);
        return decision.allowed() ? DeviceResult.success()
                : DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED, decision.reason(), false);
    }

    private static Decision decide(DeviceCallContext context, String permission, String denialReason) {
        boolean allowed = context != null && context.permissions().contains(permission);
        return new Decision(allowed, permission, allowed ? "authorized" : denialReason);
    }
}
