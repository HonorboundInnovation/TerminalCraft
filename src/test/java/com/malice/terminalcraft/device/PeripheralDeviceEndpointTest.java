package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless contract tests for modem and disk-drive Device API adapters. */
public final class PeripheralDeviceEndpointTest {
    private PeripheralDeviceEndpointTest() {}

    public static void main(String[] args) {
        testModem();
        testDiskDrive();
        System.out.println("Peripheral device endpoint tests: OK");
    }

    private static void testModem() {
        TestModem modem = new TestModem();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000005");
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new ModemDeviceEndpoint(id, "minecraft:overworld:2,64,2", modem, () -> true, () -> true));
        DeviceDescriptor descriptor = registry.descriptor(id).orElseThrow();
        assertTrue(descriptor.capabilities().contains("network_interface"), "modem capability");
        assertTrue(descriptor.eventTypes().contains("message_received"), "modem event");
        DeviceAccess write = registry.access(new DeviceCallContext(UUID.randomUUID(), "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
        assertTrue(write.call(id, "channel.open", List.of(DeviceValue.of(42))).isSuccess(), "open channel");
        assertTrue(write.call(id, "transmit", List.of(DeviceValue.of(42), DeviceValue.of(7), DeviceValue.of("ping"))).isSuccess(), "transmit");
        assertEquals("42:7:ping", modem.lastTransmission, "transmission arguments");
        modem.inbox.add("pong");
        DeviceResult received = write.call(id, "receive", List.of(DeviceValue.of(1)));
        assertEquals("pong", ((DeviceValue.StringValue) ((DeviceValue.ListValue) received.value().orElseThrow()).values().get(0)).value(), "receive");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                write.call(id, "channel.open", List.of(DeviceValue.of(65536))), "channel bound");
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                registry.call(id, "channel.close", List.of(DeviceValue.of(42))), "legacy read-only mutation");
    }

    private static void testDiskDrive() {
        TestDrive drive = new TestDrive();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000006");
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new DiskDriveDeviceEndpoint(id, "minecraft:overworld:3,64,2", drive, () -> true, () -> true));
        DeviceDescriptor descriptor = registry.descriptor(id).orElseThrow();
        assertTrue(descriptor.capabilities().contains("media_storage"), "drive capability");
        assertTrue(descriptor.eventTypes().contains("media_changed"), "drive event");
        assertError(DeviceErrorCode.NOT_FOUND, registry.call(id, "media.label.get", List.of()), "empty drive");
        drive.present = true;
        DeviceAccess write = registry.access(new DeviceCallContext(UUID.randomUUID(), "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
        assertTrue(write.call(id, "media.label.set", List.of(DeviceValue.of("backup"))).isSuccess(), "set label");
        assertEquals("backup", drive.label, "label mutation");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                write.call(id, "media.label.set", List.of(DeviceValue.of("x".repeat(65)))), "label bound");
    }

    private static final class TestModem implements ModemDevice {
        final List<Integer> channels = new ArrayList<>();
        final List<String> inbox = new ArrayList<>();
        String label = "modem";
        String lastTransmission;
        public int maxOpenChannels() { return 128; }
        public int maxReceiveBatch() { return 32; }
        public String label() { return label; }
        public void setLabel(String label) { this.label = label; }
        public boolean wireless() { return true; }
        public int range() { return 64; }
        public List<Integer> openChannels() { return List.copyOf(channels); }
        public int pendingCount() { return inbox.size(); }
        public boolean open(int channel) { if (!channels.contains(channel)) channels.add(channel); return true; }
        public boolean close(int channel) { return channels.remove((Integer) channel); }
        public void closeAll() { channels.clear(); }
        public boolean transmit(int channel, int replyChannel, String message) { lastTransmission = channel + ":" + replyChannel + ":" + message; return !channels.isEmpty(); }
        public List<String> receive(int limit) { List<String> out = new ArrayList<>(inbox.subList(0, Math.min(limit, inbox.size()))); inbox.removeAll(out); return out; }
    }

    private static final class TestDrive implements DiskDriveDevice {
        boolean present;
        String label = "floppy";
        public boolean mediaPresent() { return present; }
        public String mediaLabel() { return label; }
        public void setMediaLabel(String label) { this.label = label; }
    }

    private static void assertError(DeviceErrorCode code, DeviceResult result, String message) {
        if (result.isSuccess() || result.error().orElseThrow().code() != code) throw new AssertionError(message + ": " + result.error());
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
