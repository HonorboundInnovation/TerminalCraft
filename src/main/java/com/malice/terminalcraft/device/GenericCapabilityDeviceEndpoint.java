package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Unified telemetry, simulation, and permission-gated mutation adapter for Forge capabilities. */
public final class GenericCapabilityDeviceEndpoint implements DeviceEndpoint {
    private static final DeviceParameterDescriptor LIMIT = new DeviceParameterDescriptor(
            "limit", DeviceValueType.NUMBER, false, "Maximum entries to return");
    private static final DeviceParameterDescriptor RESOURCE = new DeviceParameterDescriptor(
            "resource", DeviceValueType.STRING, true, "Namespaced item or fluid identifier");
    private static final DeviceParameterDescriptor COUNT = new DeviceParameterDescriptor(
            "count", DeviceValueType.NUMBER, true, "Positive transfer amount");
    private static final DeviceParameterDescriptor OPTIONAL_RESOURCE = new DeviceParameterDescriptor(
            "resource", DeviceValueType.STRING, false, "Exact namespaced item identifier or empty");
    private static final DeviceParameterDescriptor NAMESPACE = new DeviceParameterDescriptor(
            "namespace", DeviceValueType.STRING, false, "Item namespace filter or empty");
    private static final DeviceParameterDescriptor TEXT = new DeviceParameterDescriptor(
            "text", DeviceValueType.STRING, false, "Bounded item identifier text filter or empty");
    private static final DeviceParameterDescriptor CURSOR = new DeviceParameterDescriptor(
            "cursor", DeviceValueType.STRING, false, "Opaque cursor returned by the previous page or empty");
    private static final DeviceParameterDescriptor TAG = new DeviceParameterDescriptor(
            "tag", DeviceValueType.STRING, false, "Exact item tag identifier, optionally prefixed with #");

