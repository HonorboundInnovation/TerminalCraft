package com.malice.terminalcraft.device;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.util.UUID;

/** Headless contract tests for the public exact-fluid transfer and escrow access surface. */
public final class ExactFluidAccessContractTest {
    private ExactFluidAccessContractTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        accessContractsExposeCallerBoundIdentifiersOnly();
        escrowValuesUseExactResourceAmountAndUnit();
        recoveryResultsMapToStableDeviceResults();
        System.out.println("Exact fluid access contract tests: OK");
    }

    private static void accessContractsExposeCallerBoundIdentifiersOnly() {
        try {
            ExactFluidTransferAccess.class.getMethod("transferExactFluid", UUID.class, UUID.class,
                    UUID.class, String.class, int.class);
            ExactFluidEscrowAccess.class.getMethod("listFluidEscrow", int.class);
            ExactFluidEscrowAccess.class.getMethod("recoverFluidEscrow", UUID.class, UUID.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("public fluid access contract changed", exception);
        }
    }

    private static void escrowValuesUseExactResourceAmountAndUnit() {
        UUID escrow = new UUID(0, 801);
        UUID operation = new UUID(0, 802);
        UUID source = new UUID(0, 803);
        UUID destination = new UUID(0, 804);
        FluidStack fluid = new FluidStack(Fluids.WATER, 750);
        fluid.getOrCreateTag().putString("terminalcraft_test", "preserved-in-custody");
        DeviceValue.MapValue value = AdjacentForgeDeviceAccess.fluidEscrowValue(
                new ExactFluidTransferCoordinator.EscrowEntry<>(escrow, operation, source,
                        destination, fluid));
        assertEquals(escrow.toString(), string(value, "escrow_id"), "escrow ID");
        assertEquals(operation.toString(), string(value, "operation_id"), "operation ID");
        assertEquals("minecraft:water", string(value, "resource"), "resource ID");
        assertEquals(750.0, number(value, "amount"), "amount");
        assertEquals("mB", string(value, "unit"), "unit");
    }

    private static void recoveryResultsMapToStableDeviceResults() {
        DeviceValue.MapValue partial = success(AdjacentForgeDeviceAccess.mapFluidEscrowRecovery(
                new ExactFluidTransferCoordinator.EscrowRecoveryResult(
                        ExactFluidTransferCoordinator.EscrowRecoveryStatus.PARTIAL, 125, 375)));
        assertEquals("partial", string(partial, "status"), "partial status");
        assertEquals(125.0, number(partial, "inserted"), "inserted");
        assertEquals(375.0, number(partial, "remaining"), "remaining");
        assertEquals("mB", string(partial, "unit"), "recovery unit");

        assertError(DeviceErrorCode.PERMISSION_DENIED, AdjacentForgeDeviceAccess.mapFluidEscrowRecovery(
                new ExactFluidTransferCoordinator.EscrowRecoveryResult(
                        ExactFluidTransferCoordinator.EscrowRecoveryStatus.PERMISSION_DENIED, 0, 0)));
        assertError(DeviceErrorCode.NOT_FOUND, AdjacentForgeDeviceAccess.mapFluidEscrowRecovery(
                new ExactFluidTransferCoordinator.EscrowRecoveryResult(
                        ExactFluidTransferCoordinator.EscrowRecoveryStatus.NOT_FOUND, 0, 0)));
        assertError(DeviceErrorCode.ADAPTER_ERROR, AdjacentForgeDeviceAccess.mapFluidEscrowRecovery(
                new ExactFluidTransferCoordinator.EscrowRecoveryResult(
                        ExactFluidTransferCoordinator.EscrowRecoveryStatus.DESTINATION_ERROR, 0, 250)));
    }

    private static DeviceValue.MapValue success(DeviceResult result) {
        if (!result.isSuccess()) throw new AssertionError("expected success: " + result.error());
        return (DeviceValue.MapValue) result.value().orElseThrow();
    }

    private static String string(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.StringValue) value.values().get(key)).value();
    }

    private static double number(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.NumberValue) value.values().get(key)).value();
    }

    private static void assertError(DeviceErrorCode expected, DeviceResult result) {
        if (result.isSuccess() || result.error().orElseThrow().code() != expected) {
            throw new AssertionError("expected error=" + expected + ", actual=" + result.error());
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
