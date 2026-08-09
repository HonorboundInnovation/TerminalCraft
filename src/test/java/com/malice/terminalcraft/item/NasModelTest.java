package com.malice.terminalcraft.item;

/** Headless contract tests for the tiered NAS media model. */
public final class NasModelTest {
    private NasModelTest() {}

    public static void main(String[] args) {
        require(SolidStateDriveTier.BASIC.itemCapacity() < SolidStateDriveTier.ADVANCED.itemCapacity(),
                "advanced media has more item capacity");
        require(SolidStateDriveTier.ADVANCED.itemCapacity() < SolidStateDriveTier.QUANTUM.itemCapacity(),
                "quantum media has more item capacity");
        require(SolidStateDriveTier.BASIC.fluidCapacityMb() < SolidStateDriveTier.ADVANCED.fluidCapacityMb(),
                "advanced media has more fluid capacity");
        require(SolidStateDriveTier.ADVANCED.fluidCapacityMb() < SolidStateDriveTier.QUANTUM.fluidCapacityMb(),
                "quantum media has more fluid capacity");
        require(SolidStateDriveTier.parse("advanced") == SolidStateDriveTier.ADVANCED,
                "tier identifiers are stable");
        System.out.println("NAS model tests: OK");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
