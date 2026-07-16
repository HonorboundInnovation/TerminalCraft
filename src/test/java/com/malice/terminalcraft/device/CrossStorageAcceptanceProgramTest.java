package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provider-neutral acceptance program for every logical item-storage adapter. */
public final class CrossStorageAcceptanceProgramTest {
    private static final UUID ENDPOINT_ID = UUID.fromString("00000000-0000-0000-0000-000000000380");
    private CrossStorageAcceptanceProgramTest() {}

    public static void main(String[] args) {
        AcceptanceStorage storage = new AcceptanceStorage();
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new GenericCapabilityDeviceEndpoint(ENDPOINT_ID, "acceptance:storage",
                "acceptance_storage", "Acceptance Storage", "acceptance", "acceptance:storage@north",
                storage, () -> true, () -> true, Set.of(), Map.of()));

        DeviceAccess reader = registry.access(DeviceCallContext.readOnly("acceptance-reader"));
        DeviceDescriptor descriptor = reader.descriptor(ENDPOINT_ID).orElseThrow();
        require(descriptor.capabilities().contains("inventory"), "inventory capability advertised");
        require(hasMethod(descriptor, "storage.metadata") && hasMethod(descriptor, "storage.query")
                && hasMethod(descriptor, "storage.count"), "logical storage methods advertised");
        require(permission(descriptor, "storage.insert").equals(DeviceCallContext.WRITE),
                "real mutation requires write permission");
        require(permission(descriptor, "storage.insert.simulate").equals(DeviceCallContext.READ),
                "simulation remains readable");

        DeviceValue.MapValue metadata = map(reader.call(ENDPOINT_ID, "storage.metadata", List.of()));
        require("terminalcraft:item_storage".equals(string(metadata, "contract")),
                "common contract identity retained");
        require("decimal_string".equals(string(metadata, "count_encoding")),
                "logical counts use lossless encoding");

        DeviceValue.MapValue first = query(reader, "", "", "", "", 1, "");
        require("minecraft:gold_ingot".equals(firstResource(first)), "first page is deterministically sorted");
        require("1".equals(string(first, "next_cursor")), "first page exposes stable cursor");
        DeviceValue.MapValue second = query(reader, "", "", "", "1", 1, "");
        require("minecraft:iron_ingot".equals(firstResource(second)), "cursor advances exactly once");

        require("minecraft:iron_ingot".equals(firstResource(query(reader,
                "minecraft:iron_ingot", "", "", "", 8, ""))), "resource filter");
        require("test:copper_ingot".equals(firstResource(query(reader,
                "", "test", "COPPER", "", 8, ""))), "namespace and case-insensitive text filters");
        require("minecraft:iron_ingot".equals(firstResource(query(reader,
                "", "", "", "", 8, "#forge:ingots/iron"))), "normalized tag filter");

        DeviceResult wide = reader.call(ENDPOINT_ID, "storage.count",
                List.of(DeviceValue.of("minecraft:iron_ingot")));
        require(wide.isSuccess() && Long.toString(AcceptanceStorage.WIDE_COUNT).equals(
                ((DeviceValue.StringValue) wide.value().orElseThrow()).value()),
                "count survives beyond double integer precision");

