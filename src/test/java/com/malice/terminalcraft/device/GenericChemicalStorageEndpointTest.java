package com.malice.terminalcraft.device;

import com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless contract tests for provider-neutral, runtime-discovered chemical telemetry. */
public final class GenericChemicalStorageEndpointTest {
    private GenericChemicalStorageEndpointTest() {}

    public static void main(String[] args) {
        long exactAmount = 9_007_199_254_740_993L;
        OptionalChemicalStorageRegistry.ChemicalStorage storage = () -> List.of(
                new OptionalChemicalStorageRegistry.Tank(
                        "gas", 0, "mekanism:hydrogen", exactAmount, Long.MAX_VALUE, "mb"),
                new OptionalChemicalStorageRegistry.Tank(
                        "gas", 1, "addonchem:tritium_mix", 125, 1_000, "mb"),
                new OptionalChemicalStorageRegistry.Tank(
                        "slurry", 0, "anothermod:dirty_osmium", 40, 500, "mb"));
        UUID id = UUID.randomUUID();
        GenericChemicalStorageEndpoint endpoint = new GenericChemicalStorageEndpoint(
                id, "test:chemical_machine", "test", "test@0,0,0", storage, () -> true, () -> true);

        DeviceDescriptor descriptor = endpoint.descriptor();
        require(descriptor.capabilities().containsAll(Set.of(
                        "chemical_storage", "chemical_gas", "chemical_slurry")),
                "chemical families are derived from the live snapshot");
        require(((DeviceValue.BooleanValue) descriptor.properties().get("chemical_dynamic_resources")).value(),
                "descriptor advertises dynamic resource discovery");

        DeviceRegistry registry = new DeviceRegistry();
        registry.register(endpoint);
        DeviceCallContext caller = DeviceCallContext.player(UUID.randomUUID(), "chemist",
                Set.of(DeviceCallContext.READ));
        DeviceResult tanks = registry.call(caller, id, "chemical.tanks", List.of());
        require(tanks.isSuccess(), "dynamic tank listing succeeds");
        DeviceValue.ListValue tankList = (DeviceValue.ListValue) tanks.value().orElseThrow();
        require(tankList.values().size() == 3, "every provider tank is returned");
        require(tankList.values().toString().contains("addonchem:tritium_mix")
                        && tankList.values().toString().contains("anothermod:dirty_osmium"),
                "non-Mekanism namespaces are not filtered by a static allowlist");

        DeviceResult count = registry.call(caller, id, "chemical.count", List.of(
                DeviceValue.of("mekanism:hydrogen"), DeviceValue.of("gas")));
        require(count.isSuccess(), "exact dynamic chemical count succeeds");
        require(((DeviceValue.StringValue) count.value().orElseThrow()).value()
                        .equals(Long.toString(exactAmount)),
                "chemical count remains exact above the IEEE-754 integer limit");
        System.out.println("Generic chemical storage endpoint tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
