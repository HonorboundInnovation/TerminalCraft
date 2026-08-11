package com.malice.terminalcraft.integration.securitycraft;

import com.malice.terminalcraft.device.ContextualDeviceEndpoint;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceErrorCode;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import com.malice.terminalcraft.integration.OptionalDeviceEndpointRegistry;
import net.geforcemods.securitycraft.api.ICustomizable;
import net.geforcemods.securitycraft.api.IEMPAffected;
import net.geforcemods.securitycraft.api.ILinkedAction;
import net.geforcemods.securitycraft.api.ILockable;
import net.geforcemods.securitycraft.api.IModuleInventory;
import net.geforcemods.securitycraft.api.IOwnable;
import net.geforcemods.securitycraft.api.IPasscodeProtected;
import net.geforcemods.securitycraft.api.LinkableBlockEntity;
import net.geforcemods.securitycraft.api.NamedBlockEntity;
import net.geforcemods.securitycraft.api.Option;
import net.geforcemods.securitycraft.api.Owner;
import net.geforcemods.securitycraft.blockentities.AlarmBlockEntity;
import net.geforcemods.securitycraft.blockentities.BlockChangeDetectorBlockEntity;
import net.geforcemods.securitycraft.blockentities.DisplayCaseBlockEntity;
import net.geforcemods.securitycraft.blockentities.FrameBlockEntity;
import net.geforcemods.securitycraft.blockentities.IMSBlockEntity;
import net.geforcemods.securitycraft.blockentities.InventoryScannerBlockEntity;
import net.geforcemods.securitycraft.blockentities.KeycardLockBlockEntity;
import net.geforcemods.securitycraft.blockentities.KeycardReaderBlockEntity;
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
import net.geforcemods.securitycraft.entity.sentry.Sentry;
import net.geforcemods.securitycraft.items.ModuleItem;
import net.geforcemods.securitycraft.misc.ModuleType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Native, secret-safe projection of every ownable SecurityCraft block entity. */
final class SecurityCraftDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final int MAX_AUDIT_ENTRIES = 64;
    private static final DeviceMethodDescriptor STATUS = read("securitycraft.status",
            "Operational SecurityCraft status; never includes passcodes, salts, access lists, or radio frequencies",
            DeviceValueType.MAP);
    private static final DeviceMethodDescriptor DETAILS = read("securitycraft.details",
            "Owner-only native configuration and device-family telemetry", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor OPTIONS = read("securitycraft.options",
            "Owner-only list of SecurityCraft custom options and valid ranges", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor MODULES = read("securitycraft.modules",
            "Owner-only list of accepted, installed, and enabled SecurityCraft modules", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor AUDIT = read("securitycraft.audit",
            "Owner-only bounded Username Logger or Block Change Detector entries", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor OPTION_SET = write("securitycraft.option.set",
            "Sets an existing SecurityCraft option using its native type and range",
            stringParameter("name", "option name"), stringParameter("value", "boolean, number, or enum name"));
    private static final DeviceMethodDescriptor MODULE_SET = write("securitycraft.module.set_enabled",
            "Enables or disables an already-installed SecurityCraft module without creating or removing items",
            stringParameter("module", "module type"), booleanParameter("enabled", "new enabled state"));
    private static final DeviceMethodDescriptor CONFIG_SET = write("securitycraft.config.set",
            "Sets an owner-only family-specific SecurityCraft field; inspect securitycraft.details for field names",
            stringParameter("field", "family-specific field"), stringParameter("value", "new value"));
    private static final DeviceMethodDescriptor AUDIT_CLEAR = new DeviceMethodDescriptor(
            "securitycraft.audit.clear", "Clears native logger/detector entries as the owning player",
            List.of(), DeviceValueType.BOOLEAN, DeviceCallContext.WRITE);

    private final OptionalDeviceEndpointRegistry.Context context;
    private final BlockEntity blockEntity;
    private final IOwnable ownable;

    SecurityCraftDeviceEndpoint(OptionalDeviceEndpointRegistry.Context context, IOwnable ownable) {
        this.context = context;
        this.blockEntity = context.blockEntity();
        this.ownable = ownable;
    }

    @Override
    public DeviceDescriptor descriptor() {
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("securitycraft_owned");
        if (blockEntity instanceof ICustomizable) capabilities.add("securitycraft_options");
        if (blockEntity instanceof IModuleInventory) capabilities.add("securitycraft_modules");
        if (blockEntity instanceof IPasscodeProtected) capabilities.add("securitycraft_passcode_protected");
        if (blockEntity instanceof ILockable) capabilities.add("securitycraft_lockable");
        if (blockEntity instanceof IEMPAffected) capabilities.add("securitycraft_emp_affected");
        if (hasAudit()) capabilities.add("securitycraft_audit");
        if (sentry() != null) capabilities.add("securitycraft_sentry");
        capabilities.add("securitycraft_" + family());

        List<DeviceMethodDescriptor> methods = new ArrayList<>();
        methods.add(STATUS);
        methods.add(DETAILS);
        methods.add(CONFIG_SET);
        if (blockEntity instanceof ICustomizable) {
            methods.add(OPTIONS);
            methods.add(OPTION_SET);
        }
        if (blockEntity instanceof IModuleInventory) {
            methods.add(MODULES);
            methods.add(MODULE_SET);
        }
        if (hasAudit()) {
            methods.add(AUDIT);
            methods.add(AUDIT_CLEAR);
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).toString();
        String name = blockEntity instanceof NamedBlockEntity named
                ? named.getName().getString() : blockEntity.getBlockState().getBlock().getName().getString();
        return new DeviceDescriptor(context.deviceId(), "terminalcraft:securitycraft_native",
                "securitycraft_device", name.isBlank() ? blockId : name, "securitycraft", context.address(),
                capabilities, publicStatus(), methods, Set.of(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), context.isCurrent(),
                context.level().hasChunkAt(context.targetPosition()));
    }

    @Override
    public DeviceResult call(DeviceCallContext caller, String method, List<DeviceValue> arguments) {
        try {
            return switch (method == null ? "" : method) {
                case "securitycraft.status" -> DeviceResult.success(DeviceValue.map(publicStatus()));
                case "securitycraft.details" -> ownerRead(caller, DeviceValue.map(ownerDetails()));
                case "securitycraft.options" -> ownerRead(caller, optionValues());
                case "securitycraft.modules" -> ownerRead(caller, moduleValues());
                case "securitycraft.audit" -> ownerRead(caller, auditValues());
                case "securitycraft.option.set" -> setOption(caller, arguments);
                case "securitycraft.module.set_enabled" -> setModule(caller, arguments);
                case "securitycraft.config.set" -> setConfig(caller, arguments);
                case "securitycraft.audit.clear" -> clearAudit(caller);
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "SecurityCraft rejected the device operation", true);
        }
    }

    /** Public operational data is intentionally insufficient to discover credentials or protected configuration. */
    Map<String, DeviceValue> publicStatus() {
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        Owner owner = ownable.getOwner();
        values.put("securitycraft_family", DeviceValue.of(family()));
        values.put("securitycraft_owner_present", DeviceValue.of(owner != null && !owner.isDefaultOwner()));
        values.put("securitycraft_owner_validated", DeviceValue.of(owner != null && owner.isValidated()));
        values.put("securitycraft_redstone_input", DeviceValue.of(
                context.level().getBestNeighborSignal(context.targetPosition())));
        values.put("securitycraft_powered", DeviceValue.of(powered()));
        values.put("securitycraft_active", DeviceValue.of(active()));
        values.put("securitycraft_disabled", DeviceValue.of(disabled()));
        values.put("securitycraft_locked", DeviceValue.of(
                blockEntity instanceof ILockable lockable && lockable.isLockedBySSS()));
        values.put("securitycraft_passcode_configured", DeviceValue.of(
                blockEntity instanceof IPasscodeProtected protectedBlock && protectedBlock.getPasscode() != null));
        values.put("securitycraft_module_count", DeviceValue.of(moduleCount()));
        values.put("securitycraft_option_count", DeviceValue.of(
                blockEntity instanceof ICustomizable customizable ? customizable.customOptions().length : 0));
        values.put("securitycraft_audit_entries", DeviceValue.of(auditCount()));
        Sentry sentry = sentry();
        values.put("securitycraft_sentry_present", DeviceValue.of(sentry != null));
        if (sentry != null) {
            values.put("securitycraft_sentry_target", DeviceValue.of(sentry.hasTarget()));
            values.put("securitycraft_sentry_shutdown", DeviceValue.of(sentry.isShutDown()));
        }
        return values;
    }

    private Map<String, DeviceValue> ownerDetails() {
        Map<String, DeviceValue> values = new LinkedHashMap<>(publicStatus());
        Owner owner = ownable.getOwner();
        values.put("owner_name", DeviceValue.of(owner == null ? "" : owner.getName()));
        values.put("available_config_fields", DeviceValue.list(configFields().stream().map(DeviceValue::of).toList()));
        if (blockEntity instanceof AlarmBlockEntity alarm) {
            values.put("alarm_sound", DeviceValue.of(alarm.getSound().getLocation().toString()));
            values.put("alarm_pitch", DeviceValue.of(alarm.getPitch()));
            values.put("alarm_sound_length", DeviceValue.of(alarm.getSoundLength()));
            values.put("alarm_cooldown", DeviceValue.of(alarm.getCooldown()));
        }
        if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) {
            values.put("detector_mode", DeviceValue.of(detector.getMode().name().toLowerCase(Locale.ROOT)));
            values.put("detector_range", DeviceValue.of(detector.getRange()));
            values.put("detector_signal_length", DeviceValue.of(detector.getSignalLength()));
        }
        if (blockEntity instanceof DisplayCaseBlockEntity display) {
            values.put("display_open", DeviceValue.of(display.isOpen()));
        }
        if (blockEntity instanceof FrameBlockEntity frame) {
            values.put("frame_camera_count", DeviceValue.of(frame.getCameraPositions().size()));
            values.put("frame_camera_selected", DeviceValue.of(frame.getCurrentCamera() != null));
        }
        if (blockEntity instanceof IMSBlockEntity ims) {
            values.put("ims_targeting_mode", DeviceValue.of(ims.getTargetingMode().name().toLowerCase(Locale.ROOT)));
            values.put("ims_attack_interval", DeviceValue.of(ims.getAttackInterval()));
        }
        if (blockEntity instanceof InventoryScannerBlockEntity scanner) {
            values.put("scanner_prohibited_items", DeviceValue.of(scanner.getAllProhibitedItems().size()));
            values.put("scanner_providing_power", DeviceValue.of(scanner.isProvidingPower()));
            values.put("scanner_wants_power", DeviceValue.of(scanner.wantsToProvidePower()));
            values.put("scanner_horizontal", DeviceValue.of(scanner.isHorizontal()));
            values.put("scanner_solid_field", DeviceValue.of(scanner.doesFieldSolidify()));
            values.put("scanner_signal_length", DeviceValue.of(scanner.getSignalLength()));
        }
        if (blockEntity instanceof KeycardReaderBlockEntity reader) {
            values.put("keycard_signature", DeviceValue.of(reader.getSignature()));
            values.put("keycard_levels", DeviceValue.list(booleans(reader.getAcceptedLevels())));
            values.put("keycard_lock_setup", DeviceValue.of(
                    !(reader instanceof KeycardLockBlockEntity lock) || lock.isSetUp()));
        }
        if (blockEntity instanceof LaserBlockBlockEntity laser) {
            Map<String, DeviceValue> sides = new LinkedHashMap<>();
            for (Direction direction : Direction.values()) {
                sides.put(direction.getName(), DeviceValue.of(laser.isSideEnabled(direction)));
            }
            values.put("laser_sides", DeviceValue.map(sides));
            values.put("laser_signal_length", DeviceValue.of(laser.getSignalLength()));
        }
        if (blockEntity instanceof PortableRadarBlockEntity radar) {
            values.put("radar_range", DeviceValue.of(radar.getSearchRadius()));
            values.put("radar_delay", DeviceValue.of(radar.getSearchDelay()));
        }
        if (blockEntity instanceof ProjectorBlockEntity projector) {
            values.put("projector_active", DeviceValue.of(projector.isActive()));
            values.put("projector_redstone", DeviceValue.of(projector.isActivatedByRedstone()));
            values.put("projector_width", DeviceValue.of(projector.getProjectionWidth()));
            values.put("projector_height", DeviceValue.of(projector.getProjectionHeight()));
            values.put("projector_range", DeviceValue.of(projector.getProjectionRange()));
            values.put("projector_offset", DeviceValue.of(projector.getProjectionOffset()));
        }
        if (blockEntity instanceof RetinalScannerBlockEntity retinal) {
            values.put("retinal_player_only", DeviceValue.of(retinal.activatedOnlyByPlayer()));
            values.put("retinal_signal_length", DeviceValue.of(retinal.getSignalLength()));
            values.put("retinal_max_distance", DeviceValue.of(retinal.getMaximumDistance()));
        }
        if (blockEntity instanceof RiftStabilizerBlockEntity rift) {
            values.put("rift_range", DeviceValue.of(rift.getRange()));
            values.put("rift_signal_length", DeviceValue.of(rift.getSignalLength()));
            values.put("rift_last_distance", DeviceValue.of(rift.getLastTeleportDistance()));
            values.put("rift_last_type", DeviceValue.of(rift.getLastTeleportationType().name().toLowerCase(Locale.ROOT)));
            Map<String, DeviceValue> filters = new LinkedHashMap<>();
            rift.getFilters().forEach((key, value) -> filters.put(key.name().toLowerCase(Locale.ROOT), DeviceValue.of(value)));
            values.put("rift_filters", DeviceValue.map(filters));
        }
        if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity redstone) {
            values.put("secure_redstone_sender", DeviceValue.of(redstone.isSender()));
            values.put("secure_redstone_frequency", DeviceValue.of(redstone.getFrequency()));
            values.put("secure_redstone_range", DeviceValue.of(redstone.getSenderRange()));
            values.put("secure_redstone_power", DeviceValue.of(redstone.getPower()));
            values.put("secure_redstone_output", DeviceValue.of(redstone.getRedstonePowerOutput()));
            values.put("secure_redstone_protected", DeviceValue.of(redstone.isProtectedSignal()));
            values.put("secure_redstone_exact", DeviceValue.of(redstone.sendsExactPower()));
            values.put("secure_redstone_inverted", DeviceValue.of(redstone.receivesInvertedPower()));
        }
        if (blockEntity instanceof SecurityCameraBlockEntity camera) {
            values.put("camera_shutdown", DeviceValue.of(camera.isShutDown()));
            values.put("camera_rotation", DeviceValue.of(camera.getCameraRotation()));
            values.put("camera_down", DeviceValue.of(camera.isDown()));
            values.put("camera_opacity", DeviceValue.of(camera.getOpacity()));
            values.put("camera_movement_speed", DeviceValue.of(camera.getMovementSpeed()));
        }
        if (blockEntity instanceof SonicSecuritySystemBlockEntity sonic) {
            values.put("sonic_active", DeviceValue.of(sonic.isActive()));
            values.put("sonic_listening", DeviceValue.of(sonic.isListening()));
            values.put("sonic_recording", DeviceValue.of(sonic.isRecording()));
            values.put("sonic_pings", DeviceValue.of(sonic.pings()));
            values.put("sonic_note_count", DeviceValue.of(sonic.getNumberOfNotes()));
            values.put("sonic_link_count", DeviceValue.of(sonic.getNumberOfLinkedBlocks()));
            values.put("sonic_shutdown", DeviceValue.of(sonic.isShutDown()));
        }
        if (blockEntity instanceof TrophySystemBlockEntity trophy) {
            values.put("trophy_target_present", DeviceValue.of(trophy.getTarget() != null));
            values.put("trophy_cooldown", DeviceValue.of(trophy.getCooldownTime()));
            values.put("trophy_default_filter", DeviceValue.of(trophy.getDefaultTypeName()));
        }
        if (blockEntity instanceof TrackMineBlockEntity mine) values.put("track_mine_active", DeviceValue.of(mine.isActive()));
        Sentry sentry = sentry();
        if (sentry != null) {
            values.put("sentry_mode", DeviceValue.of(sentry.getMode().name().toLowerCase(Locale.ROOT)));
            values.put("sentry_target", DeviceValue.of(sentry.hasTarget()));
            values.put("sentry_shutdown", DeviceValue.of(sentry.isShutDown()));
            values.put("sentry_speed_module", DeviceValue.of(sentry.hasSpeedModule()));
            values.put("sentry_allowlist_module", DeviceValue.of(!sentry.getAllowlistModule().isEmpty()));
            values.put("sentry_disguise_module", DeviceValue.of(!sentry.getDisguiseModule().isEmpty()));
        }
        return values;
    }

    private DeviceValue optionValues() {
        if (!(blockEntity instanceof ICustomizable customizable)) return DeviceValue.list(List.of());
        List<DeviceValue> values = new ArrayList<>();
        for (Option<?> option : customizable.customOptions()) {
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("name", DeviceValue.of(option.getName()));
            value.put("type", DeviceValue.of(optionType(option.get())));
            value.put("value", toDeviceValue(option.get()));
            value.put("default", toDeviceValue(option.getDefaultValue()));
            value.put("minimum", toDeviceValue(option.getMin()));
            value.put("maximum", toDeviceValue(option.getMax()));
            value.put("increment", toDeviceValue(option.getIncrement()));
            values.add(DeviceValue.map(value));
        }
        return DeviceValue.list(values);
    }

    private DeviceValue moduleValues() {
        if (!(blockEntity instanceof IModuleInventory inventory)) return DeviceValue.list(List.of());
        List<DeviceValue> values = new ArrayList<>();
        for (ModuleType type : inventory.acceptedModules()) {
            ItemStack stack = inventory.getModule(type);
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("module", DeviceValue.of(type.name().toLowerCase(Locale.ROOT)));
            value.put("installed", DeviceValue.of(!stack.isEmpty()));
            value.put("enabled", DeviceValue.of(inventory.isModuleEnabled(type)));
            value.put("item", DeviceValue.of(stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
            values.add(DeviceValue.map(value));
        }
        return DeviceValue.list(values);
    }

    private DeviceValue auditValues() {
        List<DeviceValue> values = new ArrayList<>();
        if (blockEntity instanceof UsernameLoggerBlockEntity logger) {
            UsernameLoggerBlockEntity.UsernameLoggerEntry[] entries = logger.getEntries();
            for (int index = Math.max(0, entries.length - MAX_AUDIT_ENTRIES); index < entries.length; index++) {
                UsernameLoggerBlockEntity.UsernameLoggerEntry entry = entries[index];
                if (entry == null) continue;
                values.add(DeviceValue.map(Map.of(
                        "player", DeviceValue.of(entry.playerName()),
                        "timestamp", DeviceValue.of(entry.timestamp()))));
            }
        } else if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) {
            List<BlockChangeDetectorBlockEntity.ChangeEntry> entries = detector.getFilteredEntries();
            for (int index = Math.max(0, entries.size() - MAX_AUDIT_ENTRIES); index < entries.size(); index++) {
                BlockChangeDetectorBlockEntity.ChangeEntry entry = entries.get(index);
                Map<String, DeviceValue> value = new LinkedHashMap<>();
                value.put("player", DeviceValue.of(entry.player()));
                value.put("timestamp", DeviceValue.of(entry.timestamp()));
                value.put("action", DeviceValue.of(entry.action().name().toLowerCase(Locale.ROOT)));
                value.put("block", DeviceValue.of(BuiltInRegistries.BLOCK.getKey(entry.state().getBlock()).toString()));
                value.put("x", DeviceValue.of(entry.pos().getX()));
                value.put("y", DeviceValue.of(entry.pos().getY()));
                value.put("z", DeviceValue.of(entry.pos().getZ()));
                values.add(DeviceValue.map(value));
            }
        }
        return DeviceValue.list(values);
    }

    private DeviceResult setOption(DeviceCallContext caller, List<DeviceValue> arguments) {
        DeviceResult denied = requireOwner(caller);
        if (denied != null) return denied;
        if (!(blockEntity instanceof ICustomizable customizable)) return unsupported("custom options");
        String name = stringArg(arguments, 0, "name");
        String value = stringArg(arguments, 1, "value");
        for (Option<?> option : customizable.customOptions()) {
            if (!option.getName().equalsIgnoreCase(name)) continue;
            setOptionValue(option, value);
            notifyOptionChanged(customizable, option);
            sync();
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "name", DeviceValue.of(option.getName()),
                    "value", toDeviceValue(option.get()))));
        }
        throw new IllegalArgumentException("unknown SecurityCraft option: " + name);
    }

    private DeviceResult setModule(DeviceCallContext caller, List<DeviceValue> arguments) {
        DeviceResult denied = requireOwner(caller);
        if (denied != null) return denied;
        if (!(blockEntity instanceof IModuleInventory inventory)) return unsupported("modules");
        ModuleType module = parseModule(stringArg(arguments, 0, "module"));
        boolean enabled = booleanArg(arguments, 1, "enabled");
        ItemStack stack = inventory.getModule(module);
        if (stack.isEmpty()) throw new IllegalArgumentException("module is not installed: " + module.name().toLowerCase(Locale.ROOT));
        if (inventory.isModuleEnabled(module) != enabled) {
            if (enabled) inventory.insertModule(stack, true);
            else inventory.removeModule(module, true);
            if (blockEntity instanceof LinkableBlockEntity linkable) {
                if (enabled) linkable.propagate(new ILinkedAction.ModuleInserted(stack,
                        (ModuleItem) stack.getItem(), true), linkable);
                else linkable.propagate(new ILinkedAction.ModuleRemoved(module, true), linkable);
            }
            sync();
        }
        return DeviceResult.success(DeviceValue.map(Map.of(
                "module", DeviceValue.of(module.name().toLowerCase(Locale.ROOT)),
                "enabled", DeviceValue.of(inventory.isModuleEnabled(module)))));
    }

    private DeviceResult setConfig(DeviceCallContext caller, List<DeviceValue> arguments) {
        DeviceResult denied = requireOwner(caller);
        if (denied != null) return denied;
        ServerPlayer player = SecurityCraftIntegration.authenticatedPlayer(blockEntity, caller);
        String field = normalizeField(stringArg(arguments, 0, "field"));
        String value = stringArg(arguments, 1, "value");
        boolean handled = configure(field, value, player);
        if (!handled) throw new IllegalArgumentException("unsupported config field for " + family() + ": " + field);
        sync();
        return DeviceResult.success(DeviceValue.map(ownerDetails()));
    }

    private boolean configure(String field, String value, ServerPlayer player) {
        if (blockEntity instanceof AlarmBlockEntity alarm) {
            switch (field) {
                case "sound" -> { ResourceLocation id = ResourceLocation.tryParse(value); if (id == null) throw new IllegalArgumentException("invalid sound id"); alarm.setSound(id); return true; }
                case "pitch" -> { alarm.setPitch((float) boundedDouble(value, 0.5, 2.0, "pitch")); return true; }
                case "sound_length" -> { alarm.setSoundLength(boundedInt(value, 1, AlarmBlockEntity.MAXIMUM_ALARM_SOUND_LENGTH, "sound_length")); return true; }
            }
        }
        if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity redstone) {
            switch (field) {
                case "sender" -> redstone.setSender(parseBoolean(value, field));
                case "frequency" -> redstone.setFrequency(boundedInt(value, 0, 99999, field));
                case "range" -> redstone.setSenderRange(boundedInt(value, 1, 64, field));
                case "protected" -> redstone.setProtectedSignal(parseBoolean(value, field));
                case "exact" -> redstone.setSendExactPower(parseBoolean(value, field));
                case "inverted" -> redstone.setReceiveInvertedPower(parseBoolean(value, field));
                case "highlight" -> redstone.setHighlightConnections(parseBoolean(value, field));
                default -> { return false; }
            }
            return true;
        }
        if (blockEntity instanceof KeycardReaderBlockEntity reader) {
            if (field.equals("signature")) { reader.setSignature(boundedInt(value, 0, 99999, field)); return true; }
            if (field.matches("level_[1-5]")) {
                int index = Integer.parseInt(field.substring(6)) - 1;
                boolean[] levels = reader.getAcceptedLevels().clone();
                levels[index] = parseBoolean(value, field);
                reader.setAcceptedLevels(levels);
                return true;
            }
        }
        if (blockEntity instanceof LaserBlockBlockEntity laser) {
            Direction direction = Direction.byName(field.startsWith("side_") ? field.substring(5) : field);
            if (direction != null) { laser.setSideEnabled(direction, parseBoolean(value, field), player); return true; }
        }
        if (blockEntity instanceof RiftStabilizerBlockEntity rift && field.startsWith("filter_")) {
            RiftStabilizerBlockEntity.TeleportationType type = enumValue(
                    RiftStabilizerBlockEntity.TeleportationType.class, field.substring(7), "teleportation filter");
            rift.setFilter(type, parseBoolean(value, field));
            return true;
        }
        if (blockEntity instanceof TrophySystemBlockEntity trophy && field.startsWith("filter_")) {
            ResourceLocation id = ResourceLocation.tryParse(field.substring(7));
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw new IllegalArgumentException("unknown projectile entity id");
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            trophy.setFilter(type, parseBoolean(value, field));
            return true;
        }
        if (blockEntity instanceof InventoryScannerBlockEntity scanner && field.equals("power_output")) {
            boolean desired = parseBoolean(value, field);
            if (scanner.wantsToProvidePower() != desired) scanner.togglePowerOutput();
            return true;
        }
        if (blockEntity instanceof TrackMineBlockEntity mine && field.equals("active")) {
            if (parseBoolean(value, field)) mine.activate(); else mine.deactivate();
            return true;
        }
        if (blockEntity instanceof ProjectorBlockEntity projector && field.equals("active")) {
            projector.setActive(parseBoolean(value, field)); return true;
        }
        if (blockEntity instanceof SonicSecuritySystemBlockEntity sonic) {
            switch (field) {
                case "active" -> sonic.setActive(parseBoolean(value, field));
                case "pings" -> sonic.setPings(parseBoolean(value, field));
                case "disable_when_tune" -> sonic.setDisableBlocksWhenTuneIsPlayed(parseBoolean(value, field));
                case "shutdown" -> sonic.setShutDown(parseBoolean(value, field));
                default -> { return false; }
            }
            return true;
        }
        if (blockEntity instanceof IEMPAffected affected && field.equals("shutdown")) {
            if (parseBoolean(value, field)) affected.shutDown(); else affected.reactivate();
            return true;
        }
        Sentry sentry = sentry();
        if (sentry != null) {
            if (field.equals("mode")) {
                Sentry.SentryMode mode = enumValue(Sentry.SentryMode.class, value, "sentry mode");
                sentry.toggleMode(player, mode.ordinal(), false);
                return true;
            }
            if (field.equals("shutdown")) { sentry.setShutDown(parseBoolean(value, field)); return true; }
        }
        return false;
    }

    private DeviceResult clearAudit(DeviceCallContext caller) {
        DeviceResult denied = requireOwner(caller);
        if (denied != null) return denied;
        if (blockEntity instanceof UsernameLoggerBlockEntity logger) logger.clearEntries();
        else if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) detector.clearContent();
        else return unsupported("audit log");
        sync();
        return DeviceResult.success(DeviceValue.of(true));
    }

    private DeviceResult ownerRead(DeviceCallContext caller, DeviceValue value) {
        DeviceResult denied = requireOwner(caller);
        return denied == null ? DeviceResult.success(value) : denied;
    }

    private DeviceResult requireOwner(DeviceCallContext caller) {
        ServerPlayer player = SecurityCraftIntegration.authenticatedPlayer(blockEntity, caller);
        if (player == null || !ownable.isOwnedBy(player, false)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "SecurityCraft details and controls require the online, authenticated owning player", false);
        }
        return null;
    }

    private boolean powered() {
        if (blockEntity instanceof AlarmBlockEntity alarm) return alarm.isPowered();
        if (blockEntity instanceof InventoryScannerBlockEntity scanner) return scanner.isProvidingPower();
        if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity redstone) return redstone.getPower() > 0;
        return blockEntity.getBlockState().getOptionalValue(BlockStateProperties.POWERED).orElse(false);
    }

    private boolean active() {
        if (blockEntity instanceof TrackMineBlockEntity mine) return mine.isActive();
        if (blockEntity instanceof ProjectorBlockEntity projector) return projector.isActive();
        if (blockEntity instanceof SonicSecuritySystemBlockEntity sonic) return sonic.isActive();
        if (blockEntity instanceof SecurityCameraBlockEntity camera) return !camera.isDisabled() && !camera.isShutDown();
        if (blockEntity instanceof LaserBlockBlockEntity laser) return laser.isEnabled();
        Sentry sentry = sentry();
        return sentry == null || !sentry.isShutDown();
    }

    boolean disabled() {
        if (blockEntity instanceof ICustomizable customizable) {
            for (Option<?> option : customizable.customOptions()) {
                if (option.getName().equals("disabled") && option.get() instanceof Boolean value) return value;
            }
        }
        return false;
    }

    private int moduleCount() {
        return blockEntity instanceof IModuleInventory inventory ? inventory.getInsertedModules().size() : 0;
    }

    private boolean hasAudit() {
        return blockEntity instanceof UsernameLoggerBlockEntity || blockEntity instanceof BlockChangeDetectorBlockEntity;
    }

    int auditCount() {
        if (blockEntity instanceof UsernameLoggerBlockEntity logger) {
            int count = 0;
            for (UsernameLoggerBlockEntity.UsernameLoggerEntry entry : logger.getEntries()) if (entry != null) count++;
            return count;
        }
        if (blockEntity instanceof BlockChangeDetectorBlockEntity detector) return detector.getFilteredEntries().size();
        return 0;
    }

    private String family() {
        String path = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).getPath();
        return path.replace('-', '_');
    }

    private List<String> configFields() {
        List<String> fields = new ArrayList<>();
        if (blockEntity instanceof AlarmBlockEntity) fields.addAll(List.of("sound", "pitch", "sound_length"));
        if (blockEntity instanceof SecureRedstoneInterfaceBlockEntity) fields.addAll(List.of(
                "sender", "frequency", "range", "protected", "exact", "inverted", "highlight"));
        if (blockEntity instanceof KeycardReaderBlockEntity) fields.addAll(List.of(
                "signature", "level_1", "level_2", "level_3", "level_4", "level_5"));
        if (blockEntity instanceof LaserBlockBlockEntity) for (Direction direction : Direction.values()) fields.add("side_" + direction.getName());
        if (blockEntity instanceof RiftStabilizerBlockEntity rift) rift.getFilters().keySet().forEach(type -> fields.add("filter_" + type.name().toLowerCase(Locale.ROOT)));
        if (blockEntity instanceof TrophySystemBlockEntity trophy) trophy.getFilters().keySet().stream().limit(64)
                .forEach(type -> fields.add("filter_" + BuiltInRegistries.ENTITY_TYPE.getKey(type)));
        if (blockEntity instanceof InventoryScannerBlockEntity) fields.add("power_output");
        if (blockEntity instanceof TrackMineBlockEntity || blockEntity instanceof ProjectorBlockEntity) fields.add("active");
        if (blockEntity instanceof SonicSecuritySystemBlockEntity) fields.addAll(List.of("active", "pings", "disable_when_tune", "shutdown"));
        else if (blockEntity instanceof IEMPAffected) fields.add("shutdown");
        if (sentry() != null) fields.addAll(List.of("mode", "shutdown"));
        return List.copyOf(fields);
    }

    private Sentry sentry() {
        List<Sentry> sentries = context.level().getEntitiesOfClass(Sentry.class,
                new AABB(context.targetPosition()).inflate(0.2),
                candidate -> candidate.blockPosition().equals(context.targetPosition()));
        return sentries.isEmpty() ? null : sentries.get(0);
    }

    private void sync() {
        blockEntity.setChanged();
        context.level().sendBlockUpdated(context.targetPosition(), blockEntity.getBlockState(),
                blockEntity.getBlockState(), Block.UPDATE_ALL);
    }

    private static List<DeviceValue> booleans(boolean[] values) {
        List<DeviceValue> result = new ArrayList<>(values.length);
        for (boolean value : values) result.add(DeviceValue.of(value));
        return result;
    }

    private static DeviceResult unsupported(String feature) {
        return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                "SecurityCraft device does not support " + feature, false);
    }

    private static String stringArg(List<DeviceValue> arguments, int index, String name) {
        if (arguments == null || index >= arguments.size() || !(arguments.get(index) instanceof DeviceValue.StringValue value)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.value().trim();
    }

    private static boolean booleanArg(List<DeviceValue> arguments, int index, String name) {
        if (arguments == null || index >= arguments.size()) throw new IllegalArgumentException(name + " must be boolean");
        DeviceValue value = arguments.get(index);
        if (value instanceof DeviceValue.BooleanValue bool) return bool.value();
        if (value instanceof DeviceValue.StringValue string) return parseBoolean(string.value(), name);
        throw new IllegalArgumentException(name + " must be boolean");
    }

    private static boolean parseBoolean(String value, String name) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1", "enabled" -> true;
            case "false", "off", "no", "0", "disabled" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    private static String normalizeField(String field) {
        String normalized = field.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[a-z0-9_:./]+")) throw new IllegalArgumentException("invalid config field");
        return normalized;
    }

    private static int boundedInt(String value, int minimum, int maximum, String name) {
        int parsed;
        try { parsed = Integer.parseInt(value.trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be an integer"); }
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        return parsed;
    }

    private static double boundedDouble(String value, double minimum, double maximum, String name) {
        double parsed;
        try { parsed = Double.parseDouble(value.trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be a number"); }
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) throw new IllegalArgumentException(name + " is outside its valid range");
        return parsed;
    }

    private static ModuleType parseModule(String value) {
        return enumValue(ModuleType.class, value, "module");
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unknown " + name + ": " + value); }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setOptionValue(Option option, String raw) {
        Object current = option.get();
        if (current instanceof Boolean) option.setValue(parseBoolean(raw, option.getName()));
        else if (current instanceof Integer) {
            int min = option.getMin() instanceof Number value ? value.intValue() : Integer.MIN_VALUE;
            int max = option.getMax() instanceof Number value ? value.intValue() : Integer.MAX_VALUE;
            option.setValue(boundedInt(raw, Math.min(min, max), Math.max(min, max), option.getName()));
        } else if (current instanceof Double) {
            double min = option.getMin() instanceof Number value ? value.doubleValue() : -Double.MAX_VALUE;
            double max = option.getMax() instanceof Number value ? value.doubleValue() : Double.MAX_VALUE;
            option.setValue(boundedDouble(raw, Math.min(min, max), Math.max(min, max), option.getName()));
        } else if (current instanceof Enum<?> enumeration) {
            option.setValue(enumValue((Class<? extends Enum>) enumeration.getDeclaringClass(), raw, option.getName()));
        } else throw new IllegalArgumentException("unsupported option type for " + option.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void notifyOptionChanged(ICustomizable customizable, Option option) {
        customizable.onOptionChanged(option);
    }

    private static String optionType(Object value) {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "integer";
        if (value instanceof Number) return "number";
        if (value instanceof Enum<?>) return "enum";
        return "string";
    }

    private static DeviceValue toDeviceValue(Object value) {
        if (value == null) return DeviceValue.nullValue();
        if (value instanceof Boolean bool) return DeviceValue.of(bool);
        if (value instanceof Number number) return DeviceValue.of(number.doubleValue());
        if (value instanceof Enum<?> enumeration) return DeviceValue.of(enumeration.name().toLowerCase(Locale.ROOT));
        return DeviceValue.of(String.valueOf(value));
    }

    private static DeviceMethodDescriptor read(String name, String description, DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, List.of(), type, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor write(String name, String description,
                                                DeviceParameterDescriptor... parameters) {
        return new DeviceMethodDescriptor(name, description, List.of(parameters),
                DeviceValueType.MAP, DeviceCallContext.WRITE);
    }

    private static DeviceParameterDescriptor stringParameter(String name, String description) {
        return new DeviceParameterDescriptor(name, DeviceValueType.STRING, true, description);
    }

    private static DeviceParameterDescriptor booleanParameter(String name, String description) {
        return new DeviceParameterDescriptor(name, DeviceValueType.BOOLEAN, true, description);
    }
}
