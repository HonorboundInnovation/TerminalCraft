package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.device.DeviceCallContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;
import java.util.UUID;

/** Headless characterization of caller-aware, narrowing-only optional mutation policy dispatch. */
public final class OptionalDeviceMutationPolicyRegistryTest {
    private OptionalDeviceMutationPolicyRegistryTest() {}

    public static void main(String[] args) {
        com.malice.terminalcraft.testsupport.HeadlessMinecraftBootstrap.initialize();
        BlockEntityType<?> type = BlockEntityType.Builder.of(
                RefinedStorageBridgeBlockEntity::new, Blocks.STONE).build(null);
        RefinedStorageBridgeBlockEntity target = new RefinedStorageBridgeBlockEntity(
                type, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        DeviceCallContext caller = DeviceCallContext.player(UUID.randomUUID(), "contextual-player",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        boolean[] observed = {false};
        OptionalDeviceMutationPolicyRegistry.registerContextual((blockEntity, context) -> {
            if (blockEntity == target && context.equals(caller)) observed[0] = true;
            return OptionalDeviceMutationPolicyRegistry.Decision.allow();
        });

        OptionalDeviceMutationPolicyRegistry.Decision decision =
                OptionalDeviceMutationPolicyRegistry.evaluate(target, caller);
        require(decision.allowed(), "contextual allow policy should preserve mutation when no policy denies");
        require(observed[0], "contextual policy must receive the exact authenticated caller");

        OptionalDeviceMutationPolicyRegistry.registerContextual((blockEntity, context) ->
                blockEntity == target && context.principalKind() == com.malice.terminalcraft.device.PrincipalIdentity.Kind.PLAYER
                        ? OptionalDeviceMutationPolicyRegistry.Decision.deny("player denied for characterization")
                        : OptionalDeviceMutationPolicyRegistry.Decision.allow());
        OptionalDeviceMutationPolicyRegistry.Decision denied =
                OptionalDeviceMutationPolicyRegistry.evaluate(target, caller);
        require(!denied.allowed() && denied.reason().contains("player denied"),
                "contextual deny policy must narrow mutation access");

        OptionalDeviceMutationPolicyRegistry.Decision unbound =
                OptionalDeviceMutationPolicyRegistry.evaluate(target);
        require(unbound.allowed(), "unbound checks must not inherit a player-only denial");
        System.out.println("Optional mutation policy context tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
