package com.malice.terminalcraft.device;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Focused live proof for the dedicated, read-only Applied Energistics 2 bridge boundary. */
@GameTestHolder("terminalcraft")
public final class AppliedEnergisticsBridgeGameTests {
    private static final BlockPos HOST = new BlockPos(1, 2, 2);
    private static final BlockPos BRIDGE = HOST.relative(Direction.EAST);

    private AppliedEnergisticsBridgeGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dedicatedBridgeIsDetachedSafeAndReadOnly(GameTestHelper helper) {
        if (!ModList.get().isLoaded("ae2")) {
            helper.succeed();
            return;
        }
        Block bridge = ModRegistries.APPLIED_ENERGISTICS_BRIDGE_BLOCK.get();
        helper.assertTrue(bridge != null, "Applied Energistics bridge must be registered");
        helper.setBlock(BRIDGE, bridge);

        helper.succeedWhen(() -> {
            AdjacentForgeEndpointResolver.Candidate candidate = AdjacentForgeEndpointResolver.adjacent(
                    helper.getLevel().dimension().location().toString(), helper.absolutePos(HOST), Direction.EAST);
            DeviceCallContext context = new DeviceCallContext(UUID.randomUUID(), "ae2-bridge-test",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
            AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                    new DeviceRegistry().access(context), helper.getLevel(), helper.absolutePos(HOST));
            DeviceDescriptor descriptor = access.descriptor(candidate.id()).orElseThrow();
            helper.assertTrue("terminalcraft:applied_energistics_bridge".equals(descriptor.adapterId()),
                    "bridge must select the presence-gated AE2 adapter");
            helper.assertTrue(descriptor.capabilities().contains("applied_energistics_network")
                            && descriptor.capabilities().contains("inventory"),
                    "bridge must expose bounded network and generic item telemetry");
            String status = ((DeviceValue.StringValue) descriptor.properties()
                    .get("applied_energistics_attachment_status")).value();
            helper.assertTrue("detached".equals(status),
                    "bridge without a neighboring exposed node must remain detached, got " + status);

            DeviceResult denied = access.call(candidate.id(), "storage.extract",
                    List.of(DeviceValue.of("minecraft:stone"), DeviceValue.of(1)));
            helper.assertTrue(denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                    "AE2 bridge mutation must fail closed before adapter invocation");
        });
    }
}
