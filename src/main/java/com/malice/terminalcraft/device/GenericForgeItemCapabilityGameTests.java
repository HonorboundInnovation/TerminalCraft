package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Live server-world checks for side-aware Forge item capabilities and replacement invalidation. */
@GameTestHolder("terminalcraft")
public final class GenericForgeItemCapabilityGameTests {
    private static final BlockPos HOST = new BlockPos(2, 2, 2);
    private static final BlockPos SOURCE = HOST.relative(Direction.WEST);
    private static final BlockPos DESTINATION = HOST.relative(Direction.EAST);

    private GenericForgeItemCapabilityGameTests() {}

    @GameTest(template = "empty")
    public static void exactTransferUsesLiveAdjacentCapabilities(GameTestHelper helper) {
        helper.setBlock(SOURCE, Blocks.CHEST);
        helper.setBlock(DESTINATION, Blocks.CHEST);
        ChestBlockEntity sourceChest = (ChestBlockEntity) helper.getBlockEntity(SOURCE);
        ChestBlockEntity destinationChest = (ChestBlockEntity) helper.getBlockEntity(DESTINATION);
        ItemStack exact = new ItemStack(Items.DIAMOND, 7);
        exact.getOrCreateTag().putString("terminalcraft_test", "live_capability");
        sourceChest.setItem(0, exact.copy());

        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(
                helper.getLevel(), helper.absolutePos(HOST));
        AdjacentForgeEndpointResolver.Candidate sourceCandidate =
                AdjacentForgeEndpointResolver.adjacent(
                        helper.getLevel().dimension().location().toString(),
                        helper.absolutePos(HOST), Direction.WEST);
        AdjacentForgeEndpointResolver.Candidate destinationCandidate =
                AdjacentForgeEndpointResolver.adjacent(
                        helper.getLevel().dimension().location().toString(),
                        helper.absolutePos(HOST), Direction.EAST);
        AdjacentForgeEndpointResolver.ResolvedItemEndpoint source = resolver
                .resolveItem(sourceCandidate.id()).endpointOptional().orElseThrow();
        AdjacentForgeEndpointResolver.ResolvedItemEndpoint destination = resolver
                .resolveItem(destinationCandidate.id()).endpointOptional().orElseThrow();

        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "gametest",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        ExactItemTransferCoordinator<ItemStack> coordinator = new ExactItemTransferCoordinator<>();
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(writer,
                UUID.randomUUID(), source.id(), source.port(), destination.id(), destination.port(),
                "minecraft:diamond", 7);

        helper.assertTrue(result.complete(), "live exact transfer should complete");
        helper.assertTrue(sourceChest.getItem(0).isEmpty(), "source chest should be empty");
        ItemStack received = destinationChest.getItem(0);
        helper.assertTrue(received.getCount() == 7, "destination should receive all items");
        helper.assertTrue(received.hasTag()
                        && "live_capability".equals(received.getTag().getString("terminalcraft_test")),
                "destination should preserve exact item metadata");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void adjacentDiscoveryRequiresExplicitReadGrant(GameTestHelper helper) {
        helper.setBlock(SOURCE, Blocks.CHEST);
        UUID endpointId = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), helper.absolutePos(HOST),
                Direction.WEST).id();
        DeviceCallContext undiscoverable = new DeviceCallContext(UUID.randomUUID(),
                "gametest-no-discovery", Set.of(DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess denied = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(undiscoverable), helper.getLevel(),
                helper.absolutePos(HOST));

        helper.assertTrue(denied.descriptors(DeviceRegistry.MAX_ENUMERATION_RESULTS).isEmpty(),
                "caller without device.read must not enumerate adjacent descriptors");
        helper.assertTrue(denied.descriptor(endpointId).isEmpty(),
                "caller without device.read must not probe an adjacent descriptor by UUID");

        AdjacentForgeDeviceAccess reader = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(DeviceCallContext.readOnly("gametest-reader")),
                helper.getLevel(), helper.absolutePos(HOST));
        helper.assertTrue(reader.descriptor(endpointId).isPresent(),
                "device.read should reveal a supported adjacent endpoint");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void itemEndpointEnforcesSideAuthorityLifecycleAndPermissions(GameTestHelper helper) {
        Direction exposedSide = Direction.EAST; // SOURCE is west of HOST, so its host-facing side is east.
        SidedItemChest original = sidedItemChest(helper, SOURCE, exposedSide);
        original.setStack(new ItemStack(Items.IRON_INGOT, 12));

        DeviceCallContext reader = DeviceCallContext.readOnly("gametest-reader");
        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "gametest-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        UUID endpointId = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), helper.absolutePos(HOST),
                Direction.WEST).id();

