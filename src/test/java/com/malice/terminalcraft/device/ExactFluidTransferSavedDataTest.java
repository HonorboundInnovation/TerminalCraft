package com.malice.terminalcraft.device;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;

import java.util.Set;
import java.util.UUID;

/** Headless round-trip and corruption tests for durable exact-fluid transfer state. */
public final class ExactFluidTransferSavedDataTest {
    private static final UUID PRINCIPAL = new UUID(0, 701);
    private static final UUID OPERATION = new UUID(0, 702);
    private static final UUID SOURCE = new UUID(0, 703);
    private static final UUID DESTINATION = new UUID(0, 704);

    private ExactFluidTransferSavedDataTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        roundTripPreservesReplayAndExactEscrow();
        malformedAndOversizedEntriesAreSkippedIndividually();
        oversizedCollectionsLoadToDeterministicBounds();
        legacyEscrowReceivesDeterministicId();
        recoveredEscrowIsPersistentlyRemoved();
        System.out.println("Exact fluid transfer saved-data tests: OK");
    }

    private static void roundTripPreservesReplayAndExactEscrow() {
        ExactFluidTransferSavedData data = new ExactFluidTransferSavedData();
        FluidTank sourceTank = rejectingRollbackTank(taggedWater(500, "mineral"));
        FluidTank destinationTank = new FluidTank(0);
        DeviceCallContext writer = new DeviceCallContext(PRINCIPAL, "writer",
                Set.of(DeviceCallContext.WRITE));
        var first = data.coordinator().transfer(writer, OPERATION, SOURCE,
                new ForgeFluidStackTransferPort(() -> sourceTank), DESTINATION,
                new ForgeFluidStackTransferPort(() -> destinationTank), "minecraft:water", 500);
        assertEquals(ExactFluidTransferCoordinator.Status.ESCROWED, first.status(), "escrow status");
        assertTrue(data.isDirty(), "mutation marks SavedData dirty");

        ExactFluidTransferSavedData restored = ExactFluidTransferSavedData.load(data.save(new CompoundTag()).copy());
        var entry = restored.coordinator().escrowEntries().values().iterator().next();
        assertEquals(500, entry.payload().getAmount(), "fluid amount survives");
        assertEquals("mineral", entry.payload().getTag().getString("terminalcraft_test"), "tag survives");
        var replay = restored.coordinator().transfer(writer, OPERATION, SOURCE,
                new ForgeFluidStackTransferPort(() -> new FluidTank(500)), DESTINATION,
                new ForgeFluidStackTransferPort(() -> new FluidTank(500)), "minecraft:water", 500);
        assertTrue(replay.replayed(), "restored replay is authoritative");
        assertEquals(first.status(), replay.status(), "replay status survives");
    }

    private static void malformedAndOversizedEntriesAreSkippedIndividually() {
        CompoundTag root = new CompoundTag();
        ListTag replays = new ListTag();
        replays.add(new CompoundTag());
        root.put("Replays", replays);
        ListTag escrow = new ListTag();
        escrow.add(new CompoundTag());
        CompoundTag oversized = escrowEntry(taggedWater(
                ExactFluidTransferCoordinator.MAX_TRANSFER_AMOUNT + 1, "oversized"), false);
        escrow.add(oversized);
        root.put("Escrow", escrow);
        ExactFluidTransferSavedData restored = ExactFluidTransferSavedData.load(root);
        assertEquals(0, restored.coordinator().snapshot().replays().size(), "malformed replay skipped");
        assertEquals(0, restored.coordinator().escrowEntries().size(), "bad escrow skipped");
    }

    private static void oversizedCollectionsLoadToDeterministicBounds() {
        CompoundTag root = new CompoundTag();
        ListTag replays = new ListTag();
        ListTag escrow = new ListTag();
        int serialized = ExactFluidTransferCoordinator.MAX_REPLAY_RECORDS + 5;
        for (int index = 0; index < serialized; index++) {
            CompoundTag replay = new CompoundTag();
            replay.putUUID("Principal", new UUID(0, 10_000L + index));
            replay.putUUID("Operation", new UUID(0, 20_000L + index));
            replay.putUUID("Source", SOURCE);
            replay.putUUID("Destination", DESTINATION);
            replay.putString("Resource", "minecraft:water");
            replay.putInt("Amount", 1);
            replay.putString("Status", ExactFluidTransferCoordinator.Status.PARTIAL.name());
            replay.putInt("Extracted", 0);
            replay.putInt("Inserted", 0);
            replay.putInt("RolledBack", 0);
            replay.putInt("Escrowed", 0);
            replays.add(replay);

            CompoundTag custody = escrowEntry(taggedWater(1, "bounded-" + index), true);
            custody.putUUID("EscrowId", new UUID(0, 30_000L + index));
            escrow.add(custody);
        }
        root.put("Replays", replays);
        root.put("Escrow", escrow);
        ExactFluidTransferCoordinator.Snapshot<FluidStack> snapshot =
                ExactFluidTransferSavedData.load(root).coordinator().snapshot();
        assertEquals(ExactFluidTransferCoordinator.MAX_REPLAY_RECORDS, snapshot.replays().size(),
                "oversized replay list is bounded");
        assertEquals(new UUID(0, 20_000L), snapshot.replays().get(0).operationId(),
                "bounded replay loading retains deterministic serialized order");
        assertEquals(ExactFluidTransferCoordinator.MAX_ESCROW_ENTRIES, snapshot.escrow().size(),
                "oversized escrow list is bounded");
        assertEquals(new UUID(0, 30_000L), snapshot.escrow().get(0).escrowId(),
                "bounded escrow loading retains deterministic serialized order");
    }

    private static void legacyEscrowReceivesDeterministicId() {
        CompoundTag root = new CompoundTag();
        ListTag escrow = new ListTag();
        escrow.add(escrowEntry(taggedWater(100, "legacy"), false));
        root.put("Escrow", escrow);
        UUID first = ExactFluidTransferSavedData.load(root.copy()).coordinator()
                .escrowEntries().keySet().iterator().next();
        UUID second = ExactFluidTransferSavedData.load(root.copy()).coordinator()
                .escrowEntries().keySet().iterator().next();
        assertEquals(first, second, "legacy ID is deterministic");
    }

    private static void recoveredEscrowIsPersistentlyRemoved() {
        ExactFluidTransferSavedData data = ExactFluidTransferSavedData.load(rootWithEscrow(
                taggedWater(200, "recover")));
        UUID id = data.coordinator().escrowEntries().keySet().iterator().next();
        DeviceCallContext admin = new DeviceCallContext(PRINCIPAL, "admin",
                Set.of(DeviceCallContext.ESCROW_ADMIN));
        FluidTank destination = new FluidTank(200);
        var result = data.coordinator().recoverEscrow(admin, id,
                new ForgeFluidStackTransferPort(() -> destination));
        assertEquals(ExactFluidTransferCoordinator.EscrowRecoveryStatus.COMPLETE,
                result.status(), "recovery completes");
        assertEquals(0, ExactFluidTransferSavedData.load(data.save(new CompoundTag())).coordinator()
                .escrowEntries().size(), "removed escrow stays removed after save");
    }

    private static CompoundTag rootWithEscrow(FluidStack fluid) {
        CompoundTag root = new CompoundTag();
        ListTag escrow = new ListTag();
        escrow.add(escrowEntry(fluid, true));
        root.put("Escrow", escrow);
        return root;
    }

    private static CompoundTag escrowEntry(FluidStack fluid, boolean includeId) {
        CompoundTag entry = new CompoundTag();
        if (includeId) entry.putUUID("EscrowId", new UUID(0, 705));
        entry.putUUID("Operation", OPERATION);
        entry.putUUID("Source", SOURCE);
        entry.putUUID("Destination", DESTINATION);
        entry.put("Fluid", fluid.writeToNBT(new CompoundTag()));
        return entry;
    }

    private static FluidTank rejectingRollbackTank(FluidStack initial) {
        return new FluidTank(initial.getAmount()) {
            { setFluid(initial.copy()); }
            @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        };
    }

    private static FluidStack taggedWater(int amount, String value) {
        FluidStack fluid = new FluidStack(Fluids.WATER, amount);
        fluid.getOrCreateTag().putString("terminalcraft_test", value);
        return fluid;
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
