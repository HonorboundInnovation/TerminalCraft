package com.malice.terminalcraft.device;

import com.malice.terminalcraft.shell.TerminalHost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Read-only device endpoint over the legacy TerminalHost bridge.
 * Identity, address, and lifecycle remain owned by the world-facing caller.
 */
public final class TerminalHostDeviceEndpoint implements DeviceEndpoint {
    private static final int MAX_SIDES = 32;

    private static final DeviceMethodDescriptor LABEL_GET = new DeviceMethodDescriptor(
            "label.get", "Returns the computer label", List.of(), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor LABEL_SET = new DeviceMethodDescriptor(
            "label.set", "Sets the computer label",
            List.of(new DeviceParameterDescriptor("label", DeviceValueType.STRING, true, "New label")),
            DeviceValueType.NULL, DeviceCallContext.WRITE);
    private static final DeviceMethodDescriptor REDSTONE_SIDES = new DeviceMethodDescriptor(
            "redstone.sides", "Returns supported redstone side names", List.of(), DeviceValueType.LIST);
    private static final DeviceParameterDescriptor SIDE_PARAMETER = new DeviceParameterDescriptor(
            "side", DeviceValueType.STRING, true, "Supported redstone side name");
    private static final DeviceMethodDescriptor REDSTONE_INPUT = new DeviceMethodDescriptor(
            "redstone.input", "Returns redstone input power for a side", List.of(SIDE_PARAMETER),
            DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor REDSTONE_OUTPUT = new DeviceMethodDescriptor(
            "redstone.output", "Returns configured redstone output power for a side", List.of(SIDE_PARAMETER),
            DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor REDSTONE_SET = new DeviceMethodDescriptor(
            "redstone.set", "Sets redstone output power for a side",
            List.of(SIDE_PARAMETER,
                    new DeviceParameterDescriptor("power", DeviceValueType.NUMBER, true, "Power from 0 to 15")),
            DeviceValueType.NULL, DeviceCallContext.WRITE);
    private static final DeviceMethodDescriptor TURTLE_FACING = new DeviceMethodDescriptor(
            "turtle.facing", "Returns the turtle facing direction", List.of(), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor TURTLE_MOVE = new DeviceMethodDescriptor(
            "turtle.move", "Moves the turtle in a relative direction",
            List.of(new DeviceParameterDescriptor("direction", DeviceValueType.STRING, true,
                    "forward, back, up, or down")), DeviceValueType.NULL, DeviceCallContext.WRITE);
    private static final DeviceMethodDescriptor TURTLE_TURN = new DeviceMethodDescriptor(
            "turtle.turn", "Turns the turtle left or right",
            List.of(new DeviceParameterDescriptor("direction", DeviceValueType.STRING, true,
                    "left or right")), DeviceValueType.NULL, DeviceCallContext.WRITE);

    private final UUID deviceId;
    private final String address;
    private final TerminalHost host;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;
    private boolean lastTurtle;
    private String lastLabel;

    public TerminalHostDeviceEndpoint(UUID deviceId, String address, TerminalHost host,
                                      BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = requireAddress(address);
        this.host = Objects.requireNonNull(host, "host");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.lastTurtle = false;
        this.lastLabel = "Computer";
        if (this.loaded.getAsBoolean() && this.online.getAsBoolean()) {
            this.lastTurtle = host.isTurtle();
            this.lastLabel = safeText(host.getLabel());
        }
    }

    @Override
    public synchronized DeviceDescriptor descriptor() {
        boolean currentlyLoaded = loaded.getAsBoolean();
        boolean currentlyOnline = online.getAsBoolean();
        if (currentlyLoaded && currentlyOnline) {
            lastTurtle = host.isTurtle();
            lastLabel = safeText(host.getLabel());
        }
        boolean turtle = lastTurtle;
        Set<String> capabilities = turtle
                ? Set.of("computer", "redstone_io", "turtle")
                : Set.of("computer", "redstone_io");
        List<DeviceMethodDescriptor> methods = new ArrayList<>(List.of(
                LABEL_GET, LABEL_SET, REDSTONE_SIDES, REDSTONE_INPUT, REDSTONE_OUTPUT, REDSTONE_SET));
        if (turtle) methods.addAll(List.of(TURTLE_FACING, TURTLE_MOVE, TURTLE_TURN));

        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("label", DeviceValue.of(lastLabel));
        properties.put("turtle", DeviceValue.of(turtle));

        return new DeviceDescriptor(deviceId, "terminalcraft:terminal_host",
                turtle ? "turtle" : "computer", lastLabel,
                "terminalcraft", address, capabilities, properties, methods, Set.of(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), currentlyOnline, currentlyLoaded);
    }

    @Override
    public DeviceResult call(String method, List<DeviceValue> arguments) {
        if (method == null || method.isBlank()) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "method is required", false);
        }
        List<DeviceValue> safeArguments = arguments == null ? List.of() : arguments;
        return switch (method) {
            case "label.get" -> noArguments(safeArguments,
                    () -> DeviceResult.success(DeviceValue.of(safeText(host.getLabel()))));
            case "label.set" -> setLabel(safeArguments);
            case "redstone.sides" -> noArguments(safeArguments,
                    () -> DeviceResult.success(sideValues()));
            case "redstone.input" -> redstonePower(safeArguments, true);
            case "redstone.output" -> redstonePower(safeArguments, false);
            case "redstone.set" -> setRedstone(safeArguments);
            case "turtle.facing" -> turtleFacing(safeArguments);
            case "turtle.move" -> turtleMove(safeArguments);
            case "turtle.turn" -> turtleTurn(safeArguments);
            default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    "method is unsupported", false);
        };
    }


    private DeviceResult setLabel(List<DeviceValue> arguments) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof DeviceValue.StringValue label)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "label.set requires one string label", false);
        }
        String value = label.value().trim();
        if (value.isEmpty() || value.length() > 64) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "label must contain 1 to 64 characters", false);
        }
        host.setLabel(value);
        return DeviceResult.success();
    }

    private DeviceResult setRedstone(List<DeviceValue> arguments) {
        if (arguments.size() != 2
                || !(arguments.get(0) instanceof DeviceValue.StringValue sideValue)
                || !(arguments.get(1) instanceof DeviceValue.NumberValue powerValue)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "redstone.set requires a string side and numeric power", false);
        }
        String side = sideValue.value();
        double rawPower = powerValue.value();
        if (!safeSides().contains(side) || rawPower != Math.rint(rawPower) || rawPower < 0 || rawPower > 15) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "redstone side must be supported and power must be an integer from 0 to 15", false);
        }
        if (!host.setRedstoneOutput(side, (int) rawPower)) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR, "host rejected redstone output", false);
        }
        return DeviceResult.success();
    }

    private DeviceResult turtleMove(List<DeviceValue> arguments) {
        if (!host.isTurtle()) return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "host is not a turtle", false);
        if (arguments.size() != 1 || !(arguments.get(0) instanceof DeviceValue.StringValue direction)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "turtle.move requires one direction", false);
        }
        boolean moved = switch (direction.value()) {
            case "forward" -> host.turtleForward();
            case "back" -> host.turtleBack();
            case "up" -> host.turtleUp();
            case "down" -> host.turtleDown();
            default -> false;
        };
        return moved ? DeviceResult.success() : DeviceResult.failure(DeviceErrorCode.BUSY,
                "turtle could not move in that direction", true);
    }

    private DeviceResult turtleTurn(List<DeviceValue> arguments) {
        if (!host.isTurtle()) return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "host is not a turtle", false);
        if (arguments.size() != 1 || !(arguments.get(0) instanceof DeviceValue.StringValue direction)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "turtle.turn requires one direction", false);
        }
        boolean turned = switch (direction.value()) {
            case "left" -> host.turtleTurnLeft();
            case "right" -> host.turtleTurnRight();
            default -> false;
        };
        return turned ? DeviceResult.success() : DeviceResult.failure(DeviceErrorCode.BUSY,
                "turtle could not turn in that direction", true);
    }

    private DeviceResult redstonePower(List<DeviceValue> arguments, boolean input) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof DeviceValue.StringValue sideValue)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "redstone method requires one string side", false);
        }
        String side = sideValue.value();
        if (!safeSides().contains(side)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "unsupported redstone side", false);
        }
        int power = input ? host.getRedstoneInput(side) : host.getRedstoneOutput(side);
        if (power < 0 || power > 15) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "host returned invalid redstone power", false);
        }
        return DeviceResult.success(DeviceValue.of(power));
    }

    private DeviceResult turtleFacing(List<DeviceValue> arguments) {
        if (!host.isTurtle()) {
            return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    "host is not a turtle", false);
        }
        return noArguments(arguments,
                () -> DeviceResult.success(DeviceValue.of(safeText(host.turtleFacing()))));
    }

    private DeviceValue sideValues() {
        List<DeviceValue> values = new ArrayList<>();
        for (String side : safeSides()) values.add(DeviceValue.of(side));
        return DeviceValue.list(values);
    }

    private List<String> safeSides() {
        List<String> sides = Objects.requireNonNullElse(host.redstoneSides(), List.of());
        if (sides.size() > MAX_SIDES) {
            throw new IllegalStateException("host advertised too many redstone sides");
        }
        List<String> copy = new ArrayList<>(sides.size());
        for (String side : sides) {
            if (side == null || side.isBlank() || side.length() > 64) {
                throw new IllegalStateException("host advertised invalid redstone side");
            }
            copy.add(side);
        }
        return List.copyOf(copy);
    }

    private static DeviceResult noArguments(List<DeviceValue> arguments,
                                            java.util.function.Supplier<DeviceResult> action) {
        if (!arguments.isEmpty()) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "method does not accept arguments", false);
        }
        return action.get();
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) return "unlabeled";
        return value.length() <= DeviceValue.MAX_STRING_LENGTH
                ? value
                : value.substring(0, DeviceValue.MAX_STRING_LENGTH);
    }

    private static String requireAddress(String value) {
        Objects.requireNonNull(value, "address");
        if (value.isBlank() || value.length() > DeviceValue.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("address must be non-blank and bounded");
        }
        return value;
    }
}
