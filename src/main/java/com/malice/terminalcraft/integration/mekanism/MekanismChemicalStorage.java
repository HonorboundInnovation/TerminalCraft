package com.malice.terminalcraft.integration.mekanism;

import com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Dynamic translation of any live Mekanism chemical capability, including add-on chemicals. */
final class MekanismChemicalStorage {
    private static final int MAX_TANKS_PER_FAMILY = 64;

    private MekanismChemicalStorage() {}

    static Optional<OptionalChemicalStorageRegistry.ChemicalStorage> resolve(
            BlockEntity blockEntity, Direction side) {
        if (MekanismCapabilityView.chemicals(blockEntity, side).isEmpty()) return Optional.empty();
        return Optional.of(() -> snapshot(blockEntity, side));
    }

    private static List<OptionalChemicalStorageRegistry.Tank> snapshot(
            BlockEntity blockEntity, Direction side) {
        List<OptionalChemicalStorageRegistry.Tank> tanks = new ArrayList<>();
        for (MekanismCapabilityView.ChemicalHandler typed
                : MekanismCapabilityView.chemicals(blockEntity, side)) {
            IChemicalHandler handler = typed.handler();
            int count = Math.min(handler.getTanks(), MAX_TANKS_PER_FAMILY);
            for (int tank = 0; tank < count
                    && tanks.size() < com.malice.terminalcraft.device.DeviceValue.MAX_COLLECTION_ENTRIES; tank++) {
                ChemicalStack stack = handler.getChemicalInTank(tank);
                String resource = stack == null || stack.isEmpty() || stack.getTypeRegistryName() == null
                        ? "" : stack.getTypeRegistryName().toString();
                tanks.add(new OptionalChemicalStorageRegistry.Tank(
                        typed.type(), tank, resource,
                        stack == null || stack.isEmpty() ? 0 : stack.getAmount(),
                        handler.getTankCapacity(tank), "mb"));
            }
        }
        return List.copyOf(tanks);
    }
}
