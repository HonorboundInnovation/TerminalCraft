package com.malice.terminalcraft.device;

import com.malice.terminalcraft.shell.TerminalHost;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Headless characterization tests for the permission-gated legacy host adapter. */
public final class TerminalHostDeviceEndpointTest {
    private TerminalHostDeviceEndpointTest() {}

    public static void main(String[] args) {
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicBoolean loaded = new AtomicBoolean(true);
        TestHost host = new TestHost();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
        TerminalHostDeviceEndpoint endpoint = new TerminalHostDeviceEndpoint(
                id, "overworld:10,64,20", host, online::get, loaded::get);
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(endpoint);

        DeviceDescriptor descriptor = endpoint.descriptor();
        assertEquals(id, descriptor.deviceId(), "identity is caller supplied");
        assertEquals("terminalcraft:terminal_host", descriptor.adapterId(), "adapter identity");
        assertEquals("turtle", descriptor.typeName(), "turtle type");
        assertTrue(descriptor.capabilities().contains("redstone_io"), "redstone capability");
        assertTrue(descriptor.capabilities().contains("turtle"), "turtle capability");
        assertEquals("alpha", stringProperty(descriptor, "label"), "label property");
        assertTrue(hasMethod(descriptor, "turtle.facing"), "turtle method advertised");

        assertString("alpha", registry.call(id, "label.get", List.of()), "label read");
        assertString("north", registry.call(id, "turtle.facing", List.of()), "facing read");
        assertNumber(9.0, registry.call(id, "redstone.input", List.of(DeviceValue.of("left"))),
                "input read");
        assertNumber(4.0, registry.call(id, "redstone.output", List.of(DeviceValue.of("right"))),
                "output read");

        DeviceResult sides = registry.call(id, "redstone.sides", List.of());
        DeviceValue.ListValue sideList = (DeviceValue.ListValue) sides.value().orElseThrow();
        assertEquals(2, sideList.values().size(), "side count");
        assertEquals("left", ((DeviceValue.StringValue) sideList.values().get(0)).value(), "side order");

        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                registry.call(id, "redstone.input", List.of(DeviceValue.of("top"))),
                "unknown side rejected before host access");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                registry.call(id, "label.get", List.of(DeviceValue.of("extra"))),
                "no-argument method validates schema");
        DeviceAccess readOnly = registry.access(DeviceCallContext.readOnly("reader"));
        DeviceAccess writable = registry.access(new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), "writer",
                java.util.Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                readOnly.call(id, "redstone.set", List.of(DeviceValue.of("left"), DeviceValue.of(15))),
                "read-only caller cannot mutate");
        assertEquals(0, host.mutations, "denied call does not reach host");
        assertTrue(writable.call(id, "redstone.set",
                List.of(DeviceValue.of("left"), DeviceValue.of(15))).isSuccess(), "redstone write");
        assertTrue(writable.call(id, "label.set", List.of(DeviceValue.of("beta"))).isSuccess(),
                "label write");
        assertTrue(writable.call(id, "turtle.move", List.of(DeviceValue.of("forward"))).isSuccess(),
                "turtle movement");
        assertTrue(writable.call(id, "turtle.turn", List.of(DeviceValue.of("left"))).isSuccess(),
                "turtle turn");
        assertEquals(4, host.mutations, "authorized mutations reach host");

        int readsBeforeUnload = host.reads;
        loaded.set(false);
        assertError(DeviceErrorCode.CHUNK_UNLOADED,
                registry.call(id, "label.get", List.of()), "live loaded state");
        assertEquals(readsBeforeUnload, host.reads, "unloaded descriptor does not touch stale host");
        loaded.set(true);
        online.set(false);
        assertError(DeviceErrorCode.OFFLINE,
                registry.call(id, "label.get", List.of()), "live online state");
        assertEquals(readsBeforeUnload, host.reads, "offline descriptor does not touch stale host");

        TestHost computer = new TestHost();
        computer.turtle = false;
        TerminalHostDeviceEndpoint computerEndpoint = new TerminalHostDeviceEndpoint(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "overworld:11,64,20", computer, () -> true, () -> true);
        assertTrue(!computerEndpoint.descriptor().capabilities().contains("turtle"),
                "computer omits turtle capability");
        assertTrue(!hasMethod(computerEndpoint.descriptor(), "turtle.facing"),
                "computer omits turtle method");
        assertError(DeviceErrorCode.UNSUPPORTED,
                computerEndpoint.call("turtle.facing", List.of()), "computer rejects turtle call");

        System.out.println("TerminalHost device endpoint tests: OK");
    }

    private static final class TestHost implements TerminalHost {
        private boolean turtle = true;
        private int mutations;
        private int reads;

        @Override public int getRedstoneInput(String side) { return "left".equals(side) ? 9 : 0; }
        @Override public int getRedstoneOutput(String side) { return "right".equals(side) ? 4 : 0; }
        @Override public boolean setRedstoneOutput(String side, int power) { mutations++; return true; }
        @Override public List<String> redstoneSides() { return List.of("left", "right"); }
        @Override public List<String> listPeripherals() { return List.of(); }
        @Override public String getLabel() { reads++; return "alpha"; }
        @Override public void setLabel(String label) { mutations++; }
        @Override public boolean isTurtle() { reads++; return turtle; }
        @Override public String turtleFacing() { return "north"; }
        @Override public boolean turtleForward() { mutations++; return true; }
        @Override public boolean turtleTurnLeft() { mutations++; return true; }
    }

    private static String stringProperty(DeviceDescriptor descriptor, String name) {
        return ((DeviceValue.StringValue) descriptor.properties().get(name)).value();
    }

    private static boolean hasMethod(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().anyMatch(method -> method.name().equals(name));
    }

    private static void assertString(String expected, DeviceResult result, String message) {
        assertTrue(result.isSuccess(), message + " succeeds");
        assertEquals(expected, ((DeviceValue.StringValue) result.value().orElseThrow()).value(), message);
    }

    private static void assertNumber(double expected, DeviceResult result, String message) {
        assertTrue(result.isSuccess(), message + " succeeds");
        assertEquals(expected, ((DeviceValue.NumberValue) result.value().orElseThrow()).value(), message);
    }

    private static void assertError(DeviceErrorCode expected, DeviceResult result, String message) {
        if (result.isSuccess() || result.error().orElseThrow().code() != expected) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + result.error());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
