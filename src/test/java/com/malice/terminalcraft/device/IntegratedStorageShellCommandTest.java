package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless tests for the default Sophisticated Storage and Storage Drawers terminal programs. */
public final class IntegratedStorageShellCommandTest {
    private IntegratedStorageShellCommandTest() {}

    public static void main(String[] args) {
        UUID sophisticatedId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID drawersId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        TestStorage sophisticated = new TestStorage();
        TestStorage drawers = new TestStorage();
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(endpoint(sophisticatedId, "sophisticated_storage", "sophisticated_storage",
                "north", sophisticated, Map.of("sophisticated_locked", DeviceValue.of(false))));
        registry.register(endpoint(drawersId, "storage_drawer", "storage_drawers",
                "south", drawers, Map.of("storage_drawers_drawer_count", DeviceValue.of(4))));

        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.SOPHISTICATED, "list"),
                0, List.of("0  " + sophisticatedId + "  online  test:storage@north  sophisticated_storage"),
                "Sophisticated list filters unrelated devices");
        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS, "list"),
                0, List.of("0  " + drawersId + "  online  test:storage@south  storage_drawer"),
                "drawer list filters unrelated devices");

        IntegratedStorageShellCommand.Outcome info = run(registry,
                IntegratedStorageShellCommand.Profile.SOPHISTICATED, "info", "north");
        assertEquals(0, info.exitCode(), "side selector info exit");
        assertTrue(info.lines().contains("id: " + sophisticatedId), "side selector resolves device");
        assertTrue(info.lines().stream().anyMatch(line -> line.contains("sophisticated_locked")),
                "specialized properties visible");

        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.SOPHISTICATED,
                        "items", "0", "1"), 0,
                List.of("{\"entries\": [{\"count\": \"12\", \"resource\": \"minecraft:iron_ingot\", \"tags\": []}], \"has_more\": true, \"next_cursor\": \"1\"}"),
                "bounded item query");
        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS,
                        "count", drawersId.toString(), "minecraft:iron_ingot"), 0,
                List.of("12"), "count by UUID");
        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS,
                        "simulate-extract", "0", "minecraft:iron_ingot", "5"), 0,
                List.of("5"), "read-only simulation");
        assertEquals(0, drawers.mutations, "simulation does not mutate");

        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS,
                        "insert", "0", "minecraft:iron_ingot", "5"), 1,
                List.of("drawers: permission_denied: permission required: device.write"),
                "read-only access denies mutation");
        assertEquals(0, drawers.mutations, "denied mutation never reaches storage");

        DeviceCallContext writerContext = new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000203"), "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        assertOutcome(IntegratedStorageShellCommand.execute(registry.access(writerContext),
                        IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS,
                        List.of("extract", "south", "minecraft:iron_ingot", "5")), 0,
                List.of("{\"complete\": true, \"executed\": 5, \"requested\": 5, \"simulated\": 5}"),
                "authorized extraction");
        assertEquals(1, drawers.mutations, "authorized mutation executes once");

        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.SOPHISTICATED,
                        "items", "0", "0"), 1,
                List.of("sophisticated: limit must be an integer from 1 to 64"),
                "invalid limit rejected");
        assertOutcome(run(registry, IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS,
                        "count", "9", "minecraft:stone"), 1,
                List.of("drawers: device index out of range: 9"),
                "invalid index rejected");
        assertEquals(1, drawers.mutations, "invalid commands do not mutate");

        IntegratedStorageShellCommand.Outcome help = run(registry,
                IntegratedStorageShellCommand.Profile.SOPHISTICATED, "help");
        assertEquals(0, help.exitCode(), "help succeeds");
        assertTrue(help.lines().get(0).equals("usage: sophisticated list"), "profile-specific help");
        assertOutcome(IntegratedStorageShellCommand.execute(null,
                        IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS, List.of("list")),
                1, List.of("drawers: device registry unavailable"), "missing host access");

        System.out.println("Integrated storage shell command tests: OK");
    }

    private static GenericCapabilityDeviceEndpoint endpoint(UUID id, String type, String capability,
                                                              String side, TestStorage storage,
                                                              Map<String, DeviceValue> properties) {
        return new GenericCapabilityDeviceEndpoint(id, "test:" + type, type, type,
                "test", "test:storage@" + side, storage, () -> true, () -> true,
                Set.of(capability), properties);
    }

    private static IntegratedStorageShellCommand.Outcome run(
            DeviceRegistry registry, IntegratedStorageShellCommand.Profile profile, String... args) {
        return IntegratedStorageShellCommand.execute(
                registry.access(DeviceCallContext.readOnly("test")), profile, List.of(args));
    }

    private static final class TestStorage implements GenericCapabilityDevice {
        int mutations;

        @Override public boolean hasInventory() { return true; }

        @Override
        public List<ItemSlot> itemSlots(int limit) {
            return new ArrayList<>(List.of(new ItemSlot(0, "minecraft:iron_ingot", 12, 64)))
                    .subList(0, Math.min(limit, 1));
        }

        @Override
        public ItemPage queryItems(ItemQuery query) {
            List<ItemResource> items = List.of(
                    new ItemResource("minecraft:iron_ingot", 12),
                    new ItemResource("minecraft:stone", 64));
            int start = Math.min(query.offset(), items.size());
            int end = Math.min(start + query.limit(), items.size());
            return new ItemPage(items.subList(start, end), end < items.size() ? Integer.toString(end) : "");
        }

        @Override public long itemCount(String resourceId) {
            return "minecraft:iron_ingot".equals(resourceId) ? 12 : 0;
        }
        @Override public long simulateItemInsert(String resourceId, int count) { return count; }
        @Override public long simulateItemExtract(String resourceId, int count) { return Math.min(12, count); }
        @Override public TransferOutcome insertItems(String resourceId, int count) {
            mutations++;
            return new TransferOutcome(count, count, count);
        }
        @Override public TransferOutcome extractItems(String resourceId, int count) {
            mutations++;
            return new TransferOutcome(count, count, count);
        }
    }

    private static void assertOutcome(IntegratedStorageShellCommand.Outcome actual, int exitCode,
                                      List<String> lines, String message) {
        assertEquals(exitCode, actual.exitCode(), message + " exit");
        assertEquals(lines, actual.lines(), message + " output");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
