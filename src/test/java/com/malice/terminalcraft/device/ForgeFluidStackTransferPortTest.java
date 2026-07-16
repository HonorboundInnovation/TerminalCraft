package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;

import java.util.Set;
import java.util.UUID;

/** Headless exact FluidStack identity, copy-boundary, rollback, and capability tests. */
public final class ForgeFluidStackTransferPortTest {
    private static final UUID SOURCE = new UUID(0, 401);
    private static final UUID DESTINATION = new UUID(0, 402);
    private static final DeviceCallContext WRITER = new DeviceCallContext(new UUID(0, 403), "writer",
            Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

    private ForgeFluidStackTransferPortTest() {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        drainPreservesTagAndReturnsCopy();
        fillPreservesTagAndReturnsExactRemainder();
        coordinatorRollsBackExactTaggedFluid();
        invalidHandlerAmountsAreRejected();
        malformedDrainResultsAreRejected();
        multiTankDrainRequestsAcrossMatchingVariant();
        incompatibleDestinationReturnsExactRemainder();
        drainRaceUsesExecutedExactVariant();
        unavailableCapabilityFailsWithoutInventingFluid();
        System.out.println("Forge FluidStack transfer port tests: OK");
    }

    private static void drainPreservesTagAndReturnsCopy() {
        FluidTank tank = new FluidTank(1_000);
        tank.setFluid(taggedWater(700, "warm"));
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> tank);

        FluidStack drained = port.drain("minecraft:water", 300);
        assertEquals(300, drained.getAmount(), "requested amount drained");
        assertEquals("warm", drained.getTag().getString("terminalcraft_test"), "tag preserved");
        assertEquals(400, tank.getFluidAmount(), "source mutated by executed amount");
        drained.setAmount(1);
        assertEquals(400, tank.getFluidAmount(), "returned payload does not alias source");
    }

    private static void fillPreservesTagAndReturnsExactRemainder() {
        FluidTank tank = new FluidTank(250);
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> tank);
        FluidStack offered = taggedWater(600, "cool");

