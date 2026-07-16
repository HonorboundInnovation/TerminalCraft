package com.malice.terminalcraft.device;

import com.malice.terminalcraft.registry.ModRegistries;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Focused live proof for the dedicated, read-only Refined Storage bridge boundary. */
@GameTestHolder("terminalcraft")
public final class RefinedStorageBridgeGameTests {
    private static final BlockPos HOST = new BlockPos(1, 2, 2);
    private static final BlockPos BRIDGE = HOST.relative(Direction.EAST);
    private static final BlockPos NODE = BRIDGE.relative(Direction.EAST);

    private RefinedStorageBridgeGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void dedicatedBridgeAttachesReadOnlyToOneAdjacentNetwork(GameTestHelper helper) {
        if (!ModList.get().isLoaded("refinedstorage")) {
            helper.succeed();
            return;
        }
        Block controller = ForgeRegistries.BLOCKS.getValue(
                new net.minecraft.resources.ResourceLocation("refinedstorage", "creative_controller"));
        helper.assertTrue(controller != null, "Refined Storage 1.12.4 creative controller is required");
        Block storage = ForgeRegistries.BLOCKS.getValue(
                new net.minecraft.resources.ResourceLocation("refinedstorage", "1k_storage_block"));
        helper.assertTrue(storage != null, "Refined Storage 1K item storage block is required");
        helper.setBlock(BRIDGE, ModRegistries.REFINED_STORAGE_BRIDGE_BLOCK.get());
        helper.setBlock(NODE, controller);
        helper.setBlock(NODE.above(), storage);

        UUID principalId = UUID.randomUUID();
        boolean[] inserted = {false};
        helper.succeedWhen(() -> {
            AdjacentForgeEndpointResolver.Candidate candidate = AdjacentForgeEndpointResolver.adjacent(
                    helper.getLevel().dimension().location().toString(), helper.absolutePos(HOST), Direction.EAST);
            DeviceCallContext context = new DeviceCallContext(principalId, "rs-bridge-test",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
            AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                    new DeviceRegistry().access(context), helper.getLevel(), helper.absolutePos(HOST));
            DeviceDescriptor descriptor = access.descriptor(candidate.id()).orElseThrow();
            helper.assertTrue("terminalcraft:refined_storage_bridge".equals(descriptor.adapterId()),
                    "bridge must select the presence-gated RS adapter");
            helper.assertTrue(descriptor.capabilities().contains("refined_storage_network")
                            && descriptor.capabilities().contains("inventory"),
                    "bridge must expose bounded network and generic item telemetry");
            String status = ((DeviceValue.StringValue) descriptor.properties()
                    .get("refined_storage_attachment_status")).value();
            helper.assertTrue(status.startsWith("attached_"),
                    "bridge must attach to the single directly adjacent RS node, got " + status);

            BlockEntity controllerEntity = helper.getBlockEntity(NODE);
            helper.assertTrue(controllerEntity instanceof INetworkNodeProxy<?>,
                    "controller must expose the supported network-node proxy API");
            com.refinedmods.refinedstorage.api.network.INetwork network =
                    ((INetworkNodeProxy<?>) controllerEntity).getNode().getNetwork();
            helper.assertTrue(network != null && network.canRun(), "fixture network must be online");
            if (!inserted[0]) {
                ItemStack remainder = network.insertItem(
                        new ItemStack(Items.DIAMOND, 37), 37, Action.PERFORM);
                // A running controller can precede storage-cache initialization by a tick. Retry only
                // when RS reports that none of this one-shot fixture insertion was accepted.
                helper.assertTrue(remainder.isEmpty() || remainder.getCount() == 37,
                        "fixture insertion must be atomic, remainder=" + remainder.getCount());
                inserted[0] = remainder.isEmpty();
                helper.assertTrue(inserted[0], "fixture storage is not ready for insertion");
            }

            DeviceResult query = access.call(candidate.id(), "storage.query",
                    List.of(DeviceValue.of("minecraft:diamond"), DeviceValue.of(""),
                            DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(1)));
            helper.assertTrue(query.isSuccess(), "bounded generic query must succeed");
            DeviceValue.MapValue page = (DeviceValue.MapValue) query.value().orElseThrow();
            DeviceValue.ListValue entries = (DeviceValue.ListValue) page.values().get("entries");
            helper.assertTrue(entries.values().size() == 1,
                    "network item telemetry must expose the inserted diamond aggregate");
            DeviceValue.MapValue diamond = (DeviceValue.MapValue) entries.values().get(0);
            String count = ((DeviceValue.StringValue) diamond.values().get("count")).value();
            helper.assertTrue("37".equals(count),
                    "network item telemetry must preserve the exact aggregate count, got " + count);

            DeviceResult denied = access.call(candidate.id(), "storage.extract",
                    List.of(DeviceValue.of("minecraft:stone"), DeviceValue.of(1)));
            helper.assertTrue(denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                    "mutation must fail closed before Phase 4 principal mapping");
        });
    }
}