        AdjacentForgeDeviceAccess readAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(reader), helper.getLevel(), helper.absolutePos(HOST));
        DeviceResult denied = readAccess.call(endpointId, "inventory.extract",
                List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(1)));
        helper.assertTrue(!denied.isSuccess()
                        && denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "read-only caller must receive a structured permission denial");
        helper.assertTrue(original.stackCount() == 12, "denied mutation must not reach the item handler");

        AdjacentForgeDeviceAccess writeAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), helper.absolutePos(HOST));
        DeviceResult count = writeAccess.call(endpointId, "inventory.count",
                List.of(DeviceValue.of("minecraft:iron_ingot")));
        helper.assertTrue(count.isSuccess()
                        && ((DeviceValue.NumberValue) count.value().orElseThrow()).value() == 12,
                "host-facing item capability should be callable");

        ForgeCapabilityDevice wrongSide = new ForgeCapabilityDevice(
                helper.getLevel(), helper.absolutePos(SOURCE), Direction.WEST);
        helper.assertTrue(!wrongSide.hasInventory(),
                "the same item capability must not be visible from the opposite side");

        original.invalidateCaps();
        helper.setBlock(SOURCE, Blocks.AIR);
        DeviceResult removed = writeAccess.call(endpointId, "inventory.count",
                List.of(DeviceValue.of("minecraft:iron_ingot")));
        helper.assertTrue(!removed.isSuccess()
                        && removed.error().orElseThrow().code() == DeviceErrorCode.REMOVED
                        && removed.error().orElseThrow().retryable(),
                "removed endpoint should return a structured retryable lifecycle error");

        SidedItemChest replacement = sidedItemChest(helper, SOURCE, exposedSide);
        replacement.setStack(new ItemStack(Items.GOLD_INGOT, 5));
        DeviceResult replacementCount = writeAccess.call(endpointId, "inventory.count",
                List.of(DeviceValue.of("minecraft:gold_ingot")));
        helper.assertTrue(replacementCount.isSuccess()
                        && ((DeviceValue.NumberValue) replacementCount.value().orElseThrow()).value() == 5,
                "stable side-aware endpoint identity should reacquire a compatible replacement");
        helper.assertTrue(original.stackCount() == 12,
                "invalidated original handler must remain untouched after replacement");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void resolvedPortRejectsReplacementCapability(GameTestHelper helper) {
        helper.setBlock(SOURCE, Blocks.CHEST);
        ChestBlockEntity original = (ChestBlockEntity) helper.getBlockEntity(SOURCE);
        original.setItem(0, new ItemStack(Items.IRON_INGOT, 3));

        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(
                helper.getLevel(), helper.absolutePos(HOST));
        AdjacentForgeEndpointResolver.Candidate candidate = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), helper.absolutePos(HOST),
                Direction.WEST);
        AdjacentForgeEndpointResolver.ResolvedItemEndpoint oldEndpoint = resolver
                .resolveItem(candidate.id()).endpointOptional().orElseThrow();

        helper.setBlock(SOURCE, Blocks.BARREL);
        boolean rejected = false;
        try {
            oldEndpoint.port().extract("minecraft:iron_ingot", 1, 1);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "resolved port must reject a replacement capability");

        AdjacentForgeEndpointResolver.ResolvedItemEndpoint replacement = resolver
                .resolveItem(candidate.id()).endpointOptional().orElseThrow();
        helper.assertTrue(oldEndpoint.backingIdentity() != replacement.backingIdentity(),
                "fresh resolution should bind the replacement inventory");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void genericStorageContractSupportsMetadataTagsAndRequests(GameTestHelper helper) {
        helper.setBlock(SOURCE, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(SOURCE);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 12));

        UUID endpointId = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), helper.absolutePos(HOST),
                Direction.WEST).id();
        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "storage-contract",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), helper.absolutePos(HOST));

        DeviceResult metadata = access.call(endpointId, "storage.metadata", List.of());
        helper.assertTrue(metadata.isSuccess()
                        && "terminalcraft:item_storage".equals(string(metadata, "contract")),
                "generic storage metadata should expose the stable contract identity");

        DeviceResult query = access.call(endpointId, "storage.query", List.of(
                DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""),
                DeviceValue.of(8), DeviceValue.of("forge:ingots/iron")));
        helper.assertTrue(query.isSuccess(), "tag-filtered generic storage query should succeed");
        DeviceValue.ListValue entries = (DeviceValue.ListValue) ((DeviceValue.MapValue)
                query.value().orElseThrow()).values().get("entries");
        helper.assertTrue(entries.values().size() == 1,
                "iron ingot should be discoverable through its Forge item tag");

        DeviceResult count = access.call(endpointId, "storage.count",
                List.of(DeviceValue.of("minecraft:iron_ingot")));
        helper.assertTrue(count.isSuccess()
                        && "12".equals(((DeviceValue.StringValue) count.value().orElseThrow()).value()),
                "generic storage count should use lossless decimal-string encoding");

        DeviceResult extraction = access.call(endpointId, "storage.extract",
                List.of(DeviceValue.of("minecraft:iron_ingot"), DeviceValue.of(5)));
        helper.assertTrue(extraction.isSuccess()
                        && number(extraction, "executed") == 5
                        && "items".equals(string(extraction, "unit")),
                "generic extraction request should return a structured item-unit result");
        helper.assertTrue(chest.getItem(0).getCount() == 7,
                "generic extraction request should execute exactly once");
        helper.succeed();
    }

    private static String string(DeviceResult result, String key) {
        return ((DeviceValue.StringValue) ((DeviceValue.MapValue) result.value().orElseThrow())
                .values().get(key)).value();
    }

    private static double number(DeviceResult result, String key) {
        return ((DeviceValue.NumberValue) ((DeviceValue.MapValue) result.value().orElseThrow())
                .values().get(key)).value();
    }

    @GameTest(template = "empty")
    public static void callerBoundExactTransferReplaysBeforeEndpointResolution(GameTestHelper helper) {
        helper.setBlock(SOURCE, Blocks.CHEST);
        helper.setBlock(DESTINATION, Blocks.CHEST);
        ChestBlockEntity sourceChest = (ChestBlockEntity) helper.getBlockEntity(SOURCE);
        ChestBlockEntity destinationChest = (ChestBlockEntity) helper.getBlockEntity(DESTINATION);
        ItemStack exact = new ItemStack(Items.PAPER, 4);
        exact.getOrCreateTag().putString("terminalcraft_test", "public_replay");
        sourceChest.setItem(0, exact.copy());

        String dimension = helper.getLevel().dimension().location().toString();
        UUID sourceId = AdjacentForgeEndpointResolver.adjacent(
                dimension, helper.absolutePos(HOST), Direction.WEST).id();
        UUID destinationId = AdjacentForgeEndpointResolver.adjacent(
                dimension, helper.absolutePos(HOST), Direction.EAST).id();
        UUID operationId = UUID.randomUUID();

        AdjacentForgeDeviceAccess deniedAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(DeviceCallContext.readOnly("exact-transfer-reader")),
                helper.getLevel(), helper.absolutePos(HOST));
        DeviceResult denied = deniedAccess.transferExactItems(operationId, sourceId, destinationId,
                "minecraft:paper", 4);
        helper.assertTrue(!denied.isSuccess()
                        && denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "public exact transfer must enforce device.write");
        helper.assertTrue(sourceChest.getItem(0).getCount() == 4,
                "denied exact transfer must not mutate the source");

        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "exact-transfer-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), helper.absolutePos(HOST));
        DeviceResult first = access.transferExactItems(operationId, sourceId, destinationId,
                "minecraft:paper", 4);
        helper.assertTrue(first.isSuccess() && mapBoolean(first, "complete"),
                "caller-bound exact transfer should complete");
        helper.assertTrue(destinationChest.getItem(0).getCount() == 4
                        && ItemStack.isSameItemSameTags(exact, destinationChest.getItem(0)),
                "public route must preserve exact item identity");

        helper.setBlock(SOURCE, Blocks.AIR);
        helper.setBlock(DESTINATION, Blocks.AIR);
        DeviceResult replay = access.transferExactItems(operationId, sourceId, destinationId,
                "minecraft:paper", 4);
        helper.assertTrue(replay.isSuccess() && mapBoolean(replay, "replayed")
                        && mapNumber(replay, "inserted") == 4,
                "authoritative replay must precede live endpoint resolution");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementDuringRollbackEscrowsAndRecoversExactStack(GameTestHelper helper) {
        helper.setBlock(SOURCE, Blocks.CHEST);
        ChestBlockEntity sourceChest = (ChestBlockEntity) helper.getBlockEntity(SOURCE);
        ItemStack exact = new ItemStack(Items.PAPER, 5);
        exact.getOrCreateTag().putString("terminalcraft_test", "replacement_race");
        sourceChest.setItem(0, exact.copy());

        Direction destinationSide = Direction.WEST;
        RaceDestinationChest destination = raceDestinationChest(helper, DESTINATION, destinationSide,
                () -> helper.setBlock(SOURCE, Blocks.BARREL));
        String dimension = helper.getLevel().dimension().location().toString();
        UUID sourceId = AdjacentForgeEndpointResolver.adjacent(
                dimension, helper.absolutePos(HOST), Direction.WEST).id();
        UUID destinationId = AdjacentForgeEndpointResolver.adjacent(
                dimension, helper.absolutePos(HOST), Direction.EAST).id();
        UUID operationId = UUID.randomUUID();
        DeviceCallContext admin = new DeviceCallContext(UUID.randomUUID(), "item-escrow-admin",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE,
                        DeviceCallContext.ESCROW_ADMIN));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(admin), helper.getLevel(), helper.absolutePos(HOST));

        DeviceResult transfer = access.transferExactItems(operationId, sourceId, destinationId,
                "minecraft:paper", 5);
        helper.assertTrue(transfer.isSuccess()
                        && "escrowed".equals(mapString(transfer, "status"))
                        && mapNumber(transfer, "escrowed") == 5,
                "source replacement during rollback must retain all extracted items in escrow");
        BarrelBlockEntity replacement = (BarrelBlockEntity) helper.getBlockEntity(SOURCE);
        helper.assertTrue(replacement.isEmpty(),
                "replacement inventory must never receive a stale-port rollback");

        DeviceResult listed = access.listItemEscrow(64);
        helper.assertTrue(listed.isSuccess(), "authorized bounded escrow listing should succeed");
        UUID escrowId = listedEscrowId(listed, operationId);
        destination.allowInsert();
        DeviceResult recovered = access.recoverItemEscrow(escrowId, destinationId);
        helper.assertTrue(recovered.isSuccess()
                        && "complete".equals(mapString(recovered, "status"))
                        && mapNumber(recovered, "inserted") == 5,
                "authorized recovery should remove custody only after exact insertion");
        ItemStack received = destination.stack();
        helper.assertTrue(received.getCount() == 5 && ItemStack.isSameItemSameTags(exact, received),
                "escrow recovery must preserve the exact tagged stack");
        helper.assertTrue(listedEscrowIdOptional(access.listItemEscrow(64), operationId).isEmpty(),
                "completed recovery must remove the escrow diagnostic entry");
        helper.succeed();
    }

    private static boolean mapBoolean(DeviceResult result, String key) {
        return ((DeviceValue.BooleanValue) ((DeviceValue.MapValue) result.value().orElseThrow())
                .values().get(key)).value();
    }

    private static double mapNumber(DeviceResult result, String key) {
        return ((DeviceValue.NumberValue) ((DeviceValue.MapValue) result.value().orElseThrow())
                .values().get(key)).value();
    }

    private static String mapString(DeviceResult result, String key) {
        return ((DeviceValue.StringValue) ((DeviceValue.MapValue) result.value().orElseThrow())
                .values().get(key)).value();
    }

    private static UUID listedEscrowId(DeviceResult result, UUID operationId) {
        return listedEscrowIdOptional(result, operationId).orElseThrow(() ->
                new AssertionError("escrow listing did not contain operation " + operationId));
    }

    private static java.util.Optional<UUID> listedEscrowIdOptional(DeviceResult result,
                                                                    UUID operationId) {
        if (!result.isSuccess()) return java.util.Optional.empty();
        DeviceValue.ListValue list = (DeviceValue.ListValue) result.value().orElseThrow();
        for (DeviceValue value : list.values()) {
            DeviceValue.MapValue entry = (DeviceValue.MapValue) value;
            String listedOperation = ((DeviceValue.StringValue)
                    entry.values().get("operation_id")).value();
            if (operationId.toString().equals(listedOperation)) {
                return java.util.Optional.of(UUID.fromString(((DeviceValue.StringValue)
                        entry.values().get("escrow_id")).value()));
            }
        }
        return java.util.Optional.empty();
    }

    private static RaceDestinationChest raceDestinationChest(GameTestHelper helper,
                                                               BlockPos relativePosition,
                                                               Direction exposedSide,
                                                               Runnable replacement) {
        helper.setBlock(relativePosition, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(relativePosition);
        RaceDestinationChest chest = new RaceDestinationChest(absolute,
                helper.getLevel().getBlockState(absolute), exposedSide, replacement);
        helper.getLevel().setBlockEntity(chest);
        return chest;
    }

    private static SidedItemChest sidedItemChest(GameTestHelper helper, BlockPos relativePosition,
                                                  Direction exposedSide) {
        helper.setBlock(relativePosition, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(relativePosition);
        SidedItemChest chest = new SidedItemChest(absolute, helper.getLevel().getBlockState(absolute),
                exposedSide);
        helper.getLevel().setBlockEntity(chest);
        return chest;
    }

    private static final class RaceDestinationChest extends ChestBlockEntity {
        private final Direction exposedSide;
        private final Runnable replacement;
        private boolean reject = true;
        private final ItemStackHandler handler = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (reject && !simulate) {
                    replacement.run();
                    return stack.copy();
                }
                return super.insertItem(slot, stack, simulate);
            }
        };
        private LazyOptional<IItemHandler> capability = LazyOptional.of(() -> handler);

        private RaceDestinationChest(BlockPos position,
                                     net.minecraft.world.level.block.state.BlockState state,
                                     Direction exposedSide, Runnable replacement) {
            super(position, state);
            this.exposedSide = exposedSide;
            this.replacement = replacement;
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> requested,
                                                          @Nullable Direction side) {
            if (requested == ForgeCapabilities.ITEM_HANDLER) {
                return side == exposedSide ? capability.cast() : LazyOptional.empty();
            }
            return super.getCapability(requested, side);
        }

        @Override public void invalidateCaps() {
            super.invalidateCaps();
            capability.invalidate();
        }

        private void allowInsert() { reject = false; }
        private ItemStack stack() { return handler.getStackInSlot(0).copy(); }
    }

    private static final class SidedItemChest extends ChestBlockEntity {
        private final Direction exposedSide;
        private final ItemStackHandler handler = new ItemStackHandler(1);
        private LazyOptional<IItemHandler> capability = LazyOptional.of(() -> handler);

        private SidedItemChest(BlockPos position,
                               net.minecraft.world.level.block.state.BlockState state,
                               Direction exposedSide) {
            super(position, state);
            this.exposedSide = exposedSide;
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> requested,
                                                          @Nullable Direction side) {
            if (requested == ForgeCapabilities.ITEM_HANDLER) {
                return side == exposedSide ? capability.cast() : LazyOptional.empty();
            }
            return super.getCapability(requested, side);
        }

        @Override
        public void invalidateCaps() {
            super.invalidateCaps();
            capability.invalidate();
        }

        private void setStack(ItemStack stack) { handler.setStackInSlot(0, stack.copy()); }
        private int stackCount() { return handler.getStackInSlot(0).getCount(); }
    }

}
