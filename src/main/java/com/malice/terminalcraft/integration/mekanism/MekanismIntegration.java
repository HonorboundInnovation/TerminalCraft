package com.malice.terminalcraft.integration.mekanism;

import com.malice.terminalcraft.integration.OptionalDeviceEndpointRegistry;
import com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry;
import com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.integration.OptionalSensorProbeRegistry;
import mekanism.api.security.SecurityMode;
import mekanism.common.tile.base.TileEntityMekanism;

import java.util.Optional;

/**
 * Native Mekanism adapter isolated behind Forge's optional-mod boundary. TerminalCraft never
 * impersonates a Mekanism owner: non-public machines remain read-only and mutation fails closed.
 */
public final class MekanismIntegration implements OptionalIntegration {
    @Override
    public void initialize() {
        OptionalDeviceEndpointRegistry.register(context ->
                context.blockEntity() instanceof TileEntityMekanism tile
                        ? Optional.of(new MekanismDeviceEndpoint(context, tile)) : Optional.empty());
        OptionalSensorProbeRegistry.register(MekanismSensorProbe::read);
        OptionalChemicalStorageRegistry.register(MekanismChemicalStorage::resolve);
        OptionalDeviceMutationPolicyRegistry.register(MekanismIntegration::mutationPolicy);
    }

    private static OptionalDeviceMutationPolicyRegistry.Decision mutationPolicy(
            net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (!(blockEntity instanceof TileEntityMekanism tile) || !tile.hasSecurity()) {
            return OptionalDeviceMutationPolicyRegistry.Decision.allow();
        }
        if (tile.getSecurity() == null || tile.getSecurity().getMode() != SecurityMode.PUBLIC) {
            return OptionalDeviceMutationPolicyRegistry.Decision.deny(
                    "Mekanism machine is not public; TerminalCraft does not impersonate its owner or trusted users");
        }
        return OptionalDeviceMutationPolicyRegistry.Decision.allow();
    }
}
