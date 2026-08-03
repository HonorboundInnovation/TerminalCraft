package com.malice.terminalcraft;

/** Runtime build identity sourced from the release JAR manifest. */
public final class BuildInfo {
    private BuildInfo() {}

    public static String version() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    public static String systemIdentity() {
        return "TerminalCraft " + version() + " Minecraft-1.20.1 Forge-47.4.10";
    }
}
