package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.gametest.GameTestHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Live world lifecycle checks for side-aware fluid and Forge Energy capabilities. */
@GameTestHolder("terminalcraft")
public final class GenericForgeFluidEnergyCapabilityGameTests {
    private static final BlockPos TARGET = new BlockPos(2, 2, 2);
    private static final Direction EXPOSED_SIDE = Direction.NORTH;
    private static final BlockPos TRANSFER_HOST = new BlockPos(5, 2, 5);

    private GenericForgeFluidEnergyCapabilityGameTests() {}

    @GameTest(template = "empty")
    public static void fluidCapabilityIsSideAwareAndReacquiredAfterReplacement(GameTestHelper helper) {
        CapabilityChest original = capabilityChest(helper, 1_000, 0);
        ForgeCapabilityDevice exposed = device(helper, EXPOSED_SIDE);
        ForgeCapabilityDevice hidden = device(helper, Direction.SOUTH);

        helper.assertTrue(exposed.hasFluidStorage(), "fluid capability should be visible on its exposed side");
        helper.assertTrue(!hidden.hasFluidStorage(), "fluid capability should be hidden on other sides");
        GenericCapabilityDevice.TransferOutcome first = exposed.fillFluid("minecraft:water", 750);
        helper.assertTrue(first.complete(), "initial fluid fill should complete");
        helper.assertTrue(original.fluidAmount() == 750, "initial tank should contain the executed amount");

        original.invalidateCaps();
        helper.setBlock(TARGET, Blocks.BARREL);
        helper.assertTrue(!exposed.hasFluidStorage(), "replacement without fluid capability should be observed");
        helper.assertTrue(exposed.fillFluid("minecraft:water", 100).executed() == 0,
                "mutation must not use the invalidated fluid handler");

        CapabilityChest replacement = capabilityChest(helper, 2_000, 0);
        helper.assertTrue(exposed.hasFluidStorage(), "fluid capability should be reacquired from replacement");
        GenericCapabilityDevice.TransferOutcome second = exposed.fillFluid("minecraft:water", 1_250);
        helper.assertTrue(second.complete(), "replacement fluid fill should complete");
        helper.assertTrue(replacement.fluidAmount() == 1_250, "replacement tank should receive fluid");
        helper.assertTrue(original.fluidAmount() == 750, "invalidated original tank must remain unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void genericEndpointReturnsStructuredLifecycleAuthorityAndBudgetResults(GameTestHelper helper) {
        BlockPos host = TARGET.relative(Direction.NORTH);
        CapabilityChest original = capabilityChest(helper, 1_000, 1_000);
        UUID endpointId = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), helper.absolutePos(host),
                Direction.SOUTH).id();

