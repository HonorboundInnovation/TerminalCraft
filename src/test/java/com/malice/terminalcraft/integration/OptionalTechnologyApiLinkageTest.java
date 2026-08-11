package com.malice.terminalcraft.integration;

import java.util.List;

/** Focused smoke test that links every isolated technology adapter against the pinned APIs. */
public final class OptionalTechnologyApiLinkageTest {
    private OptionalTechnologyApiLinkageTest() {}

    public static void main(String[] args) throws Exception {
        List<String> adapters = List.of(
                "com.malice.terminalcraft.integration.create.CreateMonitorIntegration",
                "com.malice.terminalcraft.integration.create.CreateDeviceIntegration",
                "com.malice.terminalcraft.integration.create.CreateDeviceEndpoint",
                "com.malice.terminalcraft.integration.create.CreateSensorProbe",
                "com.malice.terminalcraft.integration.mekanism.MekanismIntegration",
                "com.malice.terminalcraft.integration.mekanism.MekanismCapabilityView",
                "com.malice.terminalcraft.integration.mekanism.MekanismChemicalStorage",
                "com.malice.terminalcraft.integration.mekanism.MekanismDeviceEndpoint",
                "com.malice.terminalcraft.integration.mekanism.MekanismSensorProbe",
                "com.malice.terminalcraft.integration.securitycraft.SecurityCraftIntegration",
                "com.malice.terminalcraft.integration.securitycraft.SecurityCraftDeviceEndpoint",
                "com.malice.terminalcraft.integration.securitycraft.SecurityCraftSensorProbe",
                "com.malice.terminalcraft.integration.securitycraft.SecurityCraftSeaBoatEndpoint");
        ClassLoader loader = OptionalTechnologyApiLinkageTest.class.getClassLoader();
        for (String adapter : adapters) {
            Class.forName(adapter, true, loader);
        }
        System.out.println("Optional technology API linkage tests: OK");
    }
}
