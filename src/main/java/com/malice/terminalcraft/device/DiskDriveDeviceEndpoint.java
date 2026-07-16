package com.malice.terminalcraft.device;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Device API adapter for a TerminalCraft floppy drive. */
public final class DiskDriveDeviceEndpoint implements DeviceEndpoint {
    private static final DeviceMethodDescriptor MEDIA_PRESENT = new DeviceMethodDescriptor(
            "media.present", "Returns whether media is inserted", List.of(), DeviceValueType.BOOLEAN);
    private static final DeviceMethodDescriptor LABEL_GET = new DeviceMethodDescriptor(
            "media.label.get", "Returns the inserted media label", List.of(), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor LABEL_SET = new DeviceMethodDescriptor(
            "media.label.set", "Sets the inserted media label",
            List.of(new DeviceParameterDescriptor("label", DeviceValueType.STRING, true, "New media label")),
            DeviceValueType.NULL, DeviceCallContext.WRITE);

    private final UUID deviceId;
    private final String address;
    private final DiskDriveDevice drive;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;

    public DiskDriveDeviceEndpoint(UUID deviceId, String address, DiskDriveDevice drive,
                                   BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = Objects.requireNonNull(address, "address");
        this.drive = Objects.requireNonNull(drive, "drive");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public DeviceDescriptor descriptor() {
        boolean isLoaded = loaded.getAsBoolean();
        boolean isOnline = online.getAsBoolean();
        boolean present = isLoaded && isOnline && drive.mediaPresent();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("media_present", DeviceValue.of(present));
        properties.put("media_label", DeviceValue.of(present ? safeLabel(drive.mediaLabel()) : ""));
        return new DeviceDescriptor(deviceId, "terminalcraft:disk_drive", "disk_drive", "Disk Drive",
                "terminalcraft", address, Set.of("media_storage"), properties,
                List.of(MEDIA_PRESENT, LABEL_GET, LABEL_SET), Set.of("media_changed"),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), isOnline, isLoaded);
    }

    @Override
    public DeviceResult call(String method, List<DeviceValue> arguments) {
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        return switch (method == null ? "" : method) {
            case "media.present" -> DeviceResult.success(DeviceValue.of(drive.mediaPresent()));
            case "media.label.get" -> drive.mediaPresent()
                    ? DeviceResult.success(DeviceValue.of(safeLabel(drive.mediaLabel())))
                    : noMedia();
            case "media.label.set" -> setLabel(args);
            default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
        };
    }

    private DeviceResult setLabel(List<DeviceValue> args) {
        if (!drive.mediaPresent()) return noMedia();
        String label = ((DeviceValue.StringValue) args.get(0)).value().trim();
        if (label.isEmpty() || label.length() > 64)
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "label must contain 1 to 64 characters", false);
        drive.setMediaLabel(label);
        return DeviceResult.success();
    }

    private static DeviceResult noMedia() {
        return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "no media is inserted", true);
    }
    private static String safeLabel(String label) {
        if (label == null) return "";
        return label.substring(0, Math.min(64, label.length()));
    }
}