        DeviceCallContext reader = DeviceCallContext.readOnly("fluid-energy-reader");
        AdjacentForgeDeviceAccess readAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(reader), helper.getLevel(), helper.absolutePos(host));
        DeviceResult denied = readAccess.call(endpointId, "fluid.fill",
                List.of(DeviceValue.of("minecraft:water"), DeviceValue.of(100)));
        helper.assertTrue(!denied.isSuccess()
                        && denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "read-only fluid mutation should be denied before reaching the handler");
        helper.assertTrue(original.fluidAmount() == 0, "denied fluid mutation must have no side effect");

        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "fluid-energy-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess writeAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), helper.absolutePos(host));
        DeviceResult status = writeAccess.call(endpointId, "energy.status", List.of());
        helper.assertTrue(status.isSuccess(), "host-facing energy capability should be callable");

        original.invalidateCaps();
        helper.setBlock(TARGET, Blocks.AIR);
        DeviceResult removed = writeAccess.call(endpointId, "energy.status", List.of());
        helper.assertTrue(!removed.isSuccess()
                        && removed.error().orElseThrow().code() == DeviceErrorCode.REMOVED
                        && removed.error().orElseThrow().retryable(),
                "removed generic endpoint should be a structured retryable lifecycle failure");

        CapabilityChest wrongSide = capabilityChest(helper, TARGET, Direction.SOUTH, 1_000, 1_000);
        DeviceResult unsupported = writeAccess.call(endpointId, "energy.status", List.of());
        helper.assertTrue(!unsupported.isSuccess()
                        && unsupported.error().orElseThrow().code() == DeviceErrorCode.UNSUPPORTED,
                "present block without a host-facing capability should be unsupported");
        wrongSide.invalidateCaps();

        CapabilityChest replacement = capabilityChest(helper, 1_000, 2_000);
        DeviceResult replacementStatus = writeAccess.call(endpointId, "energy.status", List.of());
        helper.assertTrue(replacementStatus.isSuccess(),
                "stable endpoint locator should reacquire a compatible replacement");

        DeviceCallContext burstCaller = new DeviceCallContext(UUID.randomUUID(), "burst-caller",
                Set.of(DeviceCallContext.READ));
        AdjacentForgeDeviceAccess burstAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(burstCaller), helper.getLevel(), helper.absolutePos(host));
        for (int call = 0; call < DeviceInvocationBudget.MAX_CALLS_PER_TICK; call++) {
            helper.assertTrue(burstAccess.call(endpointId, "energy.status", List.of()).isSuccess(),
                    "call at shared per-tick boundary should be admitted");
        }
        DeviceResult limited = burstAccess.call(endpointId, "energy.status", List.of());
        helper.assertTrue(!limited.isSuccess()
                        && limited.error().orElseThrow().code() == DeviceErrorCode.BUSY
                        && limited.error().orElseThrow().retryable(),
                "one call over the shared logical-tick budget should be retryable busy");
        helper.assertTrue(replacement.energyStored() == 0,
                "read-only burst and rejected call must not mutate replacement energy");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void exactFluidTransferUsesLiveAdjacentCapabilities(GameTestHelper helper) {
        BlockPos sourcePosition = TRANSFER_HOST.relative(Direction.WEST);
        BlockPos destinationPosition = TRANSFER_HOST.relative(Direction.EAST);
        CapabilityChest sourceChest = capabilityChest(helper, sourcePosition, Direction.EAST, 1_000, 0);
        CapabilityChest destinationChest = capabilityChest(helper, destinationPosition, Direction.WEST, 1_000, 0);
        FluidStack exact = new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 750);
        exact.getOrCreateTag().putString("terminalcraft_test", "live_exact_fluid");
        sourceChest.setFluid(exact);

        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(
                helper.getLevel(), helper.absolutePos(TRANSFER_HOST));
        String dimension = helper.getLevel().dimension().location().toString();
        UUID sourceId = AdjacentForgeEndpointResolver.adjacent(dimension,
                helper.absolutePos(TRANSFER_HOST), Direction.WEST).id();
        UUID destinationId = AdjacentForgeEndpointResolver.adjacent(dimension,
                helper.absolutePos(TRANSFER_HOST), Direction.EAST).id();
        var source = resolver.resolveFluid(sourceId).endpointOptional().orElseThrow();
        var destination = resolver.resolveFluid(destinationId).endpointOptional().orElseThrow();
        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "gametest",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

        var result = new ExactFluidTransferCoordinator<FluidStack>().transfer(writer, UUID.randomUUID(),
                source.id(), source.port(), destination.id(), destination.port(),
                "minecraft:water", 750);

        helper.assertTrue(result.complete(), "live exact fluid transfer should complete");
        helper.assertTrue(sourceChest.fluidAmount() == 0, "source tank should be empty");
        helper.assertTrue(destinationChest.fluidAmount() == 750, "destination should receive all fluid");
        helper.assertTrue(destinationChest.fluidTag("terminalcraft_test").equals("live_exact_fluid"),
                "destination should preserve exact fluid metadata");
        helper.succeed();
    }


    @GameTest(template = "empty")
    public static void callerBoundExactFluidTransferReplaysBeforeEndpointResolution(GameTestHelper helper) {
        BlockPos sourcePosition = TRANSFER_HOST.relative(Direction.WEST);
        BlockPos destinationPosition = TRANSFER_HOST.relative(Direction.EAST);
        CapabilityChest sourceChest = capabilityChest(helper, sourcePosition, Direction.EAST, 1_000, 0);
        CapabilityChest destinationChest = capabilityChest(helper, destinationPosition, Direction.WEST, 1_000, 0);
        FluidStack exact = new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 600);
        exact.getOrCreateTag().putString("terminalcraft_test", "public_fluid_replay");
        sourceChest.setFluid(exact);

        String dimension = helper.getLevel().dimension().location().toString();
        UUID sourceId = AdjacentForgeEndpointResolver.adjacent(dimension,
                helper.absolutePos(TRANSFER_HOST), Direction.WEST).id();
        UUID destinationId = AdjacentForgeEndpointResolver.adjacent(dimension,
                helper.absolutePos(TRANSFER_HOST), Direction.EAST).id();
        UUID operationId = UUID.randomUUID();

        AdjacentForgeDeviceAccess deniedAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(DeviceCallContext.readOnly("exact-fluid-reader")),
                helper.getLevel(), helper.absolutePos(TRANSFER_HOST));
        DeviceResult denied = deniedAccess.transferExactFluid(operationId, sourceId, destinationId,
                "minecraft:water", 600);
        helper.assertTrue(!denied.isSuccess()
                        && denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "public exact-fluid transfer must enforce device.write");
        helper.assertTrue(sourceChest.fluidAmount() == 600,
                "denied exact-fluid transfer must not mutate the source");

        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "exact-fluid-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), helper.absolutePos(TRANSFER_HOST));
        DeviceCallContext invalidCaller = new DeviceCallContext(UUID.randomUUID(), "invalid-fluid-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess invalidAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(invalidCaller), helper.getLevel(),
                helper.absolutePos(TRANSFER_HOST));
        DeviceResult emptyFluid = invalidAccess.transferExactFluid(UUID.randomUUID(), sourceId,
                destinationId, "minecraft:empty", 1);
        helper.assertTrue(!emptyFluid.isSuccess()
                        && emptyFluid.error().orElseThrow().code() == DeviceErrorCode.INVALID_ARGUMENT,
                "empty fluid must be rejected at the public argument boundary");
        helper.assertTrue(sourceChest.fluidAmount() == 600,
                "invalid empty-fluid request must not mutate the source");

        DeviceResult first = access.transferExactFluid(operationId, sourceId, destinationId,
                "minecraft:water", 600);
        helper.assertTrue(first.isSuccess() && mapBoolean(first, "complete"),
                "caller-bound exact-fluid transfer should complete");
        helper.assertTrue(destinationChest.fluidAmount() == 600
                        && destinationChest.fluidTag("terminalcraft_test").equals("public_fluid_replay"),
                "public route must preserve exact fluid identity");

        helper.setBlock(sourcePosition, Blocks.AIR);
        helper.setBlock(destinationPosition, Blocks.AIR);
        DeviceResult replay = access.transferExactFluid(operationId, sourceId, destinationId,
                "minecraft:water", 600);
        helper.assertTrue(replay.isSuccess() && mapBoolean(replay, "replayed")
                        && mapNumber(replay, "inserted") == 600,
                "authoritative fluid replay must precede live endpoint resolution");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidReplacementDuringRollbackEscrowsAndRecoversExactVariant(GameTestHelper helper) {
        BlockPos sourcePosition = TRANSFER_HOST.relative(Direction.WEST);
        BlockPos destinationPosition = TRANSFER_HOST.relative(Direction.EAST);
        CapabilityChest sourceChest = capabilityChest(helper, sourcePosition, Direction.EAST, 1_000, 0);
        FluidStack exact = new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 500);
        exact.getOrCreateTag().putString("terminalcraft_test", "fluid_replacement_race");
        sourceChest.setFluid(exact);
        RaceFluidChest destination = raceFluidChest(helper, destinationPosition, Direction.WEST,
                () -> helper.setBlock(sourcePosition, Blocks.BARREL));

        String dimension = helper.getLevel().dimension().location().toString();
        UUID sourceId = AdjacentForgeEndpointResolver.adjacent(dimension,
                helper.absolutePos(TRANSFER_HOST), Direction.WEST).id();
        UUID destinationId = AdjacentForgeEndpointResolver.adjacent(dimension,
                helper.absolutePos(TRANSFER_HOST), Direction.EAST).id();
        UUID operationId = UUID.randomUUID();
        DeviceCallContext admin = new DeviceCallContext(UUID.randomUUID(), "fluid-escrow-admin",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE,
                        DeviceCallContext.ESCROW_ADMIN));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(admin), helper.getLevel(), helper.absolutePos(TRANSFER_HOST));

        DeviceResult transfer = access.transferExactFluid(operationId, sourceId, destinationId,
                "minecraft:water", 500);
        helper.assertTrue(transfer.isSuccess()
                        && "escrowed".equals(mapString(transfer, "status"))
                        && mapNumber(transfer, "escrowed") == 500,
                "source replacement during fluid rollback must retain all extracted fluid in escrow");
        helper.assertTrue(helper.getBlockEntity(sourcePosition) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity,
                "source should have been replaced during destination insertion");

        DeviceResult listed = access.listFluidEscrow(32);
        helper.assertTrue(listed.isSuccess(), "authorized bounded fluid escrow listing should succeed");
        UUID escrowId = listedEscrowId(listed, operationId);
        destination.allowFill();
        DeviceResult recovered = access.recoverFluidEscrow(escrowId, destinationId);
        helper.assertTrue(recovered.isSuccess()
                        && "complete".equals(mapString(recovered, "status"))
                        && mapNumber(recovered, "inserted") == 500,
                "authorized fluid recovery should remove custody only after exact insertion");
        helper.assertTrue(destination.fluidAmount() == 500
                        && destination.fluidTag("terminalcraft_test").equals("fluid_replacement_race"),
                "fluid escrow recovery must preserve the exact tagged variant");
        helper.assertTrue(listedEscrowIdOptional(access.listFluidEscrow(32), operationId).isEmpty(),
                "completed fluid recovery must remove the escrow diagnostic entry");
        for (int call = 0; call < 4; call++) {
            helper.assertTrue(access.listFluidEscrow(32).isSuccess(),
                    "fluid escrow listing should be admitted through the work boundary");
        }
        DeviceResult limited = access.listFluidEscrow(32);
        helper.assertTrue(!limited.isSuccess()
                        && limited.error().orElseThrow().code() == DeviceErrorCode.BUSY
                        && limited.error().orElseThrow().retryable(),
                "fluid escrow administration must share the server-owned work budget");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void callerBoundEnergyMutationIsAuthorizedPartialAndNonReversible(GameTestHelper helper) {
        BlockPos host = TARGET.relative(Direction.NORTH);
        CapabilityChest chest = capabilityChest(helper, 0, 1_000);
        UUID endpointId = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), helper.absolutePos(host),
                Direction.SOUTH).id();

        AdjacentForgeDeviceAccess reader = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(DeviceCallContext.readOnly("energy-reader")),
                helper.getLevel(), helper.absolutePos(host));
        DeviceResult denied = reader.call(endpointId, "energy.receive", List.of(DeviceValue.of(1_250)));
        helper.assertTrue(!denied.isSuccess()
                        && denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "caller-bound FE mutation must require device.write");
        helper.assertTrue(chest.energyStored() == 0, "denied FE mutation must not reach the storage");

        DeviceCallContext writerContext = new DeviceCallContext(UUID.randomUUID(), "energy-writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess writer = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writerContext), helper.getLevel(), helper.absolutePos(host));
        DeviceResult partial = writer.call(endpointId, "energy.receive", List.of(DeviceValue.of(1_250)));
        helper.assertTrue(partial.isSuccess()
                        && "partial".equals(mapString(partial, "status"))
                        && "FE".equals(mapString(partial, "unit"))
                        && mapNumber(partial, "requested") == 1_250
                        && mapNumber(partial, "executed") == 1_000
                        && !mapBoolean(partial, "reversible")
                        && !mapBoolean(partial, "retry_safe"),
                "partial FE receive must report authoritative single-attempt non-reversible semantics");
        helper.assertTrue(chest.energyStored() == 1_000,
                "partial FE receive must mutate only the executed amount");

        DeviceResult extracted = writer.call(endpointId, "energy.extract", List.of(DeviceValue.of(300)));
        helper.assertTrue(extracted.isSuccess()
                        && "complete".equals(mapString(extracted, "status"))
                        && mapNumber(extracted, "executed") == 300,
                "FE extraction must report its authoritative executed amount");
        helper.assertTrue(chest.energyStored() == 700, "executed extraction must be observable");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void energyReplacementDuringSimulationNeverMutatesStaleStorage(GameTestHelper helper) {
        final CapabilityChest[] replacement = new CapabilityChest[1];
        RaceEnergyChest original = raceEnergyChest(helper, TARGET, EXPOSED_SIDE,
                0, false, () -> replacement[0] = capabilityChest(helper, 0, 2_000));
        ForgeCapabilityDevice exposed = device(helper, EXPOSED_SIDE);

        GenericCapabilityDevice.TransferOutcome result = exposed.receiveEnergy(500);
        helper.assertTrue(result.simulated() == 500 && result.executed() == 0 && !result.complete(),
                "replacement after simulation must fail closed before FE execution");
        helper.assertTrue(original.energyStored() == 0,
                "the stale energy capability must never receive FE after replacement");
        helper.assertTrue(replacement[0] != null && replacement[0].energyStored() == 0,
                "a replacement must not receive FE through the stale operation");

        GenericCapabilityDevice.TransferOutcome fresh = exposed.receiveEnergy(500);
        helper.assertTrue(fresh.complete() && replacement[0].energyStored() == 500,
                "a fresh operation must reacquire and mutate the compatible replacement");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void energyReplacementDuringExtractSimulationNeverDrainsStaleStorage(GameTestHelper helper) {
        final CapabilityChest[] replacement = new CapabilityChest[1];
        RaceEnergyChest original = raceEnergyChest(helper, TARGET, EXPOSED_SIDE,
                800, true, () -> {
                    replacement[0] = capabilityChest(helper, 0, 2_000);
                    replacement[0].setEnergy(900);
                });
        ForgeCapabilityDevice exposed = device(helper, EXPOSED_SIDE);

        GenericCapabilityDevice.TransferOutcome result = exposed.extractEnergy(500);
        helper.assertTrue(result.simulated() == 500 && result.executed() == 0 && !result.complete(),
                "replacement after extract simulation must fail closed before FE execution");
        helper.assertTrue(original.energyStored() == 800,
                "the stale energy capability must not be drained after replacement");
        helper.assertTrue(replacement[0] != null && replacement[0].energyStored() == 900,
                "a replacement must not be drained through the stale operation");

        GenericCapabilityDevice.TransferOutcome fresh = exposed.extractEnergy(500);
        helper.assertTrue(fresh.complete() && replacement[0].energyStored() == 400,
                "a fresh extraction must reacquire and mutate the compatible replacement");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void energyCapabilityIsSideAwareAndReacquiredAfterReplacement(GameTestHelper helper) {
        CapabilityChest original = capabilityChest(helper, 0, 1_000);
        ForgeCapabilityDevice exposed = device(helper, EXPOSED_SIDE);
        ForgeCapabilityDevice hidden = device(helper, Direction.SOUTH);

        helper.assertTrue(exposed.hasEnergyStorage(), "energy capability should be visible on its exposed side");
        helper.assertTrue(!hidden.hasEnergyStorage(), "energy capability should be hidden on other sides");
        GenericCapabilityDevice.TransferOutcome first = exposed.receiveEnergy(800);
        helper.assertTrue(first.complete(), "initial energy receive should complete");
        helper.assertTrue(original.energyStored() == 800, "initial storage should contain the executed FE");

        original.invalidateCaps();
        helper.setBlock(TARGET, Blocks.BARREL);
        helper.assertTrue(!exposed.hasEnergyStorage(), "replacement without energy capability should be observed");
        helper.assertTrue(exposed.receiveEnergy(100).executed() == 0,
                "mutation must not use the invalidated energy storage");

        CapabilityChest replacement = capabilityChest(helper, 0, 2_000);
        helper.assertTrue(exposed.hasEnergyStorage(), "energy capability should be reacquired from replacement");
        GenericCapabilityDevice.TransferOutcome second = exposed.receiveEnergy(1_250);
        helper.assertTrue(second.complete(), "replacement energy receive should complete");
        helper.assertTrue(replacement.energyStored() == 1_250, "replacement storage should receive FE");
        helper.assertTrue(original.energyStored() == 800, "invalidated original storage must remain unchanged");
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
                new AssertionError("fluid escrow listing did not contain operation " + operationId));
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

    private static RaceFluidChest raceFluidChest(GameTestHelper helper, BlockPos relativePosition,
                                                   Direction exposedSide, Runnable replacement) {
        helper.setBlock(relativePosition, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(relativePosition);
        RaceFluidChest chest = new RaceFluidChest(absolute,
                helper.getLevel().getBlockState(absolute), exposedSide, replacement);
        helper.getLevel().setBlockEntity(chest);
        return chest;
    }

    private static RaceEnergyChest raceEnergyChest(GameTestHelper helper, BlockPos relativePosition,
                                                   Direction exposedSide, int initialEnergy,
                                                   boolean replaceOnExtract, Runnable replacement) {
        helper.setBlock(relativePosition, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(relativePosition);
        RaceEnergyChest chest = new RaceEnergyChest(absolute,
                helper.getLevel().getBlockState(absolute), exposedSide, initialEnergy,
                replaceOnExtract, replacement);
        helper.getLevel().setBlockEntity(chest);
        return chest;
    }

    private static ForgeCapabilityDevice device(GameTestHelper helper, Direction side) {
        return new ForgeCapabilityDevice(helper.getLevel(), helper.absolutePos(TARGET), side);
    }

    private static CapabilityChest capabilityChest(GameTestHelper helper, int fluidCapacity, int energyCapacity) {
        helper.setBlock(TARGET, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(TARGET);
        CapabilityChest chest = new CapabilityChest(absolute, helper.getLevel().getBlockState(absolute),
                EXPOSED_SIDE, fluidCapacity, energyCapacity);
        helper.getLevel().setBlockEntity(chest);
        return chest;
    }

    private static CapabilityChest capabilityChest(GameTestHelper helper, BlockPos relativePosition,
                                                    Direction exposedSide, int fluidCapacity,
                                                    int energyCapacity) {
        helper.setBlock(relativePosition, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(relativePosition);
        CapabilityChest chest = new CapabilityChest(absolute, helper.getLevel().getBlockState(absolute),
                exposedSide, fluidCapacity, energyCapacity);
        helper.getLevel().setBlockEntity(chest);
        return chest;
    }

    private static final class RaceFluidChest extends ChestBlockEntity {
        private final Direction exposedSide;
        private final Runnable replacement;
        private boolean reject = true;
        private final FluidTank tank = new FluidTank(1_000) {
            @Override public int fill(FluidStack resource, FluidAction action) {
                if (reject && action.execute()) {
                    replacement.run();
                    return 0;
                }
                return super.fill(resource, action);
            }
        };
        private LazyOptional<IFluidHandler> capability = LazyOptional.of(() -> tank);

        private RaceFluidChest(BlockPos position,
                               net.minecraft.world.level.block.state.BlockState state,
                               Direction exposedSide, Runnable replacement) {
            super(position, state);
            this.exposedSide = exposedSide;
            this.replacement = replacement;
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> requested,
                                                          @Nullable Direction side) {
            if (requested == ForgeCapabilities.FLUID_HANDLER) {
                return side == exposedSide ? capability.cast() : LazyOptional.empty();
            }
            return super.getCapability(requested, side);
        }

        @Override public void invalidateCaps() {
            super.invalidateCaps();
            capability.invalidate();
        }

        private void allowFill() { reject = false; }
        private int fluidAmount() { return tank.getFluidAmount(); }
        private String fluidTag(String key) {
            FluidStack fluid = tank.getFluid();
            return fluid.hasTag() ? fluid.getTag().getString(key) : "";
        }
    }

    private static final class RaceEnergyChest extends ChestBlockEntity {
        private final Direction exposedSide;
        private final EnergyStorage energy;
        private LazyOptional<IEnergyStorage> capability;

        private RaceEnergyChest(BlockPos position,
                                net.minecraft.world.level.block.state.BlockState state,
                                Direction exposedSide, int initialEnergy,
                                boolean replaceOnExtract, Runnable replacement) {
            super(position, state);
            this.exposedSide = exposedSide;
            this.energy = new EnergyStorage(1_000) {
                private boolean replaced;
                private void replaceOnce(boolean simulate, boolean extraction) {
                    if (simulate && extraction == replaceOnExtract && !replaced) {
                        replaced = true;
                        replacement.run();
                    }
                }
                @Override public int receiveEnergy(int maxReceive, boolean simulate) {
                    int accepted = super.receiveEnergy(maxReceive, simulate);
                    replaceOnce(simulate, false);
                    return accepted;
                }
                @Override public int extractEnergy(int maxExtract, boolean simulate) {
                    int accepted = super.extractEnergy(maxExtract, simulate);
                    replaceOnce(simulate, true);
                    return accepted;
                }
            };
            energy.receiveEnergy(initialEnergy, false);
            capability = LazyOptional.of(() -> energy);
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> requested,
                                                          @Nullable Direction side) {
            if (requested == ForgeCapabilities.ENERGY) {
                return side == exposedSide ? capability.cast() : LazyOptional.empty();
            }
            return super.getCapability(requested, side);
        }

        @Override public void invalidateCaps() {
            super.invalidateCaps();
            capability.invalidate();
        }

        private int energyStored() { return energy.getEnergyStored(); }
    }

    private static final class CapabilityChest extends ChestBlockEntity {
        private final Direction exposedSide;
        private final FluidTank tank;
        private final EnergyStorage energy;
        private LazyOptional<IFluidHandler> fluidCapability;
        private LazyOptional<IEnergyStorage> energyCapability;

        private CapabilityChest(BlockPos position, net.minecraft.world.level.block.state.BlockState state,
                                Direction exposedSide, int fluidCapacity, int energyCapacity) {
            super(position, state);
            this.exposedSide = exposedSide;
            tank = new FluidTank(Math.max(1, fluidCapacity));
            energy = new EnergyStorage(Math.max(1, energyCapacity));
            fluidCapability = fluidCapacity > 0 ? LazyOptional.of(() -> tank) : LazyOptional.empty();
            energyCapability = energyCapacity > 0 ? LazyOptional.of(() -> energy) : LazyOptional.empty();
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability,
                                                          @Nullable Direction side) {
            if (side == exposedSide && capability == ForgeCapabilities.FLUID_HANDLER) {
                return fluidCapability.cast();
            }
            if (side == exposedSide && capability == ForgeCapabilities.ENERGY) {
                return energyCapability.cast();
            }
            return super.getCapability(capability, side);
        }

        @Override
        public void invalidateCaps() {
            super.invalidateCaps();
            fluidCapability.invalidate();
            energyCapability.invalidate();
        }

        private void setFluid(FluidStack fluid) { tank.setFluid(fluid.copy()); }

        private int fluidAmount() {
            FluidStack fluid = tank.getFluid();
            return fluid.isEmpty() ? 0 : fluid.getAmount();
        }

        private String fluidTag(String key) {
            FluidStack fluid = tank.getFluid();
            return fluid.hasTag() ? fluid.getTag().getString(key) : "";
        }

        private void setEnergy(int amount) {
            energy.receiveEnergy(amount, false);
        }

        private int energyStored() {
            return energy.getEnergyStored();
        }
    }
}
