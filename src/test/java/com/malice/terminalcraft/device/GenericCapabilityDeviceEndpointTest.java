package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless contract tests for generic item, fluid, and energy capability adapters. */
public final class GenericCapabilityDeviceEndpointTest {
    private GenericCapabilityDeviceEndpointTest() {}

    public static void main(String[] args) {
        TestDevice device = new TestDevice();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000007");
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new GenericCapabilityDeviceEndpoint(id, "terminalcraft:forge_capability",
                "Test Machine", "testmod", "minecraft:overworld:4,64,2@west", device,
                () -> true, () -> true));

        DeviceDescriptor descriptor = registry.descriptor(id).orElseThrow();
        assertEquals(Set.of("inventory", "fluid_storage", "energy_storage"), descriptor.capabilities(),
                "generic capabilities");
        assertEquals("mB", stringProperty(descriptor, "fluid_unit"), "fluid units");
        assertEquals("FE", stringProperty(descriptor, "energy_unit"), "energy units");
        assertEquals("single_attempt_non_reversible",
                stringProperty(descriptor, "energy_mutation_semantics"),
                "energy mutation semantics are explicit");
        assertEquals(false, booleanProperty(descriptor, "energy_retry_after_error"),
                "failed FE execution must not be retried blindly");
        assertTrue(hasMethod(descriptor, "storage.query"), "aggregate storage query advertised");
        assertTrue(hasMethod(descriptor, "storage.metadata"), "storage metadata advertised");
        assertTrue(hasMethod(descriptor, "storage.insert"), "generic insertion request advertised");
        assertEquals(DeviceCallContext.WRITE, method(descriptor, "storage.extract").requiredPermission(),
                "generic extraction request requires write permission");
        assertEquals("decimal_string", stringProperty(descriptor, "storage_count_encoding"),
                "wide storage counts use lossless encoding");
        assertTrue(hasMethod(descriptor, "inventory.insert.simulate"), "item simulation advertised");
        assertEquals(DeviceCallContext.WRITE, method(descriptor, "inventory.insert").requiredPermission(),
                "real item insertion requires write permission");
        assertEquals(DeviceCallContext.READ, method(descriptor, "inventory.insert.simulate").requiredPermission(),
                "item simulation requires only read permission");

