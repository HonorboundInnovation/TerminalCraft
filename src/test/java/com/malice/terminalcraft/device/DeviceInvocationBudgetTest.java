package com.malice.terminalcraft.device;

/** Headless boundary tests for shared logical-tick device call admission. */
public final class DeviceInvocationBudgetTest {
    private DeviceInvocationBudgetTest() {}

    public static void main(String[] args) {
        callLimitIsPerAuthorityAndResetsNextTick();
        workLimitRejectsWithoutConsumingCapacity();
        callerBucketCountIsBounded();
        invalidRequestsAreRejected();
        workEstimatesAreConservativeAndBounded();
        System.out.println("Device invocation budget tests: OK");
    }

    private static void callLimitIsPerAuthorityAndResetsNextTick() {
        DeviceInvocationBudget budget = new DeviceInvocationBudget();
        for (int call = 0; call < DeviceInvocationBudget.MAX_CALLS_PER_TICK; call++) {
            assertEquals(DeviceInvocationBudget.Admission.ADMITTED,
                    budget.admit("host#a", 10, 1), "call admitted at boundary");
        }
        assertEquals(DeviceInvocationBudget.Admission.CALL_LIMIT,
                budget.admit("host#a", 10, 1), "one call over limit rejected");
        assertEquals(DeviceInvocationBudget.Admission.ADMITTED,
                budget.admit("host#b", 10, 1), "different authority has independent budget");
        assertEquals(DeviceInvocationBudget.Admission.ADMITTED,
                budget.admit("host#a", 11, 1), "next tick resets budget");
    }

    private static void workLimitRejectsWithoutConsumingCapacity() {
        DeviceInvocationBudget budget = new DeviceInvocationBudget();
        assertEquals(DeviceInvocationBudget.Admission.ADMITTED,
                budget.admit("host#a", 20, DeviceInvocationBudget.MAX_WORK_UNITS_PER_TICK - 1),
                "work below boundary admitted");
        assertEquals(DeviceInvocationBudget.Admission.WORK_LIMIT,
                budget.admit("host#a", 20, 2), "work above boundary rejected");
        assertEquals(DeviceInvocationBudget.Admission.ADMITTED,
                budget.admit("host#a", 20, 1), "rejection does not consume remaining work");
    }

    private static void callerBucketCountIsBounded() {
        DeviceInvocationBudget budget = new DeviceInvocationBudget();
        for (int caller = 0; caller < DeviceInvocationBudget.MAX_BUCKETS_PER_TICK; caller++) {
            assertEquals(DeviceInvocationBudget.Admission.ADMITTED,
                    budget.admit("caller-" + caller, 30, 1), "caller bucket admitted");
        }
        assertEquals(DeviceInvocationBudget.Admission.BUCKET_LIMIT,
                budget.admit("overflow", 30, 1), "caller bucket overflow rejected");
    }

    private static void invalidRequestsAreRejected() {
        DeviceInvocationBudget budget = new DeviceInvocationBudget();
        assertThrows(() -> budget.admit("", 1, 1), "blank authority");
        assertThrows(() -> budget.admit("host#a", 1, 0), "zero work");
        assertThrows(() -> budget.admit("host#a", 1,
                DeviceInvocationBudget.MAX_WORK_UNITS_PER_TICK + 1), "oversized work");
    }

    private static void workEstimatesAreConservativeAndBounded() {
        assertEquals(GenericCapabilityDevice.MAX_INVENTORY_SLOTS,
                AdjacentForgeDeviceAccess.callWorkUnits("inventory.count"), "inventory scan cost");
        assertEquals(GenericCapabilityDevice.MAX_INVENTORY_SLOTS,
                AdjacentForgeDeviceAccess.callWorkUnits("storage.query"), "storage query cost");
        assertEquals(GenericCapabilityDevice.MAX_FLUID_TANKS,
                AdjacentForgeDeviceAccess.callWorkUnits("fluid.tanks"), "fluid scan cost");
        assertEquals(1, AdjacentForgeDeviceAccess.callWorkUnits("energy.status"), "energy cost");
        assertEquals(1, AdjacentForgeDeviceAccess.callWorkUnits(null), "malformed method still charged");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertThrows(Runnable action, String message) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message + ": expected exception");
    }
}
