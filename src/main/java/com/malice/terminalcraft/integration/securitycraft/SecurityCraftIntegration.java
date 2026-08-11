package com.malice.terminalcraft.integration.securitycraft;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceEndpoint;
import com.malice.terminalcraft.device.PrincipalIdentity;
import com.malice.terminalcraft.integration.OptionalDeviceEndpointRegistry;
import com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.integration.OptionalNearbyDeviceEndpointRegistry;
import com.malice.terminalcraft.integration.OptionalSensorProbeRegistry;
import net.geforcemods.securitycraft.api.IOwnable;
import net.geforcemods.securitycraft.entity.SecuritySeaBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/**
 * SecurityCraft 1.20.1 adapter, isolated behind the optional-mod loader boundary.
 * Native mutations are deliberately owner-only and never expose or accept passcodes.
 */
public final class SecurityCraftIntegration implements OptionalIntegration {
    @Override
    public void initialize() {
        OptionalDeviceEndpointRegistry.register(context ->
                context.blockEntity() instanceof IOwnable ownable
                        ? Optional.of(new SecurityCraftDeviceEndpoint(context, ownable))
                        : Optional.empty());
        OptionalSensorProbeRegistry.register(SecurityCraftSensorProbe::read);
        OptionalNearbyDeviceEndpointRegistry.register((level, hostPosition) -> level
                .getEntitiesOfClass(SecuritySeaBoat.class, new AABB(hostPosition).inflate(1.5),
                        entity -> entity.isAlive())
                .stream().limit(16).<DeviceEndpoint>map(entity ->
                        new SecurityCraftSeaBoatEndpoint(level, hostPosition, entity)).toList());
        OptionalDeviceMutationPolicyRegistry.registerContextual(SecurityCraftIntegration::mutationPolicy);
    }

    static OptionalDeviceMutationPolicyRegistry.Decision mutationPolicy(
            BlockEntity blockEntity, DeviceCallContext caller) {
        if (!(blockEntity instanceof IOwnable ownable)) {
            return OptionalDeviceMutationPolicyRegistry.Decision.allow();
        }
        ServerPlayer player = authenticatedPlayer(blockEntity, caller);
        if (player == null || !ownable.isOwnedBy(player, false)) {
            return OptionalDeviceMutationPolicyRegistry.Decision.deny(
                    "SecurityCraft mutation requires the online, authenticated owning player");
        }
        return OptionalDeviceMutationPolicyRegistry.Decision.allow();
    }

    static ServerPlayer authenticatedPlayer(BlockEntity blockEntity, DeviceCallContext caller) {
        if (caller.principalKind() != PrincipalIdentity.Kind.PLAYER
                || !(blockEntity.getLevel() instanceof ServerLevel level)) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(caller.principalId());
        return player != null && player.getGameProfile().getId().equals(caller.principalId())
                ? player : null;
    }

    static ServerPlayer authenticatedPlayer(ServerLevel level, DeviceCallContext caller) {
        if (caller.principalKind() != PrincipalIdentity.Kind.PLAYER) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(caller.principalId());
        return player != null && player.getGameProfile().getId().equals(caller.principalId())
                ? player : null;
    }
}
