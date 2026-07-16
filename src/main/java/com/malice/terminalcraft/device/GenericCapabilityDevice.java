package com.malice.terminalcraft.device;

import java.util.List;

/** Implementation-neutral telemetry, simulation, and authorized mutation surface. */
public interface GenericCapabilityDevice extends GenericItemStorage {
    int MAX_INVENTORY_SLOTS = 128;
    int MAX_FLUID_TANKS = 64;
    int MAX_TRANSFER_AMOUNT = 1_000_000;

    default boolean hasInventory() { return false; }
    default List<ItemSlot> itemSlots(int limit) { return List.of(); }
    default long itemCount(String resourceId) { return 0; }
    @Override default long countItem(String resourceId) { return itemCount(resourceId); }
    @Override default ItemPage queryItems(ItemQuery query) { return new ItemPage(List.of(), ""); }
    default long simulateItemInsert(String resourceId, int count) { return 0; }
    default long simulateItemExtract(String resourceId, int count) { return 0; }
    default TransferOutcome insertItems(String resourceId, int count) {
        return TransferOutcome.none(count);
    }
    default TransferOutcome extractItems(String resourceId, int count) {
        return TransferOutcome.none(count);
    }

    default boolean hasFluidStorage() { return false; }
    default List<FluidTank> fluidTanks(int limit) { return List.of(); }
    default long simulateFluidFill(String resourceId, int amountMb) { return 0; }
    default long simulateFluidDrain(String resourceId, int amountMb) { return 0; }
    default TransferOutcome fillFluid(String resourceId, int amountMb) {
        return TransferOutcome.none(amountMb);
    }
    default TransferOutcome drainFluid(String resourceId, int amountMb) {
        return TransferOutcome.none(amountMb);
    }

    default boolean hasEnergyStorage() { return false; }
    default EnergyStatus energyStatus() { return new EnergyStatus(0, 0, false, false); }
    default long simulateEnergyReceive(int amountFe) { return 0; }
    default long simulateEnergyExtract(int amountFe) { return 0; }
    default TransferOutcome receiveEnergy(int amountFe) { return TransferOutcome.none(amountFe); }
    default TransferOutcome extractEnergy(int amountFe) { return TransferOutcome.none(amountFe); }

    record ItemSlot(int slot, String resourceId, int count, int slotLimit) {}
    record FluidTank(int tank, String resourceId, int amountMb, int capacityMb) {}
    record EnergyStatus(int storedFe, int capacityFe, boolean canReceive, boolean canExtract) {}

    /** Reports preflight capacity and the amount actually executed after state was rechecked. */
    record TransferOutcome(long requested, long simulated, long executed) {
        public TransferOutcome {
            if (requested < 0 || simulated < 0 || executed < 0
                    || simulated > requested || executed > simulated) {
                throw new IllegalArgumentException("invalid transfer outcome");
            }
        }

        public static TransferOutcome none(long requested) {
            return new TransferOutcome(requested, 0, 0);
        }

        public boolean complete() {
            return executed == requested;
        }
    }
}