        List<DeviceValue> request = List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(8));
        DeviceValue.MapValue simulation = map(reader.call(ENDPOINT_ID, "storage.insert.simulate", request));
        require(number(simulation, "accepted") == 6 && storage.mutations == 0,
                "simulation reports capacity without mutation");
        require(error(reader.call(ENDPOINT_ID, "storage.insert", request)) == DeviceErrorCode.PERMISSION_DENIED
                && storage.mutations == 0, "read-only mutation fails before adapter invocation");

        DeviceAccess writer = registry.access(new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000381"), "acceptance-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
        DeviceValue.MapValue partial = map(writer.call(ENDPOINT_ID, "storage.insert", request));
        require(number(partial, "requested") == 8 && number(partial, "executed") == 6
                && "partial".equals(string(partial, "status")) && storage.mutations == 1,
                "authorized partial mutation executes exactly once");
        DeviceValue.MapValue complete = map(writer.call(ENDPOINT_ID, "storage.extract",
                List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(5))));
        require(number(complete, "executed") == 5 && "complete".equals(string(complete, "status"))
                && storage.mutations == 2, "authorized complete mutation executes exactly once");

        require(error(writer.call(ENDPOINT_ID, "storage.insert",
                List.of(DeviceValue.of("bad id"), DeviceValue.of(1)))) == DeviceErrorCode.INVALID_ARGUMENT,
                "malformed resource rejected");
        require(error(writer.call(ENDPOINT_ID, "storage.insert",
                List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(0))))
                == DeviceErrorCode.INVALID_ARGUMENT, "non-positive amount rejected");
        require(error(queryResult(reader, "", "", "", "", GenericItemStorage.MAX_PAGE_SIZE + 1, ""))
                == DeviceErrorCode.INVALID_ARGUMENT, "oversized page rejected");
        require(storage.mutations == 2, "invalid requests never reach adapter");

        StorageShellCommand.Outcome json = StorageShellCommand.execute(reader,
                List.of("query", ENDPOINT_ID.toString(), "--tag", "#forge:ingots/iron", "--json"));
        require(json.exitCode() == 0 && json.lines().get(0).contains("\"count\": \""
                + AcceptanceStorage.WIDE_COUNT + "\"")
                && json.lines().get(0).contains("forge:ingots/iron"),
                "shell JSON preserves the common machine-readable projection");
        StorageShellCommand.Outcome denied = StorageShellCommand.execute(reader,
                List.of("extract", ENDPOINT_ID.toString(), "minecraft:iron_ingot", "1"));
        require(denied.exitCode() == 1 && denied.lines().get(0).contains("permission_denied"),
                "shell mutation preserves permission denial");

        System.out.println("Cross-storage acceptance program tests: OK");
    }

    private static DeviceValue.MapValue query(DeviceAccess access, String resource, String namespace,
                                               String text, String cursor, int limit, String tag) {
        return map(queryResult(access, resource, namespace, text, cursor, limit, tag));
    }

    private static DeviceResult queryResult(DeviceAccess access, String resource, String namespace,
                                            String text, String cursor, int limit, String tag) {
        return access.call(ENDPOINT_ID, "storage.query", List.of(DeviceValue.of(resource),
                DeviceValue.of(namespace), DeviceValue.of(text), DeviceValue.of(cursor),
                DeviceValue.of(limit), DeviceValue.of(tag)));
    }

    private static final class AcceptanceStorage implements GenericCapabilityDevice {
        static final long WIDE_COUNT = 9_007_199_254_740_993L;
        int mutations;
        @Override public boolean hasInventory() { return true; }
        @Override public ItemPage queryItems(ItemQuery query) {
            List<ItemResource> filtered = List.of(
                    new ItemResource("minecraft:gold_ingot", 7, Set.of("forge:ingots/gold")),
                    new ItemResource("minecraft:iron_ingot", WIDE_COUNT, Set.of("forge:ingots/iron")),
                    new ItemResource("test:copper_ingot", 5, Set.of("forge:ingots/copper"))).stream()
                    .filter(item -> query.matches(item.resourceId(), item.tags())).toList();
            int start = Math.min(query.offset(), filtered.size());
            int end = Math.min(start + query.limit(), filtered.size());
            return new ItemPage(filtered.subList(start, end), end < filtered.size() ? Integer.toString(end) : "");
        }
        @Override public long itemCount(String resourceId) {
            return "minecraft:iron_ingot".equals(resourceId) ? WIDE_COUNT : 0;
        }
        @Override public long simulateItemInsert(String resourceId, int count) { return Math.min(count, 6); }
        @Override public long simulateItemExtract(String resourceId, int count) { return count; }
        @Override public TransferOutcome insertItems(String resourceId, int count) {
            mutations++;
            return new TransferOutcome(count, Math.min(count, 6), Math.min(count, 6));
        }
        @Override public TransferOutcome extractItems(String resourceId, int count) {
            mutations++;
            return new TransferOutcome(count, count, count);
        }
    }

    private static boolean hasMethod(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().anyMatch(method -> method.name().equals(name));
    }
    private static String permission(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().filter(method -> method.name().equals(name))
                .findFirst().orElseThrow().requiredPermission();
    }
    private static String firstResource(DeviceValue.MapValue page) {
        DeviceValue.ListValue entries = (DeviceValue.ListValue) page.values().get("entries");
        require(!entries.values().isEmpty(), "query returned an entry");
        return string((DeviceValue.MapValue) entries.values().get(0), "resource");
    }
    private static DeviceValue.MapValue map(DeviceResult result) {
        require(result.isSuccess(), "call succeeds: " + result.error());
        return (DeviceValue.MapValue) result.value().orElseThrow();
    }
    private static String string(DeviceValue.MapValue map, String key) {
        return ((DeviceValue.StringValue) map.values().get(key)).value();
    }
    private static long number(DeviceValue.MapValue map, String key) {
        return (long) ((DeviceValue.NumberValue) map.values().get(key)).value();
    }
    private static DeviceErrorCode error(DeviceResult result) {
        require(!result.isSuccess(), "call was expected to fail");
        return result.error().orElseThrow().code();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
