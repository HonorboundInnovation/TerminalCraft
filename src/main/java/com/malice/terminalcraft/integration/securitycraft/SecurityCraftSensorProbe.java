package com.malice.terminalcraft.integration.securitycraft;

import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorReading;
import net.geforcemods.securitycraft.api.ICustomizable;
import net.geforcemods.securitycraft.api.IEMPAffected;
import net.geforcemods.securitycraft.api.ILockable;
import net.geforcemods.securitycraft.api.IModuleInventory;
import net.geforcemods.securitycraft.api.IOwnable;
import net.geforcemods.securitycraft.api.IPasscodeProtected;
import net.geforcemods.securitycraft.api.Option;
import net.geforcemods.securitycraft.blockentities.AlarmBlockEntity;
import net.geforcemods.securitycraft.blockentities.BlockChangeDetectorBlockEntity;
import net.geforcemods.securitycraft.blockentities.IMSBlockEntity;
import net.geforcemods.securitycraft.blockentities.InventoryScannerBlockEntity;
import net.geforcemods.securitycraft.blockentities.LaserBlockBlockEntity;
import net.geforcemods.securitycraft.blockentities.PortableRadarBlockEntity;
import net.geforcemods.securitycraft.blockentities.ProjectorBlockEntity;
import net.geforcemods.securitycraft.blockentities.RetinalScannerBlockEntity;
import net.geforcemods.securitycraft.blockentities.RiftStabilizerBlockEntity;
import net.geforcemods.securitycraft.blockentities.SecureRedstoneInterfaceBlockEntity;
import net.geforcemods.securitycraft.blockentities.SecurityCameraBlockEntity;
import net.geforcemods.securitycraft.blockentities.SonicSecuritySystemBlockEntity;
import net.geforcemods.securitycraft.blockentities.TrackMineBlockEntity;
import net.geforcemods.securitycraft.blockentities.TrophySystemBlockEntity;
import net.geforcemods.securitycraft.blockentities.UsernameLoggerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Locale;
import java.util.Optional;

/** Safe PLC/SCADA telemetry for the complete SecurityCraft ownable block-entity family. */
final class SecurityCraftSensorProbe {
    private SecurityCraftSensorProbe() {}

    static Optional<SensorReading> read(ServerLevel level, BlockPos target, Direction side,
                                        SensorChannel channel, long time) {
        if (channel.kind() != SensorKind.MACHINE) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(target);
        if (!(blockEntity instanceof IOwnable)) return Optional.empty();
        return Optional.ofNullable(machine(blockEntity, channel, time));
    }

    private static SensorReading machine(BlockEntity blockEntity, SensorChannel channel, long time) {
        return switch (channel.metric()) {
            case "active", "armed" -> numeric(channel, active(blockEntity) ? 1 : 0, "boolean", time);
            case "powered" -> numeric(channel, powered(blockEntity) ? 1 : 0, "boolean", time);
            case "disabled" -> numeric(channel, disabled(blockEntity) ? 1 : 0, "boolean", time);
            case "enabled" -> numeric(channel, disabled(blockEntity) ? 0 : 1, "boolean", time);
            case "locked" -> numeric(channel,
                    blockEntity instanceof ILockable lockable && lockable.isLockedBySSS() ? 1 : 0,
                    "boolean", time);
            case "passcode_configured" -> numeric(channel,
                    blockEntity instanceof IPasscodeProtected protectedBlock && protectedBlock.getPasscode() != null ? 1 : 0,
                    "boolean", time);
            case "modules", "module_count" -> numeric(channel,
                    blockEntity instanceof IModuleInventory inventory ? inventory.getInsertedModules().size() : 0,
                    "modules", time);
            case "enabled_modules" -> numeric(channel, enabledModules(blockEntity), "modules", time);
            case "entries", "audit_entries" -> numeric(channel, auditEntries(blockEntity), "entries", time);
            case "range" -> range(blockEntity, channel, time);
            case "signal_length" -> signalLength(blockEntity, channel, time);
            case "output", "redstone_output" -> numeric(channel, redstoneOutput(blockEntity), "signal", time);
            case "shutdown" -> numeric(channel,
                    blockEntity instanceof IEMPAffected affected && affected.isShutDown() ? 1 : 0,
                    "boolean", time);
            case "target", "target_present" -> numeric(channel,
                    blockEntity instanceof TrophySystemBlockEntity trophy && trophy.getTarget() != null ? 1 : 0,
                    "boolean", time);
            case "mode" -> mode(blockEntity, channel, time);
            case "fault" -> numeric(channel, 0, "boolean", time);
            default -> null;
        };
    }

