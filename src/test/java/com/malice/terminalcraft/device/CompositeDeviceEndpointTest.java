package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless characterization of additive endpoint composition and contextual dispatch. */
public final class CompositeDeviceEndpointTest {
    private CompositeDeviceEndpointTest() {}

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        String address = "test:device";
        DeviceCallContext caller = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

        DeviceEndpoint primary = endpoint(id, address, "primary.read", "primary", false);
        DeviceEndpoint contextual = endpoint(id, address, "crafting.submit", "contextual", true);
        CompositeDeviceEndpoint composite = new CompositeDeviceEndpoint(primary, contextual);

        DeviceDescriptor descriptor = composite.descriptor();
        require(descriptor.deviceId().equals(id), "composite preserves physical identity");
        require(descriptor.methods().size() == 2, "composite exposes both methods");
        require(descriptor.capabilities().containsAll(Set.of("primary", "contextual")),
                "composite unions capabilities");
        require(descriptor.permissions().containsAll(Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)),
                "composite unions permissions");

        DeviceResult contextualResult = composite.call(caller, "crafting.submit", List.of());
        require(contextualResult.isSuccess(), "contextual method succeeds");
        DeviceValue.MapValue contextualValue = (DeviceValue.MapValue) contextualResult.value().orElseThrow();
        require(((DeviceValue.StringValue) contextualValue.values().get("principal")).value()
                        .equals(caller.principalId().toString()),
                "composite forwards the authenticated caller context");

        require(composite.call(caller, "missing", List.of()).error().orElseThrow().code() == DeviceErrorCode.UNSUPPORTED,
                "unknown method is rejected");

        expectFailure(() -> new CompositeDeviceEndpoint(primary,
                endpoint(UUID.randomUUID(), address, "other", "other", false)),
                "different device identity is rejected");
        expectFailure(() -> new CompositeDeviceEndpoint(primary,
                endpoint(id, "test:other", "other", "other", false)),
                "different address is rejected");
        expectFailure(() -> new CompositeDeviceEndpoint(primary,
                endpoint(id, address, "primary.read", "duplicate", false)).descriptor(),
                "duplicate methods are rejected");

        System.out.println("Composite device endpoint tests: OK");
    }

    private static DeviceEndpoint endpoint(UUID id, String address, String method,
                                           String capability, boolean contextual) {
        DeviceMethodDescriptor descriptor = new DeviceMethodDescriptor(method, "test method", List.of(),
                DeviceValueType.MAP, contextual ? DeviceCallContext.WRITE : DeviceCallContext.READ);
        DeviceDescriptor device = new DeviceDescriptor(id, "terminalcraft:test", "test_device",
                "Test device", "terminalcraft", address, Set.of(capability), Map.of(),
                List.of(descriptor), Set.of(), Set.of(descriptor.requiredPermission()), true, true);
        if (contextual) {
            return new ContextualDeviceEndpoint() {
                @Override public DeviceDescriptor descriptor() { return device; }
                @Override public DeviceResult call(DeviceCallContext caller, String ignored,
                                                    List<DeviceValue> arguments) {
                    return DeviceResult.success(DeviceValue.map(Map.of(
                            "principal", DeviceValue.of(caller.principalId().toString()))));
                }
            };
        }
        return new DeviceEndpoint() {
            @Override public DeviceDescriptor descriptor() { return device; }
            @Override public DeviceResult call(String ignored, List<DeviceValue> arguments) {
                return DeviceResult.success(DeviceValue.map(Map.of("delegate", DeviceValue.of(capability))));
            }
        };
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