        FluidStack remainder = port.fill(offered);
        assertEquals(600, offered.getAmount(), "caller payload remains unchanged");
        assertEquals(250, tank.getFluidAmount(), "destination accepts capacity");
        assertEquals(350, remainder.getAmount(), "exact remainder returned");
        assertTrue(offered.getFluid() == tank.getFluid().getFluid() && java.util.Objects.equals(offered.getTag(), tank.getFluid().getTag()), "inserted tag preserved");
        assertTrue(offered.getFluid() == remainder.getFluid() && java.util.Objects.equals(offered.getTag(), remainder.getTag()), "remainder tag preserved");
    }

    private static void coordinatorRollsBackExactTaggedFluid() {
        FluidTank sourceTank = new FluidTank(1_000);
        sourceTank.setFluid(taggedWater(800, "mineral"));
        FluidTank destinationTank = new FluidTank(300);
        ForgeFluidStackTransferPort source = new ForgeFluidStackTransferPort(() -> sourceTank);
        ForgeFluidStackTransferPort destination = new ForgeFluidStackTransferPort(() -> destinationTank);
        ExactFluidTransferCoordinator<FluidStack> coordinator = new ExactFluidTransferCoordinator<>();

        var result = coordinator.transfer(WRITER, new UUID(0, 404), SOURCE, source, DESTINATION,
                destination, "minecraft:water", 700);

        assertEquals(ExactFluidTransferCoordinator.Status.PARTIAL, result.status(), "partial status");
        assertEquals(300, result.insertedMb(), "destination accepted capacity");
        assertEquals(400, result.rolledBackMb(), "remainder restored to source");
        assertEquals(500, sourceTank.getFluidAmount(), "source holds unrequested plus rollback");
        assertEquals("mineral", sourceTank.getFluid().getTag().getString("terminalcraft_test"),
                "rollback tag preserved");
        assertEquals("mineral", destinationTank.getFluid().getTag().getString("terminalcraft_test"),
                "destination tag preserved");
    }

    private static void invalidHandlerAmountsAreRejected() {
        IFluidHandler invalid = new FluidTank(1_000) {
            @Override public int fill(FluidStack resource, FluidAction action) {
                return resource.getAmount() + 1;
            }
        };
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> invalid);
        assertThrows(() -> port.fill(taggedWater(100, "invalid")), "oversized fill result rejected");
    }

    private static void malformedDrainResultsAreRejected() {
        IFluidHandler handler = new FluidTank(1_000) {
            { setFluid(taggedWater(500, "oversized")); }
            @Override public FluidStack drain(FluidStack resource, FluidAction action) {
                FluidStack result = resource.copy();
                result.setAmount(resource.getAmount() + 1);
                return result;
            }
        };
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> handler);
        assertThrows(() -> port.drain("minecraft:water", 100),
                "oversized exact drain result rejected");
    }

    private static void multiTankDrainRequestsAcrossMatchingVariant() {
        AggregatingTwoTankHandler handler = new AggregatingTwoTankHandler(
                taggedWater(200, "shared"), taggedWater(350, "shared"));
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> handler);
        FluidStack drained = port.drain("minecraft:water", 500);
        assertEquals(500, drained.getAmount(), "same exact variant aggregates across tanks");
        assertEquals("shared", drained.getTag().getString("terminalcraft_test"),
                "multi-tank drain preserves exact variant");
        assertEquals(50, handler.totalAmount(), "only requested amount is removed");
    }

    private static void incompatibleDestinationReturnsExactRemainder() {
        FluidTank tank = new FluidTank(1_000);
        tank.setFluid(new FluidStack(Fluids.LAVA, 400));
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> tank);
        FluidStack offered = taggedWater(250, "incompatible");
        FluidStack remainder = port.fill(offered);
        assertEquals(250, remainder.getAmount(), "incompatible fluid is fully rejected");
        assertTrue(offered.isFluidStackIdentical(remainder),
                "incompatible remainder preserves exact identity");
        assertEquals(400, tank.getFluidAmount(), "destination remains unchanged");
    }

    private static void drainRaceUsesExecutedExactVariant() {
        IFluidHandler handler = new FluidTank(1_000) {
            { setFluid(taggedWater(500, "observed")); }
            @Override public FluidStack drain(FluidStack resource, FluidAction action) {
                FluidStack raced = taggedWater(Math.min(200, resource.getAmount()), "executed");
                if (action.execute()) getFluid().shrink(raced.getAmount());
                return raced;
            }
        };
        FluidStack drained = new ForgeFluidStackTransferPort(() -> handler)
                .drain("minecraft:water", 200);
        assertEquals(200, drained.getAmount(), "race returns executed amount");
        assertEquals("executed", drained.getTag().getString("terminalcraft_test"),
                "executed exact variant is authoritative after observation race");
    }

    private static void unavailableCapabilityFailsWithoutInventingFluid() {
        ForgeFluidStackTransferPort port = new ForgeFluidStackTransferPort(() -> null);
        assertThrows(() -> port.drain("minecraft:water", 1), "missing source capability rejected");
        assertThrows(() -> port.fill(taggedWater(1, "plain")), "missing destination capability rejected");
    }

    private static final class AggregatingTwoTankHandler implements IFluidHandler {
        private final FluidStack[] tanks;
        private AggregatingTwoTankHandler(FluidStack first, FluidStack second) {
            tanks = new FluidStack[] { first.copy(), second.copy() };
        }
        @Override public int getTanks() { return tanks.length; }
        @Override public FluidStack getFluidInTank(int tank) { return tanks[tank].copy(); }
        @Override public int getTankCapacity(int tank) { return 1_000; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return true; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) {
            int remaining = resource.getAmount();
            int drained = 0;
            for (FluidStack tank : tanks) {
                if (remaining == 0 || !tank.isFluidEqual(resource)) continue;
                int take = Math.min(remaining, tank.getAmount());
                drained += take;
                remaining -= take;
                if (action.execute()) tank.shrink(take);
            }
            if (drained == 0) return FluidStack.EMPTY;
            FluidStack result = resource.copy();
            result.setAmount(drained);
            return result;
        }
        @Override public FluidStack drain(int maxDrain, FluidAction action) {
            for (FluidStack tank : tanks) {
                if (!tank.isEmpty()) {
                    FluidStack request = tank.copy();
                    request.setAmount(maxDrain);
                    return drain(request, action);
                }
            }
            return FluidStack.EMPTY;
        }
        private int totalAmount() {
            return java.util.Arrays.stream(tanks).mapToInt(FluidStack::getAmount).sum();
        }
    }

    private static FluidStack taggedWater(int amount, String variant) {
        FluidStack stack = new FluidStack(Fluids.WATER, amount);
        CompoundTag tag = new CompoundTag();
        tag.putString("terminalcraft_test", variant);
        stack.setTag(tag);
        return stack;
    }

    private static void assertThrows(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message + ": expected exception");
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
