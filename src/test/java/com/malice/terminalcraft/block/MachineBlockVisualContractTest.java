package com.malice.terminalcraft.block;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression coverage for inset machine models that must not cull neighboring block faces. */
public final class MachineBlockVisualContractTest {
    private MachineBlockVisualContractTest() {}

    public static void main(String[] args) throws IOException {
        // Direct block construction is unavailable after vanilla's headless bootstrap freezes its
        // intrusive registry, so keep this asset/code contract test independent of that registry.
        String source = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/block/"
                + "ProgrammableLogicControllerBlock.java"));
        check(source.contains(".noOcclusion()"),
                "inset PLC cabinet must preserve adjacent block faces");
        System.out.println("Machine block visual contract tests: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
