package com.malice.terminalcraft.device;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

import java.util.Set;
import java.util.UUID;

/** Headless round-trip tests for durable exact-item replay and escrow state. */
public final class ExactItemTransferSavedDataTest {
    private static final UUID PRINCIPAL = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000404");

    private ExactItemTransferSavedDataTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        roundTripPreservesReplayAndExactEscrow();
        malformedEntriesAreSkippedIndividually();
        legacyEscrowReceivesDeterministicId();
        oversizedCollectionsAreBoundedDuringLoad();
        System.out.println("Exact item transfer saved-data tests: OK");
    }

    private static void roundTripPreservesReplayAndExactEscrow() {
        ExactItemTransferSavedData data = new ExactItemTransferSavedData();
        ItemStack tagged = new ItemStack(Items.STONE, 5);
        tagged.getOrCreateTag().putString("terminalcraft_test", "durable_healing");
        ItemStackHandler replaySourceHandler = new ItemStackHandler(1);
        replaySourceHandler.setStackInSlot(0, tagged.copy());
        ItemStackHandler destinationHandler = new ItemStackHandler(0);
        ForgeItemStackTransferPort source = new ForgeItemStackTransferPort(() -> replaySourceHandler);
        ForgeItemStackTransferPort destination = new ForgeItemStackTransferPort(() -> destinationHandler);
        DeviceCallContext writer = new DeviceCallContext(PRINCIPAL, "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

        // With no source room after extraction and no destination slots, the exact stack enters escrow.
        ItemStackHandler sourceHandler = rejectingRollbackHandler(tagged);
        ForgeItemStackTransferPort rejectingSource = new ForgeItemStackTransferPort(() -> sourceHandler);
        ExactItemTransferCoordinator.TransferResult first = data.coordinator().transfer(writer, OPERATION,
                SOURCE, rejectingSource, DESTINATION, destination, "minecraft:stone", 5);
        assertEquals(ExactItemTransferCoordinator.Status.ESCROWED, first.status(), "escrow status");
        assertTrue(data.isDirty(), "state mutation marks SavedData dirty");

        CompoundTag serialized = data.save(new CompoundTag());
        ExactItemTransferSavedData restored = ExactItemTransferSavedData.load(serialized.copy());
        UUID escrowId = data.coordinator().escrowEntries().get(0).escrowId();
        ItemStack restoredStack = restored.coordinator().escrowEntries().get(0).payload();
        assertEquals(escrowId, restored.coordinator().escrowEntries().get(0).escrowId(),
                "stable escrow ID survives");
        assertEquals(5, restoredStack.getCount(), "escrow count survives");
        assertEquals("durable_healing", restoredStack.getTag().getString("terminalcraft_test"),
                "escrow tag survives");

        int destinationCount = destinationHandler.getSlots();
        ExactItemTransferCoordinator.TransferResult replay = restored.coordinator().transfer(writer, OPERATION,
                SOURCE, source, DESTINATION, destination, "minecraft:stone", 5);
        assertTrue(replay.replayed(), "restored replay record is authoritative");
        assertEquals(first.status(), replay.status(), "restored result status");
        assertEquals(destinationCount, destinationHandler.getSlots(), "replay does not mutate destination");
    }

    private static ItemStackHandler rejectingRollbackHandler(ItemStack initial) {
        return new ItemStackHandler(1) {
            { setStackInSlot(0, initial.copy()); }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return stack.copy();
            }
        };
    }

    private static void malformedEntriesAreSkippedIndividually() {
        CompoundTag root = new CompoundTag();
        ListTag replays = new ListTag();
        CompoundTag malformedReplay = new CompoundTag();
        malformedReplay.putString("Status", "NOT_A_STATUS");
        replays.add(malformedReplay);
        root.put("Replays", replays);
        ListTag escrow = new ListTag();
        escrow.add(new CompoundTag());
        root.put("Escrow", escrow);

        ExactItemTransferSavedData restored = ExactItemTransferSavedData.load(root);
        assertEquals(0, restored.coordinator().snapshot().replays().size(), "malformed replay skipped");
        assertEquals(0, restored.coordinator().escrowEntries().size(), "malformed escrow skipped");
    }


    private static void legacyEscrowReceivesDeterministicId() {
        ItemStack stack = new ItemStack(Items.STONE, 2);
        CompoundTag entry = new CompoundTag();
        entry.putUUID("Operation", OPERATION);
        entry.putUUID("Source", SOURCE);
        entry.putUUID("Destination", DESTINATION);
        entry.put("Stack", stack.save(new CompoundTag()));
        ListTag entries = new ListTag();
        entries.add(entry);
        CompoundTag root = new CompoundTag();
        root.put("Escrow", entries);
        UUID first = ExactItemTransferSavedData.load(root.copy()).coordinator()
                .escrowEntries().get(0).escrowId();
        UUID second = ExactItemTransferSavedData.load(root.copy()).coordinator()
                .escrowEntries().get(0).escrowId();
        assertEquals(first, second, "legacy migration ID is deterministic");
    }

    private static void oversizedCollectionsAreBoundedDuringLoad() {
        CompoundTag root = new CompoundTag();
        ListTag replays = new ListTag();
        ListTag escrow = new ListTag();
        int supplied = ExactItemTransferCoordinator.MAX_REPLAY_RECORDS + 1;
        for (int index = 0; index < supplied; index++) {
            CompoundTag replay = new CompoundTag();
            replay.putUUID("Principal", PRINCIPAL);
            replay.putUUID("Operation", new UUID(1L, index));
            replay.putUUID("Source", SOURCE);
            replay.putUUID("Destination", DESTINATION);
            replay.putString("Resource", "minecraft:stone");
            replay.putInt("Count", 1);
            replay.putString("Status", ExactItemTransferCoordinator.Status.PERMISSION_DENIED.name());
            replay.putInt("Extracted", 0);
            replay.putInt("Inserted", 0);
            replay.putInt("RolledBack", 0);
            replay.putInt("Escrowed", 0);
            replays.add(replay);

            CompoundTag retained = new CompoundTag();
            retained.putUUID("EscrowId", new UUID(2L, index));
            retained.putUUID("Operation", new UUID(3L, index));
            retained.putUUID("Source", SOURCE);
            retained.putUUID("Destination", DESTINATION);
            retained.put("Stack", new ItemStack(Items.STONE, 1).save(new CompoundTag()));
            escrow.add(retained);
        }
        root.put("Replays", replays);
        root.put("Escrow", escrow);

        ExactItemTransferSavedData restored = ExactItemTransferSavedData.load(root);
        assertEquals(ExactItemTransferCoordinator.MAX_REPLAY_RECORDS,
                restored.coordinator().snapshot().replays().size(),
                "oversized replay collection is bounded");
        assertEquals(ExactItemTransferCoordinator.MAX_ESCROW_PARTS,
                restored.coordinator().escrowEntries().size(),
                "oversized escrow collection is bounded without discarding retained entries");
        assertEquals(new UUID(1L, 0),
                restored.coordinator().snapshot().replays().get(0).operationId(),
                "bounded load order is deterministic");
        assertEquals(new UUID(2L, 0),
                restored.coordinator().escrowEntries().get(0).escrowId(),
                "bounded escrow load order is deterministic");
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