    private static final DeviceMethodDescriptor STORAGE_METADATA = readMethod(
            "storage.metadata", "Returns stable generic item-storage contract metadata",
            List.of(), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor ITEM_QUERY = readMethod(
            "storage.query", "Returns a bounded aggregate item-resource page",
            List.of(OPTIONAL_RESOURCE, NAMESPACE, TEXT, CURSOR, LIMIT, TAG), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor STORAGE_COUNT = readMethod(
            "storage.count", "Returns a lossless aggregate item count", List.of(RESOURCE), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor STORAGE_INSERT_SIMULATE = readMethod(
            "storage.insert.simulate", "Simulates a bounded generic storage insertion request",
            List.of(RESOURCE, COUNT), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor STORAGE_EXTRACT_SIMULATE = readMethod(
            "storage.extract.simulate", "Simulates a bounded generic storage extraction request",
            List.of(RESOURCE, COUNT), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor STORAGE_INSERT = writeMethod(
            "storage.insert", "Executes a bounded generic storage insertion request", List.of(RESOURCE, COUNT));
    private static final DeviceMethodDescriptor STORAGE_EXTRACT = writeMethod(
            "storage.extract", "Executes a bounded generic storage extraction request", List.of(RESOURCE, COUNT));
    private static final DeviceMethodDescriptor ITEM_SLOTS = readMethod(
            "inventory.slots", "Returns bounded non-empty inventory slots", List.of(LIMIT), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor ITEM_COUNT = readMethod(
            "inventory.count", "Counts an item across visible slots", List.of(RESOURCE), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor ITEM_INSERT_SIMULATE = readMethod(
            "inventory.insert.simulate", "Simulates item insertion without mutation", List.of(RESOURCE, COUNT), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor ITEM_EXTRACT_SIMULATE = readMethod(
            "inventory.extract.simulate", "Simulates item extraction without mutation", List.of(RESOURCE, COUNT), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor ITEM_INSERT = writeMethod(
            "inventory.insert", "Inserts items and reports the bounded partial outcome", List.of(RESOURCE, COUNT));
    private static final DeviceMethodDescriptor ITEM_EXTRACT = writeMethod(
            "inventory.extract", "Extracts items and reports the bounded partial outcome", List.of(RESOURCE, COUNT));
    private static final DeviceMethodDescriptor FLUID_TANKS = readMethod(
            "fluid.tanks", "Returns bounded fluid tank telemetry in millibuckets", List.of(LIMIT), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor FLUID_FILL_SIMULATE = readMethod(
            "fluid.fill.simulate", "Simulates filling fluid in millibuckets", List.of(RESOURCE, COUNT), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor FLUID_DRAIN_SIMULATE = readMethod(
            "fluid.drain.simulate", "Simulates draining fluid in millibuckets", List.of(RESOURCE, COUNT), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor FLUID_FILL = writeMethod(
            "fluid.fill", "Fills fluid and reports the bounded partial outcome", List.of(RESOURCE, COUNT));
    private static final DeviceMethodDescriptor FLUID_DRAIN = writeMethod(
            "fluid.drain", "Drains fluid and reports the bounded partial outcome", List.of(RESOURCE, COUNT));
    private static final DeviceMethodDescriptor ENERGY_STATUS = readMethod(
            "energy.status", "Returns Forge Energy telemetry in FE", List.of(), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor ENERGY_RECEIVE_SIMULATE = readMethod(
            "energy.receive.simulate", "Simulates receiving Forge Energy", List.of(COUNT), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor ENERGY_EXTRACT_SIMULATE = readMethod(
            "energy.extract.simulate", "Simulates extracting Forge Energy", List.of(COUNT), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor ENERGY_RECEIVE = writeMethod(
            "energy.receive", "Receives Forge Energy and reports the bounded partial outcome", List.of(COUNT));
    private static final DeviceMethodDescriptor ENERGY_EXTRACT = writeMethod(
            "energy.extract", "Extracts Forge Energy and reports the bounded partial outcome", List.of(COUNT));

    private final UUID deviceId;
    private final String adapterId;
    private final String displayName;
    private final String modSource;
    private final String address;
    private final GenericCapabilityDevice device;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;
    private final Set<String> additionalCapabilities;
    private final Map<String, DeviceValue> additionalProperties;
    private final String typeName;
    private final GenericItemStorage itemStorageOverride;

    public GenericCapabilityDeviceEndpoint(UUID deviceId, String adapterId, String displayName,
                                           String modSource, String address,
                                           GenericCapabilityDevice device,
                                           BooleanSupplier online, BooleanSupplier loaded) {
        this(deviceId, adapterId, "forge_capability", displayName, modSource, address, device,
                online, loaded, Set.of(), Map.of());
    }

    public GenericCapabilityDeviceEndpoint(UUID deviceId, String adapterId, String typeName,
                                           String displayName, String modSource, String address,
                                           GenericCapabilityDevice device,
                                           BooleanSupplier online, BooleanSupplier loaded,
                                           Set<String> additionalCapabilities,
                                           Map<String, DeviceValue> additionalProperties) {
        this(deviceId, adapterId, typeName, displayName, modSource, address, device, online, loaded,
                additionalCapabilities, additionalProperties, null);
    }

    public GenericCapabilityDeviceEndpoint(UUID deviceId, String adapterId, String typeName,
                                           String displayName, String modSource, String address,
                                           GenericCapabilityDevice device,
                                           BooleanSupplier online, BooleanSupplier loaded,
                                           Set<String> additionalCapabilities,
                                           Map<String, DeviceValue> additionalProperties,
                                           GenericItemStorage itemStorageOverride) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.typeName = Objects.requireNonNull(typeName, "typeName");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.modSource = Objects.requireNonNull(modSource, "modSource");
        this.address = Objects.requireNonNull(address, "address");
        this.device = Objects.requireNonNull(device, "device");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.additionalCapabilities = Set.copyOf(additionalCapabilities);
        this.additionalProperties = Map.copyOf(additionalProperties);
        this.itemStorageOverride = itemStorageOverride;
    }

    @Override
    public DeviceDescriptor descriptor() {
        Set<String> capabilities = capabilities();
        List<DeviceMethodDescriptor> methods = new ArrayList<>();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        if (capabilities.contains("inventory")) {
            methods.addAll(List.of(STORAGE_METADATA, ITEM_QUERY, STORAGE_COUNT,
                    STORAGE_INSERT_SIMULATE, STORAGE_EXTRACT_SIMULATE, STORAGE_INSERT, STORAGE_EXTRACT,
                    ITEM_SLOTS, ITEM_COUNT, ITEM_INSERT_SIMULATE, ITEM_EXTRACT_SIMULATE,
                    ITEM_INSERT, ITEM_EXTRACT));
            properties.put("inventory_slot_limit", DeviceValue.of(GenericCapabilityDevice.MAX_INVENTORY_SLOTS));
            properties.put("storage_page_limit", DeviceValue.of(GenericItemStorage.MAX_PAGE_SIZE));
            properties.put("storage_count_encoding", DeviceValue.of("decimal_string"));
            properties.put("storage_contract_version", DeviceValue.of(1));
            properties.put("storage_cursor_semantics", DeviceValue.of("sorted_offset_live_view"));
            properties.put("storage_filters", DeviceValue.list(List.of(
                    DeviceValue.of("resource"), DeviceValue.of("namespace"),
                    DeviceValue.of("text"), DeviceValue.of("tag"))));
            properties.put("storage_mutation_methods", DeviceValue.list(List.of(
                    DeviceValue.of("storage.insert"), DeviceValue.of("storage.extract"))));
        }
        if (capabilities.contains("fluid_storage")) {
            methods.addAll(List.of(FLUID_TANKS, FLUID_FILL_SIMULATE,
                    FLUID_DRAIN_SIMULATE, FLUID_FILL, FLUID_DRAIN));
            properties.put("fluid_unit", DeviceValue.of("mB"));
            properties.put("fluid_tank_limit", DeviceValue.of(GenericCapabilityDevice.MAX_FLUID_TANKS));
        }
        if (capabilities.contains("energy_storage")) {
            methods.addAll(List.of(ENERGY_STATUS, ENERGY_RECEIVE_SIMULATE,
                    ENERGY_EXTRACT_SIMULATE, ENERGY_RECEIVE, ENERGY_EXTRACT));
            properties.put("energy_unit", DeviceValue.of("FE"));
            properties.put("energy_mutation_semantics", DeviceValue.of("single_attempt_non_reversible"));
            properties.put("energy_retry_after_error", DeviceValue.of(false));
        }
        properties.putAll(additionalProperties);
        return new DeviceDescriptor(deviceId, adapterId, typeName, displayName,
                modSource, address, capabilities, properties, methods, Set.of(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE),
                online.getAsBoolean(), loaded.getAsBoolean());
    }

    @Override
    public DeviceResult call(String method, List<DeviceValue> arguments) {
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        try {
            return switch (method == null ? "" : method) {
                case "storage.metadata" -> DeviceResult.success(storageMetadata());
                case "storage.query" -> {
                    GenericItemStorage.ItemQuery query = itemQuery(args);
                    GenericItemStorage.ItemPage page = itemStorage().queryItems(query);
                    if (page.entries().size() > query.limit()) {
                        throw new IllegalStateException("adapter returned too many storage entries");
                    }
                    yield DeviceResult.success(itemPage(page));
                }
                case "storage.count" -> DeviceResult.success(DeviceValue.of(
                        Long.toString(itemStorage().countItem(resource(args.get(0))))));
                case "storage.insert.simulate" -> {
                    int requested = amount(args.get(1));
                    yield DeviceResult.success(storageSimulation(requested,
                            device.simulateItemInsert(resource(args.get(0)), requested)));
                }
                case "storage.extract.simulate" -> {
                    int requested = amount(args.get(1));
                    yield DeviceResult.success(storageSimulation(requested,
                            device.simulateItemExtract(resource(args.get(0)), requested)));
                }
                case "storage.insert" -> DeviceResult.success(storageTransfer(
                        device.insertItems(resource(args.get(0)), amount(args.get(1)))));
                case "storage.extract" -> DeviceResult.success(storageTransfer(
                        device.extractItems(resource(args.get(0)), amount(args.get(1)))));
                case "inventory.slots" -> DeviceResult.success(itemSlots(limit(args, GenericCapabilityDevice.MAX_INVENTORY_SLOTS)));
                case "inventory.count" -> DeviceResult.success(DeviceValue.of(device.itemCount(resource(args.get(0)))));
                case "inventory.insert.simulate" -> DeviceResult.success(DeviceValue.of(device.simulateItemInsert(resource(args.get(0)), amount(args.get(1)))));
                case "inventory.extract.simulate" -> DeviceResult.success(DeviceValue.of(device.simulateItemExtract(resource(args.get(0)), amount(args.get(1)))));
                case "inventory.insert" -> DeviceResult.success(transfer(device.insertItems(resource(args.get(0)), amount(args.get(1)))));
                case "inventory.extract" -> DeviceResult.success(transfer(device.extractItems(resource(args.get(0)), amount(args.get(1)))));
                case "fluid.tanks" -> DeviceResult.success(fluidTanks(limit(args, GenericCapabilityDevice.MAX_FLUID_TANKS)));
                case "fluid.fill.simulate" -> DeviceResult.success(DeviceValue.of(device.simulateFluidFill(resource(args.get(0)), amount(args.get(1)))));
                case "fluid.drain.simulate" -> DeviceResult.success(DeviceValue.of(device.simulateFluidDrain(resource(args.get(0)), amount(args.get(1)))));
                case "fluid.fill" -> DeviceResult.success(transfer(device.fillFluid(resource(args.get(0)), amount(args.get(1)))));
                case "fluid.drain" -> DeviceResult.success(transfer(device.drainFluid(resource(args.get(0)), amount(args.get(1)))));
                case "energy.status" -> DeviceResult.success(energyStatus(device.energyStatus()));
                case "energy.receive.simulate" -> DeviceResult.success(DeviceValue.of(device.simulateEnergyReceive(amount(args.get(0)))));
                case "energy.extract.simulate" -> DeviceResult.success(DeviceValue.of(device.simulateEnergyExtract(amount(args.get(0)))));
                case "energy.receive" -> DeviceResult.success(energyTransfer(
                        device.receiveEnergy(amount(args.get(0)))));
                case "energy.extract" -> DeviceResult.success(energyTransfer(
                        device.extractEnergy(amount(args.get(0)))));
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        } catch (IndeterminateEnergyMutationException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "Forge Energy mutation outcome is indeterminate; do not retry automatically", false);
        }
    }

    private GenericItemStorage itemStorage() {
        return itemStorageOverride == null ? device : itemStorageOverride;
    }

    private Set<String> capabilities() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (device.hasInventory()) result.add("inventory");
        if (device.hasFluidStorage()) result.add("fluid_storage");
        if (device.hasEnergyStorage()) result.add("energy_storage");
        result.addAll(additionalCapabilities);
        return Set.copyOf(result);
    }

    private static GenericItemStorage.ItemQuery itemQuery(List<DeviceValue> args) {
        if (args.size() > 6) throw new IllegalArgumentException("storage.query accepts at most 6 arguments");
        String resource = optionalString(args, 0);
        String namespace = optionalString(args, 1);
        String text = optionalString(args, 2);
        String cursor = optionalString(args, 3);
        int limit = args.size() < 5 ? GenericItemStorage.MAX_PAGE_SIZE
                : positiveInteger(args.get(4), GenericItemStorage.MAX_PAGE_SIZE, "limit");
        String tag = optionalString(args, 5);
        return new GenericItemStorage.ItemQuery(resource, namespace, text, cursor, limit, tag);
    }

    private static String optionalString(List<DeviceValue> args, int index) {
        if (index >= args.size()) return "";
        if (!(args.get(index) instanceof DeviceValue.StringValue string)) {
            throw new IllegalArgumentException("storage query filters and cursor must be strings");
        }
        return string.value();
    }

    private static DeviceValue itemPage(GenericItemStorage.ItemPage page) {
        List<DeviceValue> entries = new ArrayList<>();
        for (GenericItemStorage.ItemResource entry : page.entries()) {
            entries.add(DeviceValue.map(Map.of(
                    "resource", DeviceValue.of(entry.resourceId()),
                    "count", DeviceValue.of(Long.toString(entry.count())),
                    "tags", DeviceValue.list(entry.tags().stream().sorted()
                            .map(DeviceValue::of).toList()))));
        }
        return DeviceValue.map(Map.of(
                "entries", DeviceValue.list(entries),
                "next_cursor", DeviceValue.of(page.nextCursor()),
                "has_more", DeviceValue.of(page.hasMore())));
    }

    private static DeviceValue storageMetadata() {
        return DeviceValue.map(Map.ofEntries(
                Map.entry("contract", DeviceValue.of("terminalcraft:item_storage")),
                Map.entry("version", DeviceValue.of(1)),
                Map.entry("resource_kind", DeviceValue.of("item")),
                Map.entry("unit", DeviceValue.of("items")),
                Map.entry("aggregate", DeviceValue.of(true)),
                Map.entry("slot_independent", DeviceValue.of(true)),
                Map.entry("count_encoding", DeviceValue.of("decimal_string")),
                Map.entry("max_page_size", DeviceValue.of(GenericItemStorage.MAX_PAGE_SIZE)),
                Map.entry("max_transfer_amount", DeviceValue.of(GenericCapabilityDevice.MAX_TRANSFER_AMOUNT)),
                Map.entry("cursor_semantics", DeviceValue.of("sorted_offset_live_view")),
                Map.entry("filters", DeviceValue.list(List.of(DeviceValue.of("resource"),
                        DeviceValue.of("namespace"), DeviceValue.of("text"), DeviceValue.of("tag")))),
                Map.entry("mutation_result", DeviceValue.of("requested_simulated_executed"))));
    }

    private DeviceValue itemSlots(int limit) {
        List<DeviceValue> values = new ArrayList<>();
        List<GenericCapabilityDevice.ItemSlot> slots = device.itemSlots(limit);
        if (slots.size() > limit) throw new IllegalStateException("adapter returned too many item slots");
        for (GenericCapabilityDevice.ItemSlot slot : slots) values.add(DeviceValue.map(Map.of(
                "slot", DeviceValue.of(slot.slot()), "resource", DeviceValue.of(slot.resourceId()),
                "count", DeviceValue.of(slot.count()), "slot_limit", DeviceValue.of(slot.slotLimit()))));
        return DeviceValue.list(values);
    }

    private DeviceValue fluidTanks(int limit) {
        List<DeviceValue> values = new ArrayList<>();
        List<GenericCapabilityDevice.FluidTank> tanks = device.fluidTanks(limit);
        if (tanks.size() > limit) throw new IllegalStateException("adapter returned too many fluid tanks");
        for (GenericCapabilityDevice.FluidTank tank : tanks) values.add(DeviceValue.map(Map.of(
                "tank", DeviceValue.of(tank.tank()), "resource", DeviceValue.of(tank.resourceId()),
                "amount_mb", DeviceValue.of(tank.amountMb()), "capacity_mb", DeviceValue.of(tank.capacityMb()))));
        return DeviceValue.list(values);
    }

    private static DeviceValue energyStatus(GenericCapabilityDevice.EnergyStatus status) {
        return DeviceValue.map(Map.of(
                "stored_fe", DeviceValue.of(status.storedFe()), "capacity_fe", DeviceValue.of(status.capacityFe()),
                "can_receive", DeviceValue.of(status.canReceive()), "can_extract", DeviceValue.of(status.canExtract())));
    }

    private static DeviceValue energyTransfer(GenericCapabilityDevice.TransferOutcome outcome) {
        Objects.requireNonNull(outcome, "energy transfer outcome");
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        values.put("requested", DeviceValue.of(outcome.requested()));
        values.put("simulated", DeviceValue.of(outcome.simulated()));
        values.put("executed", DeviceValue.of(outcome.executed()));
        values.put("complete", DeviceValue.of(outcome.complete()));
        values.put("status", DeviceValue.of(outcome.complete() ? "complete" : "partial"));
        values.put("unit", DeviceValue.of("FE"));
        values.put("reversible", DeviceValue.of(false));
        values.put("retry_safe", DeviceValue.of(false));
        return DeviceValue.map(values);
    }

    private static DeviceValue storageSimulation(long requested, long accepted) {
        if (accepted < 0 || accepted > requested) {
            throw new IllegalArgumentException("invalid storage simulation outcome");
        }
        return DeviceValue.map(Map.of(
                "requested", DeviceValue.of(requested),
                "accepted", DeviceValue.of(accepted),
                "complete", DeviceValue.of(accepted == requested),
                "status", DeviceValue.of(accepted == requested ? "complete" : "partial"),
                "unit", DeviceValue.of("items"),
                "mutates", DeviceValue.of(false)));
    }

    private static DeviceValue storageTransfer(GenericCapabilityDevice.TransferOutcome outcome) {
        Objects.requireNonNull(outcome, "storage transfer outcome");
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        values.put("requested", DeviceValue.of(outcome.requested()));
        values.put("simulated", DeviceValue.of(outcome.simulated()));
        values.put("executed", DeviceValue.of(outcome.executed()));
        values.put("complete", DeviceValue.of(outcome.complete()));
        values.put("status", DeviceValue.of(outcome.complete() ? "complete" : "partial"));
        values.put("unit", DeviceValue.of("items"));
        return DeviceValue.map(values);
    }

    private static DeviceValue transfer(GenericCapabilityDevice.TransferOutcome outcome) {
        Objects.requireNonNull(outcome, "transfer outcome");
        return DeviceValue.map(Map.of(
                "requested", DeviceValue.of(outcome.requested()),
                "simulated", DeviceValue.of(outcome.simulated()),
                "executed", DeviceValue.of(outcome.executed()),
                "complete", DeviceValue.of(outcome.complete())));
    }

    private static int limit(List<DeviceValue> args, int maximum) {
        return args.isEmpty() ? maximum : positiveInteger(args.get(0), maximum, "limit");
    }

    private static int amount(DeviceValue value) {
        return positiveInteger(value, GenericCapabilityDevice.MAX_TRANSFER_AMOUNT, "amount");
    }

    private static int positiveInteger(DeviceValue value, int maximum, String label) {
        double raw = ((DeviceValue.NumberValue) value).value();
        if (raw != Math.rint(raw) || raw < 1 || raw > maximum)
            throw new IllegalArgumentException(label + " must be an integer from 1 to " + maximum);
        return (int) raw;
    }

    private static String resource(DeviceValue value) {
        String resource = ((DeviceValue.StringValue) value).value();
        if (!resource.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
            throw new IllegalArgumentException("resource must be a namespaced identifier");
        return resource;
    }

    private static DeviceMethodDescriptor readMethod(String name, String description,
                                                      List<DeviceParameterDescriptor> parameters,
                                                      DeviceValueType returnType) {
        return new DeviceMethodDescriptor(name, description, parameters, returnType, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor writeMethod(String name, String description,
                                                       List<DeviceParameterDescriptor> parameters) {
        return new DeviceMethodDescriptor(name, description, parameters, DeviceValueType.MAP, DeviceCallContext.WRITE);
    }
}
