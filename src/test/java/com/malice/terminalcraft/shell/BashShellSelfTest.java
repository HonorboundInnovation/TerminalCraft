package com.malice.terminalcraft.shell;

/**
 * Headless verification of bash scripting features.
 * Run with: ./gradlew shellSelfTest (or ./gradlew check)
 */
public class BashShellSelfTest {
    public static void main(String[] args) {
        BashShell shell = new BashShell();
        int failures = shell.runSelfTest();
        System.out.println("BashShell selftest failures=" + failures);
        if (failures != 0) {
            for (String line : shell.getOutputLines()) {
                if (line.contains("FAIL") || line.startsWith("selftest")) {
                    System.out.println(line);
                }
            }
            System.exit(1);
        }
        System.out.println("BashShell selftest: OK");
    }
}