        DeviceValue.MapValue firstPage = map(registry.call(id, "storage.query", List.of(
                DeviceValue.of(""), DeviceValue.of("minecraft"), DeviceValue.of("ingot"),
                DeviceValue.of(""), DeviceValue.of(1))));
        DeviceValue.ListValue resources = (DeviceValue.ListValue) firstPage.values().get("entries");
        assertEquals(1, resources.values().size(), "storage query page bounded");
        DeviceValue.MapValue resource = (DeviceValue.MapValue) resources.values().get(0);
        assertEquals("minecraft:gold_ingot", string(resource, "resource"), "storage query sorted resource");
        assertEquals("7", string(resource, "count"), "storage count serialized losslessly");
        assertEquals("1", string(firstPage, "next_cursor"), "storage query cursor");
        DeviceValue.ListValue resourceTags = (DeviceValue.ListValue) resource.values().get("tags");
        assertEquals(List.of(DeviceValue.of("forge:ingots/gold")), resourceTags.values(),
                "storage resource exposes sorted tags");
        DeviceValue.MapValue metadata = map(registry.call(id, "storage.metadata", List.of()));
        assertEquals("terminalcraft:item_storage", string(metadata, "contract"), "storage contract identity");
        assertEquals("48", ((DeviceValue.StringValue) registry.call(id, "storage.count",
                List.of(DeviceValue.of("minecraft:iron_ingot"))).value().orElseThrow()).value(),
                "generic count uses lossless string");
        DeviceValue.MapValue secondPage = map(registry.call(id, "storage.query", List.of(
                DeviceValue.of(""), DeviceValue.of("minecraft"), DeviceValue.of("ingot"),
                DeviceValue.of("1"), DeviceValue.of(1))));
        DeviceValue.ListValue secondResources = (DeviceValue.ListValue) secondPage.values().get("entries");
        assertEquals("minecraft:iron_ingot", string((DeviceValue.MapValue) secondResources.values().get(0),
                "resource"), "storage cursor selects next resource");
        DeviceValue.MapValue tagPage = map(registry.call(id, "storage.query", List.of(
                DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""),
                DeviceValue.of(8), DeviceValue.of("#forge:ingots/iron"))));
        DeviceValue.ListValue taggedResources = (DeviceValue.ListValue) tagPage.values().get("entries");
        assertEquals(1, taggedResources.values().size(), "tag filter returns matching resource");
        assertEquals("minecraft:iron_ingot", string((DeviceValue.MapValue) taggedResources.values().get(0),
                "resource"), "tag-filtered resource");

        DeviceValue.ListValue slots = list(registry.call(id, "inventory.slots", List.of(DeviceValue.of(1))));
        assertEquals(1, slots.values().size(), "slot result bounded");
        assertEquals(48.0, number(registry.call(id, "inventory.count", List.of(DeviceValue.of("minecraft:iron_ingot")))), "item count");
        assertEquals(12.0, number(registry.call(id, "inventory.insert.simulate",
                List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(12)))), "simulated insert");
        assertEquals(12.0, number(registry.call(id, "inventory.extract.simulate",
                List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(12)))), "simulated extract");
        assertEquals(0, device.mutations, "simulations do not mutate");

        DeviceValue.ListValue tanks = list(registry.call(id, "fluid.tanks", List.of()));
        assertEquals(2, tanks.values().size(), "fluid tanks");
        assertEquals(250.0, number(registry.call(id, "fluid.fill.simulate",
                List.of(DeviceValue.of("minecraft:water"), DeviceValue.of(250)))), "simulated fill");
        assertEquals(100.0, number(registry.call(id, "fluid.drain.simulate",
                List.of(DeviceValue.of("minecraft:water"), DeviceValue.of(100)))), "simulated drain");

        DeviceValue.MapValue energy = map(registry.call(id, "energy.status", List.of()));
        assertEquals(4000.0, number(energy, "stored_fe"), "stored FE");
        assertEquals(500.0, number(registry.call(id, "energy.receive.simulate", List.of(DeviceValue.of(500)))), "energy receive simulation");
        assertEquals(300.0, number(registry.call(id, "energy.extract.simulate", List.of(DeviceValue.of(300)))), "energy extract simulation");

        List<DeviceValue> itemArguments = List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(12));
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                registry.access(DeviceCallContext.readOnly("reader")).call(id, "inventory.insert", itemArguments),
                "read-only item mutation denied");
        assertEquals(0, device.mutations, "denied mutation never reaches adapter");
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                registry.access(DeviceCallContext.readOnly("energy-reader")).call(id, "energy.receive",
                        List.of(DeviceValue.of(500))),
                "read-only energy receive denied");
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                registry.access(DeviceCallContext.readOnly("energy-reader")).call(id, "energy.extract",
                        List.of(DeviceValue.of(300))),
                "read-only energy extract denied");
        assertEquals(0, device.mutations, "denied energy mutation never reaches adapter");

        DeviceCallContext writerContext = new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000109"), "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        DeviceAccess writer = registry.access(writerContext);
        DeviceValue.MapValue storageSimulation = map(writer.call(id, "storage.insert.simulate", itemArguments));
        assertEquals(12.0, number(storageSimulation, "accepted"), "generic storage simulation accepted");
        assertEquals("items", string(storageSimulation, "unit"), "generic storage simulation unit");
        DeviceValue.MapValue storageInsert = map(writer.call(id, "storage.insert", itemArguments));
        assertEquals(9.0, number(storageInsert, "executed"), "generic storage insertion executed");
        assertEquals("partial", string(storageInsert, "status"), "generic storage insertion status");
        assertEquals("items", string(storageInsert, "unit"), "generic storage insertion unit");
        assertTransfer(writer.call(id, "inventory.insert", itemArguments), 12, 12, 9,
                false, "partial item insertion");
        assertTransfer(writer.call(id, "inventory.extract", itemArguments), 12, 12, 12,
                true, "complete item extraction");
        assertTransfer(writer.call(id, "fluid.fill", List.of(DeviceValue.of("minecraft:water"), DeviceValue.of(250))),
                250, 250, 200, false, "partial fluid fill");
        assertTransfer(writer.call(id, "fluid.drain", List.of(DeviceValue.of("minecraft:water"), DeviceValue.of(100))),
                100, 100, 100, true, "complete fluid drain");
        assertEnergyTransfer(writer.call(id, "energy.receive", List.of(DeviceValue.of(500))),
                500, 500, 400, false, "partial energy receive");
        assertEnergyTransfer(writer.call(id, "energy.extract", List.of(DeviceValue.of(300))),
                300, 300, 300, true, "complete energy extract");
        assertEquals(7, device.mutations, "authorized mutations reach adapter exactly once");

        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                registry.call(id, "inventory.count", List.of(DeviceValue.of("not-an-id"))), "resource validation");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                registry.call(id, "fluid.tanks", List.of(DeviceValue.of(65))), "tank limit");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writer.call(id, "energy.receive", List.of(DeviceValue.of(0))), "positive mutation amount");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writer.call(id, "energy.receive", List.of(DeviceValue.of(-1))), "negative energy amount");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writer.call(id, "energy.receive", List.of(DeviceValue.of(1.5))), "fractional energy amount");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writer.call(id, "energy.receive", List.of(DeviceValue.of(
                        GenericCapabilityDevice.MAX_TRANSFER_AMOUNT + 1))), "oversized energy amount");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writer.call(id, "energy.receive", List.of()), "missing energy amount");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writer.call(id, "energy.receive", List.of(DeviceValue.of(1), DeviceValue.of(1))),
                "extra energy argument");
        assertEquals(7, device.mutations, "invalid mutation rejected before adapter");
        device.indeterminateEnergyFailure = true;
        DeviceResult indeterminate = writer.call(id, "energy.receive", List.of(DeviceValue.of(1)));
        assertError(DeviceErrorCode.ADAPTER_ERROR, indeterminate,
                "indeterminate FE execution is a structured adapter error");
        assertEquals(false, indeterminate.error().orElseThrow().retryable(),
                "non-reversible FE failure is not retryable");
        device.indeterminateEnergyFailure = false;
        device.overReturnQuery = true;
        assertError(DeviceErrorCode.ADAPTER_ERROR,
                registry.call(id, "storage.query", List.of(
                        DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""),
                        DeviceValue.of(""), DeviceValue.of(1))),
                "adapter cannot exceed requested storage page budget");
        device.overReturnQuery = false;
        assertThrows(() -> new GenericCapabilityDevice.TransferOutcome(10, 11, 0),
                "transfer simulation cannot exceed request");
        assertThrows(() -> new GenericCapabilityDevice.TransferOutcome(10, 10, 11),
                "transfer execution cannot exceed request");
        assertThrows(() -> new GenericCapabilityDevice.TransferOutcome(10, 5, 6),
                "transfer execution cannot exceed its preflight offer");

        long logicalWideCount = 9_007_199_254_740_993L;
        UUID logicalId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        GenericItemStorage logicalStorage = query -> new GenericItemStorage.ItemPage(List.of(
                new GenericItemStorage.ItemResource("minecraft:iron_ingot", logicalWideCount)), "");
        registry.register(new GenericCapabilityDeviceEndpoint(logicalId, "test:logical", "logical_storage",
                "Logical Storage", "test", "test:logical", new TestDevice(), () -> true, () -> true,
                Set.of(), java.util.Map.of(), logicalStorage));
        DeviceResult logicalCount = registry.call(logicalId, "storage.count",
                List.of(DeviceValue.of("minecraft:iron_ingot")));
        assertEquals(Long.toString(logicalWideCount),
                ((DeviceValue.StringValue) logicalCount.value().orElseThrow()).value(),
                "storage count follows logical query override without precision loss");

        TestDevice inventoryOnly = new TestDevice();
        inventoryOnly.fluid = false;
        inventoryOnly.energy = false;
        UUID inventoryId = UUID.fromString("00000000-0000-0000-0000-000000000008");
        registry.register(new GenericCapabilityDeviceEndpoint(inventoryId, "testmod:specialized",
                "specialized_storage", "Inventory", "testmod", "test:inventory", inventoryOnly,
                () -> true, () -> true, Set.of("specialized_storage"),
                java.util.Map.of("specialized_slots", DeviceValue.of(27))));
        DeviceDescriptor inventoryDescriptor = registry.descriptor(inventoryId).orElseThrow();
        assertEquals("testmod:specialized", inventoryDescriptor.adapterId(), "specialized adapter retained");
        assertEquals("specialized_storage", inventoryDescriptor.typeName(), "specialized type retained");
        assertEquals(Set.of("inventory", "specialized_storage"), inventoryDescriptor.capabilities(),
                "generic and specialized capabilities merged");
        assertEquals(27.0, ((DeviceValue.NumberValue) inventoryDescriptor.properties()
                .get("specialized_slots")).value(), "specialized properties merged");
        assertTrue(!hasMethod(inventoryDescriptor, "fluid.tanks"), "absent capability read method omitted");
        assertTrue(!hasMethod(inventoryDescriptor, "fluid.fill"), "absent capability write method omitted");

        System.out.println("Generic capability device endpoint tests: OK");
    }

    private static final class TestDevice implements GenericCapabilityDevice {
        boolean fluid = true;
        boolean energy = true;
        int mutations;
        boolean overReturnQuery;
        boolean indeterminateEnergyFailure;
        @Override public boolean hasInventory() { return true; }
        @Override public List<ItemSlot> itemSlots(int limit) {
            return new ArrayList<>(List.of(
                    new ItemSlot(0, "minecraft:iron_ingot", 32, 64),
                    new ItemSlot(2, "minecraft:iron_ingot", 16, 64))).subList(0, Math.min(limit, 2));
        }
        @Override public ItemPage queryItems(ItemQuery query) {
            List<ItemResource> all = List.of(
                    new ItemResource("minecraft:gold_ingot", 7, Set.of("forge:ingots/gold")),
                    new ItemResource("minecraft:iron_ingot", 48, Set.of("forge:ingots/iron")),
                    new ItemResource("test:copper_ingot", 5, Set.of("forge:ingots/copper")));
            List<ItemResource> filtered = all.stream()
                    .filter(entry -> query.matches(entry.resourceId(), entry.tags())).toList();
            int offset = Math.min(query.offset(), filtered.size());
            int end = Math.min(offset + (overReturnQuery ? GenericItemStorage.MAX_PAGE_SIZE : query.limit()),
                    filtered.size());
            return new ItemPage(filtered.subList(offset, end), end < filtered.size() ? Integer.toString(end) : "");
        }
        @Override public long itemCount(String resourceId) { return "minecraft:iron_ingot".equals(resourceId) ? 48 : 0; }
        @Override public long simulateItemInsert(String resourceId, int count) { return count; }
        @Override public long simulateItemExtract(String resourceId, int count) { return Math.min(count, 48); }
        @Override public TransferOutcome insertItems(String resourceId, int count) { mutations++; return new TransferOutcome(count, count, Math.min(count, 9)); }
        @Override public TransferOutcome extractItems(String resourceId, int count) { mutations++; return new TransferOutcome(count, count, count); }
        @Override public boolean hasFluidStorage() { return fluid; }
        @Override public List<FluidTank> fluidTanks(int limit) { return List.of(
                new FluidTank(0, "minecraft:water", 500, 1000),
                new FluidTank(1, "minecraft:empty", 0, 1000)); }
        @Override public long simulateFluidFill(String resourceId, int amountMb) { return amountMb; }
        @Override public long simulateFluidDrain(String resourceId, int amountMb) { return Math.min(amountMb, 500); }
        @Override public TransferOutcome fillFluid(String resourceId, int amountMb) { mutations++; return new TransferOutcome(amountMb, amountMb, Math.min(amountMb, 200)); }
        @Override public TransferOutcome drainFluid(String resourceId, int amountMb) { mutations++; return new TransferOutcome(amountMb, amountMb, amountMb); }
        @Override public boolean hasEnergyStorage() { return energy; }
        @Override public EnergyStatus energyStatus() { return new EnergyStatus(4000, 10000, true, true); }
        @Override public long simulateEnergyReceive(int amountFe) { return amountFe; }
        @Override public long simulateEnergyExtract(int amountFe) { return Math.min(amountFe, 4000); }
        @Override public TransferOutcome receiveEnergy(int amountFe) {
            mutations++;
            if (indeterminateEnergyFailure) throw new IndeterminateEnergyMutationException(
                    "test indeterminate mutation", new IllegalStateException("handler failed"));
            return new TransferOutcome(amountFe, amountFe, Math.min(amountFe, 400));
        }
        @Override public TransferOutcome extractEnergy(int amountFe) { mutations++; return new TransferOutcome(amountFe, amountFe, amountFe); }
    }

    private static boolean hasMethod(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().anyMatch(candidate -> candidate.name().equals(name));
    }
    private static DeviceMethodDescriptor method(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }
    private static String stringProperty(DeviceDescriptor descriptor, String name) {
        return ((DeviceValue.StringValue) descriptor.properties().get(name)).value();
    }
    private static boolean booleanProperty(DeviceDescriptor descriptor, String name) {
        return ((DeviceValue.BooleanValue) descriptor.properties().get(name)).value();
    }
    private static DeviceValue.ListValue list(DeviceResult result) {
        assertTrue(result.isSuccess(), "list call succeeds: " + result.error());
        return (DeviceValue.ListValue) result.value().orElseThrow();
    }
    private static DeviceValue.MapValue map(DeviceResult result) {
        assertTrue(result.isSuccess(), "map call succeeds: " + result.error());
        return (DeviceValue.MapValue) result.value().orElseThrow();
    }
    private static double number(DeviceResult result) {
        assertTrue(result.isSuccess(), "number call succeeds: " + result.error());
        return ((DeviceValue.NumberValue) result.value().orElseThrow()).value();
    }
    private static double number(DeviceValue.MapValue map, String key) {
        return ((DeviceValue.NumberValue) map.values().get(key)).value();
    }
    private static String string(DeviceValue.MapValue map, String key) {
        return ((DeviceValue.StringValue) map.values().get(key)).value();
    }
    private static void assertTransfer(DeviceResult result, long requested, long simulated,
                                       long executed, boolean complete, String message) {
        DeviceValue.MapValue value = map(result);
        assertEquals((double) requested, number(value, "requested"), message + " requested");
        assertEquals((double) simulated, number(value, "simulated"), message + " simulated");
        assertEquals((double) executed, number(value, "executed"), message + " executed");
        assertEquals(complete, ((DeviceValue.BooleanValue) value.values().get("complete")).value(),
                message + " complete");
    }
    private static void assertEnergyTransfer(DeviceResult result, long requested, long simulated,
                                             long executed, boolean complete, String message) {
        assertTransfer(result, requested, simulated, executed, complete, message);
        DeviceValue.MapValue value = map(result);
        assertEquals(complete ? "complete" : "partial", string(value, "status"),
                message + " status");
        assertEquals("FE", string(value, "unit"), message + " unit");
        assertEquals(false, ((DeviceValue.BooleanValue) value.values().get("reversible")).value(),
                message + " reversibility");
        assertEquals(false, ((DeviceValue.BooleanValue) value.values().get("retry_safe")).value(),
                message + " retry safety");
    }
    private static void assertError(DeviceErrorCode code, DeviceResult result, String message) {
        if (result.isSuccess() || result.error().orElseThrow().code() != code)
            throw new AssertionError(message + ": expected=" + code + ", actual=" + result.error());
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void assertThrows(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message + ": expected exception");
    }
}
