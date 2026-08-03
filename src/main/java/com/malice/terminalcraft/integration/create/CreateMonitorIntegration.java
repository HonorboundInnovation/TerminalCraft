package com.malice.terminalcraft.integration.create;

import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.registry.ModRegistries;
import com.simibubi.create.api.behaviour.display.DisplayTarget;

/** Registers TerminalCraft monitor walls as Create Display Link targets. */
public final class CreateMonitorIntegration implements OptionalIntegration {
    @Override
    public void initialize() {
        DisplayTarget.BY_BLOCK_ENTITY.register(ModRegistries.MONITOR_BLOCK_ENTITY.get(),
                new TerminalCraftMonitorDisplayTarget());
    }
}
