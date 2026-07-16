package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless contract tests for human and machine-readable generic storage commands. */
public final class StorageShellCommandTest {
    private StorageShellCommandTest() {}

    public static void main(String[] args) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000251");
        TestStorage storage = new TestStorage();
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new GenericCapabilityDeviceEndpoint(id, "test:storage", "test_storage",
                "Test Storage", "test", "test:storage@north", storage, () -> true, () -> true,
                Set.of(), Map.of()));

        DeviceAccess reader = registry.access(DeviceCallContext.readOnly("reader"));
        assertOutcome(run(reader, "list"), 0,
                List.of("0  " + id + "  online  test:storage@north  Test Storage"), "human list");
        assertTrue(run(reader, "list", "--json").lines().get(0).contains("\"id\": \"" + id + "\""),
                "machine list is structured");
        assertTrue(run(reader, "info", "north").lines().stream()
                .anyMatch(line -> line.startsWith("metadata: ") && line.contains("terminalcraft:item_storage")),
                "human info includes contract metadata");

        assertOutcome(run(reader, "query", "0", "--tag", "#forge:ingots/iron", "--limit", "1"),
                0, List.of("12  minecraft:iron_ingot"), "human filtered query");
        String queryJson = run(reader, "query", "0", "--namespace", "minecraft", "--json")
                .lines().get(0);
        assertTrue(queryJson.contains("\"count\": \"12\"")
                && queryJson.contains("\"tags\": [\"forge:ingots/iron\"]"),
                "machine query preserves lossless count and tags");
        assertOutcome(run(reader, "count", "north", "minecraft:iron_ingot"), 0,
                List.of("12 items  minecraft:iron_ingot"), "human count");
        assertTrue(run(reader, "count", "0", "minecraft:iron_ingot", "--json").lines().get(0)
                .equals("{\"count\": \"12\", \"resource\": \"minecraft:iron_ingot\", \"unit\": \"items\"}"),
                "machine count schema");

        assertOutcome(run(reader, "simulate-extract", "0", "minecraft:iron_ingot", "5"), 0,
                List.of("simulate-extract: 5/5 items complete  minecraft:iron_ingot"),
                "human simulation");
        assertEquals(0, storage.mutations, "simulation does not mutate");
        assertOutcome(run(reader, "extract", "0", "minecraft:iron_ingot", "5"), 1,
                List.of("storage: permission_denied: permission required: device.write"),
                "read-only mutation denied");

        DeviceCallContext writerContext = new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000252"), "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        assertOutcome(run(registry.access(writerContext), "insert", "0", "minecraft:iron_ingot", "8"),
                0, List.of("insert: 6/8 items partial  minecraft:iron_ingot"),
                "human partial insertion");
        assertEquals(1, storage.mutations, "authorized request mutates once");

        assertOutcome(run(reader, "query", "0", "--limit", "0"), 1,
                List.of("storage: limit must be an integer from 1 to 64"), "invalid limit");
        assertOutcome(run(reader, "query", "0", "--unknown", "x"), 1,
                List.of("storage: unknown query option: --unknown"), "unknown option");
        assertOutcome(StorageShellCommand.execute(null, List.of("list")), 1,
                List.of("storage: device registry unavailable"), "missing access");

        System.out.println("Storage shell command tests: OK");
    }

    private static StorageShellCommand.Outcome run(DeviceAccess access, String... args) {
        return StorageShellCommand.execute(access, List.of(args));
    }

    private static final class TestStorage implements GenericCapabilityDevice {
        int mutations;
        @Override public boolean hasInventory() { return true; }
        @Override public ItemPage queryItems(ItemQuery query) {
            List<ItemResource> all = List.of(
                    new ItemResource("minecraft:iron_ingot", 12, Set.of("forge:ingots/iron")),
                    new ItemResource("test:copper_ingot", 7, Set.of("forge:ingots/copper")));
            List<ItemResource> filtered = all.stream()
                    .filter(item -> query.matches(item.resourceId(), item.tags())).toList();
            int start = Math.min(query.offset(), filtered.size());
            int end = Math.min(start + query.limit(), filtered.size());
            return new ItemPage(filtered.subList(start, end), end < filtered.size() ? Integer.toString(end) : "");
        }
        @Override public long itemCount(String resourceId) { return "minecraft:iron_ingot".equals(resourceId) ? 12 : 0; }
        @Override public long simulateItemInsert(String resourceId, int count) { return Math.min(count, 6); }
        @Override public long simulateItemExtract(String resourceId, int count) { return Math.min(count, 12); }
        @Override public TransferOutcome insertItems(String resourceId, int count) {
            mutations++;
            return new TransferOutcome(count, Math.min(count, 6), Math.min(count, 6));
        }
        @Override public TransferOutcome extractItems(String resourceId, int count) {
            mutations++;
            return new TransferOutcome(count, Math.min(count, 12), Math.min(count, 12));
        }
    }

    private static void assertOutcome(StorageShellCommand.Outcome actual, int exitCode,
                                      List<String> lines, String message) {
        assertEquals(exitCode, actual.exitCode(), message + " exit");
        assertEquals(lines, actual.lines(), message + " output");
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