    private static boolean active(BlockEntity blockEntity) {
        if (blockEntity instanceof TrackMineBlockEntity mine) return mine.isActive();
        if (blockEntity instanceof ProjectorBlockEntity projector) return projector.isActive();
        if (blockEntity instanceof SonicSecuritySystemBlockEntity sonic) return sonic.isActive();
        if (blockEntity instanceof SecurityCameraBlockEntity camera) return !camera.isDisabled() && !camera.isShutDown();
        if (blockEntity instanceof LaserBlockBlockEntity laser) return laser.isEnabled();
        return !disabled(blockEntity);
    }

    private static boolean powered(BlockEntity blockEntity) {
        if (blockEntity instanceof AlarmBlockEntity alarm) return alarm.isPowered();
        if (blockEntity instanceof InventoryScannerBlockEntity scanner) return scanner.isProvidingPower();
        if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity redstone) return redstone.getPower() > 0;
        return blockEntity.getBlockState().getOptionalValue(BlockStateProperties.POWERED).orElse(false);
    }

    private static boolean disabled(BlockEntity blockEntity) {
        if (blockEntity instanceof ICustomizable customizable) {
            for (Option<?> option : customizable.customOptions()) {
                if (option.getName().equals("disabled") && option.get() instanceof Boolean value) return value;
            }
        }
        return false;
    }

    private static int enabledModules(BlockEntity blockEntity) {
        if (!(blockEntity instanceof IModuleInventory inventory)) return 0;
        int count = 0;
        for (var module : inventory.acceptedModules()) if (inventory.isModuleEnabled(module)) count++;
        return count;
    }

    private static int auditEntries(BlockEntity blockEntity) {
        if (blockEntity instanceof UsernameLoggerBlockEntity logger) {
            int count = 0;
            for (UsernameLoggerBlockEntity.UsernameLoggerEntry entry : logger.getEntries()) if (entry != null) count++;
            return count;
        }
        if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) return detector.getFilteredEntries().size();
        return 0;
    }

    private static SensorReading range(BlockEntity blockEntity, SensorChannel channel, long time) {
        double range;
        if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) range = detector.getRange();
        else if (blockEntity instanceof PortableRadarBlockEntity radar) range = radar.getSearchRadius();
        else if (blockEntity instanceof RiftStabilizerBlockEntity rift) range = rift.getRange();
        else if (blockEntity instanceof RetinalScannerBlockEntity retinal) range = retinal.getMaximumDistance();
        else return null;
        return numeric(channel, range, "blocks", time);
    }

    private static SensorReading signalLength(BlockEntity blockEntity, SensorChannel channel, long time) {
        int ticks;
        if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) ticks = detector.getSignalLength();
        else if (blockEntity instanceof InventoryScannerBlockEntity scanner) ticks = scanner.getSignalLength();
        else if (blockEntity instanceof LaserBlockBlockEntity laser) ticks = laser.getSignalLength();
        else if (blockEntity instanceof RetinalScannerBlockEntity retinal) ticks = retinal.getSignalLength();
        else if (blockEntity instanceof RiftStabilizerBlockEntity rift) ticks = rift.getSignalLength();
        else return null;
        return numeric(channel, ticks, "ticks", time);
    }

    private static int redstoneOutput(BlockEntity blockEntity) {
        if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity redstone) return redstone.getRedstonePowerOutput();
        if (blockEntity instanceof InventoryScannerBlockEntity scanner) return scanner.isProvidingPower() ? 15 : 0;
        return powered(blockEntity) ? 15 : 0;
    }

    private static SensorReading mode(BlockEntity blockEntity, SensorChannel channel, long time) {
        String value;
        if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) value = detector.getMode().name();
        else if (blockEntity instanceof IMSBlockEntity ims) value = ims.getTargetingMode().name();
        else if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity redstone) value = redstone.isSender() ? "sender" : "receiver";
        else return null;
        return SensorReading.text(channel.name(), channel.kind(), channel.metric(),
                value.toLowerCase(Locale.ROOT), time);
    }

    private static SensorReading numeric(SensorChannel channel, double value, String unit, long time) {
        return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(), value, unit, time);
    }
}
