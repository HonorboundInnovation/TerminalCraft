package com.malice.terminalcraft.integration.create;

import com.malice.terminalcraft.integration.OptionalDeviceEndpointRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.integration.OptionalSensorProbeRegistry;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity;

import java.util.Optional;

/** Native Create machine telemetry and controls, loaded only after Forge confirms Create is present. */
public final class CreateDeviceIntegration implements OptionalIntegration {
    @Override
    public void initialize() {
        OptionalDeviceEndpointRegistry.register(context -> isSupported(context.blockEntity())
                ? Optional.of(new CreateDeviceEndpoint(context)) : Optional.empty());
        OptionalSensorProbeRegistry.register(CreateSensorProbe::read);
    }

    private static boolean isSupported(Object blockEntity) {
        return blockEntity instanceof KineticBlockEntity
                || blockEntity instanceof ThresholdSwitchBlockEntity
                || blockEntity instanceof RedstoneLinkBlockEntity
                || blockEntity instanceof SequencedGearshiftBlockEntity;
    }
}
