package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless exact ItemStack identity, copy-boundary, partial rollback, and capability tests. */
public final class ForgeItemStackTransferPortTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final DeviceCallContext WRITER = new DeviceCallContext(
            UUID.fromString("00000000-0000-0000-0000-000000000303"), "writer",
            Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

    private ForgeItemStackTransferPortTest() {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        extractionPreservesTagsAndReturnsCopies();
        insertionPreservesTagsAndReturnsDefensiveRemainder();
        coordinatorRollsBackExactTaggedRemainder();
        unavailableCapabilityFailsWithoutInventingPayloads();
        restrictedSlotsAreSkippedWithoutBypass();
        oversizedHandlersStayWithinVisibleSlotBound();
        changingContentsAreRejectedAsAdapterCorruption();
        System.out.println("Forge ItemStack transfer port tests: OK");
    }

    private static void extractionPreservesTagsAndReturnsCopies() {
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, taggedStack(7, "healing"));
        ForgeItemStackTransferPort port = new ForgeItemStackTransferPort(() -> handler);

        List<ItemStack> extracted = port.extract("minecraft:paper", 3, 8);
        assertEquals(1, extracted.size(), "one exact stack part extracted");
        assertEquals(3, extracted.get(0).getCount(), "requested count extracted");
        assertEquals("healing", extracted.get(0).getTag().getString("terminalcraft_test"),
                "tag preserved during extraction");
        assertEquals(4, handler.getStackInSlot(0).getCount(), "handler mutated exactly once");

        extracted.get(0).setCount(1);
        assertEquals(4, handler.getStackInSlot(0).getCount(),
                "returned payload does not alias handler storage");
    }

    private static void insertionPreservesTagsAndReturnsDefensiveRemainder() {
        ItemStackHandler handler = new ItemStackHandler(1) {
            @Override public int getSlotLimit(int slot) { return 2; }
        };
        ForgeItemStackTransferPort port = new ForgeItemStackTransferPort(() -> handler);
        ItemStack offered = taggedStack(5, "swiftness");

        ItemStack remainder = port.insert(offered);
        assertEquals(5, offered.getCount(), "port never mutates caller payload");
        assertEquals(2, handler.getStackInSlot(0).getCount(), "bounded destination accepted items");
        assertEquals(3, remainder.getCount(), "unaccepted exact remainder returned");
        assertTrue(ItemStack.isSameItemSameTags(offered, handler.getStackInSlot(0)),
                "inserted stack retains exact identity");
        assertTrue(ItemStack.isSameItemSameTags(offered, remainder),
                "remainder retains exact identity");

        remainder.setCount(1);
        assertEquals(2, handler.getStackInSlot(0).getCount(),
                "returned remainder does not alias destination storage");
    }

    private static void coordinatorRollsBackExactTaggedRemainder() {
        ItemStackHandler sourceHandler = new ItemStackHandler(2);
        sourceHandler.setStackInSlot(0, taggedStack(6, "long_healing"));
        ItemStackHandler destinationHandler = new ItemStackHandler(1) {
            @Override public int getSlotLimit(int slot) { return 2; }
        };
        ForgeItemStackTransferPort source = new ForgeItemStackTransferPort(() -> sourceHandler);
        ForgeItemStackTransferPort destination = new ForgeItemStackTransferPort(() -> destinationHandler);
        ExactItemTransferCoordinator<ItemStack> coordinator = new ExactItemTransferCoordinator<>();

        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(WRITER,
                UUID.fromString("00000000-0000-0000-0000-000000000304"), SOURCE, source,
                DESTINATION, destination, "minecraft:paper", 6);

        assertEquals(ExactItemTransferCoordinator.Status.PARTIAL, result.status(), "partial status");
        assertEquals(2, result.inserted(), "destination accepted capacity");
        assertEquals(4, result.rolledBack(), "exact remainder restored");
        assertEquals(0, result.escrowed(), "successful rollback avoids escrow");
        assertEquals(4, sourceHandler.getStackInSlot(0).getCount(), "source contains rollback remainder");
        assertEquals("long_healing",
                sourceHandler.getStackInSlot(0).getTag().getString("terminalcraft_test"),
                "rollback retains source tag");
        assertEquals("long_healing",
                destinationHandler.getStackInSlot(0).getTag().getString("terminalcraft_test"),
                "destination retains source tag");
        assertEquals(result.extracted(), result.inserted() + result.rolledBack() + result.escrowed(),
                "coordinator conserves exact stacks");
    }

    private static void unavailableCapabilityFailsWithoutInventingPayloads() {
        ForgeItemStackTransferPort port = new ForgeItemStackTransferPort(() -> null);
        assertThrows(() -> port.extract("minecraft:paper", 1, 1),
                "missing source capability rejected");
        assertThrows(() -> port.insert(taggedStack(1, "healing")),
                "missing destination capability rejected");
    }

    private static void restrictedSlotsAreSkippedWithoutBypass() {
        ItemStackHandler sourceHandler = new ItemStackHandler(2) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return slot == 0 ? ItemStack.EMPTY : super.extractItem(slot, amount, simulate);
            }
        };
        sourceHandler.setStackInSlot(0, taggedStack(3, "locked"));
        sourceHandler.setStackInSlot(1, taggedStack(4, "open"));
        ForgeItemStackTransferPort source = new ForgeItemStackTransferPort(() -> sourceHandler);
        List<ItemStack> extracted = source.extract("minecraft:paper", 4, 4);
        assertEquals(1, extracted.size(), "only extractable slot contributes");
        assertEquals("open", extracted.get(0).getTag().getString("terminalcraft_test"),
                "slot restriction is not bypassed");
        assertEquals(3, sourceHandler.getStackInSlot(0).getCount(), "locked slot remains untouched");

        ItemStackHandler destinationHandler = new ItemStackHandler(2) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return slot == 0 ? stack.copy() : super.insertItem(slot, stack, simulate);
            }
        };
        ForgeItemStackTransferPort destination = new ForgeItemStackTransferPort(() -> destinationHandler);
        ItemStack remainder = destination.insert(taggedStack(2, "routed"));
        assertTrue(remainder.isEmpty(), "later permitted slot accepts payload");
        assertTrue(destinationHandler.getStackInSlot(0).isEmpty(), "restricted insertion slot remains empty");
        assertEquals(2, destinationHandler.getStackInSlot(1).getCount(),
                "permitted insertion slot receives payload");
    }

    private static void oversizedHandlersStayWithinVisibleSlotBound() {
        int slots = GenericCapabilityDevice.MAX_INVENTORY_SLOTS + 1;
        ItemStackHandler handler = new ItemStackHandler(slots);
        handler.setStackInSlot(GenericCapabilityDevice.MAX_INVENTORY_SLOTS,
                taggedStack(1, "outside-bound"));
        ForgeItemStackTransferPort port = new ForgeItemStackTransferPort(() -> handler);
        assertEquals(List.of(), port.extract("minecraft:paper", 1, 1),
                "slot beyond the public bound is never scanned");
        ItemStack remainder = port.insert(taggedStack(1, "bounded-insert"));
        assertTrue(remainder.isEmpty(), "bounded visible slots can still accept insertion");
        assertEquals(1, handler.getStackInSlot(0).getCount(), "insertion starts within visible bound");
        assertEquals(1, handler.getStackInSlot(GenericCapabilityDevice.MAX_INVENTORY_SLOTS).getCount(),
                "out-of-bound slot remains untouched");
    }

    private static void changingContentsAreRejectedAsAdapterCorruption() {
        ItemStackHandler handler = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack changed = taggedStack(Math.min(amount, 1), "changed-after-read");
                setStackInSlot(slot, ItemStack.EMPTY);
                return changed;
            }
        };
        handler.setStackInSlot(0, taggedStack(1, "visible-before-read"));
        ForgeItemStackTransferPort port = new ForgeItemStackTransferPort(() -> handler);
        assertThrows(() -> port.extract("minecraft:paper", 1, 1),
                "handler identity race is rejected instead of normalized");
    }

    private static ItemStack taggedStack(int count, String variant) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("terminalcraft_test", variant);
        stack.setTag(tag);
        return stack;
    }

    private static void assertThrows(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message + ": expected exception");
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
