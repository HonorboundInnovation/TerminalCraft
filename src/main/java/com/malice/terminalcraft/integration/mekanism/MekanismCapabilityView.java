package com.malice.terminalcraft.integration.mekanism;

import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;

import java.util.ArrayList;
import java.util.List;

/** Side-aware, bounded access to Mekanism's registered Forge capabilities. */
final class MekanismCapabilityView {
    private MekanismCapabilityView() {}

    static List<ChemicalHandler> chemicals(BlockEntity blockEntity, Direction side) {
        List<ChemicalHandler> handlers = new ArrayList<>(4);
        for (ChemicalType type : ChemicalType.values()) {
            addChemical(handlers, type.name().toLowerCase(java.util.Locale.ROOT),
                    chemical(blockEntity, side, type));
        }
        return List.copyOf(handlers);
    }

    private static IChemicalHandler chemical(BlockEntity blockEntity, Direction side,
                                              ChemicalType type) {
        return switch (type) {
            case GAS -> resolve(blockEntity, Capabilities.GAS_HANDLER, side);
            case INFUSION -> resolve(blockEntity, Capabilities.INFUSION_HANDLER, side);
            case PIGMENT -> resolve(blockEntity, Capabilities.PIGMENT_HANDLER, side);
            case SLURRY -> resolve(blockEntity, Capabilities.SLURRY_HANDLER, side);
        };
    }

    static IStrictEnergyHandler energy(BlockEntity blockEntity, Direction side) {
        return resolve(blockEntity, Capabilities.STRICT_ENERGY, side);
    }

    static IHeatHandler heat(BlockEntity blockEntity, Direction side) {
        return resolve(blockEntity, Capabilities.HEAT_HANDLER, side);
    }

    private static void addChemical(List<ChemicalHandler> handlers, String type,
                                    IChemicalHandler handler) {
        if (handler != null) handlers.add(new ChemicalHandler(type, handler));
    }

    private static <T> T resolve(BlockEntity blockEntity, Capability<T> capability, Direction side) {
        return blockEntity.getCapability(capability, side).resolve().orElse(null);
    }

    record ChemicalHandler(String type, IChemicalHandler handler) {}
}
