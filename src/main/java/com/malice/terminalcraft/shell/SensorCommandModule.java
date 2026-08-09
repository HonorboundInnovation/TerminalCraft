package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.blockentity.SensorArrayBlockEntity;
import com.malice.terminalcraft.blockentity.StandaloneSensorBlockEntity;
import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorReading;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Locale;

/** Beginner-friendly shell surface for configuring an adjacent Sensor Array. */
final class SensorCommandModule implements ShellCommandModule {
    @Override
    public void register(Registrar registrar) {
        registrar.register("sensor", this::sensor, "sensors");
    }

    private void sensor(Context context, List<String> args) {
        BlockEntity target = findSensor(context.worldHost());
        if (target == null) {
            fail(context, "sensor: no adjacent Sensor Array or individual sensor");
            return;
        }
        if (args.isEmpty() || "help".equalsIgnoreCase(args.get(0))) {
            help(context);
            context.setExitCode(0);
            return;
        }
        String operation = args.get(0).toLowerCase(Locale.ROOT);
        switch (operation) {
            case "list", "status" -> {
                if (target instanceof SensorArrayBlockEntity array) array.summary().forEach(context::printLine);
                else ((StandaloneSensorBlockEntity) target).summary().forEach(context::printLine);
                context.setExitCode(0);
            }
            case "read", "get" -> {
                if (target instanceof SensorArrayBlockEntity array) read(context, array, args);
                else read(context, (StandaloneSensorBlockEntity) target, args);
            }
            case "configure", "set" -> {
                if (target instanceof SensorArrayBlockEntity array) configure(context, array, args);
                else configure(context, (StandaloneSensorBlockEntity) target, args);
            }
            case "enable", "disable" -> {
                if (target instanceof SensorArrayBlockEntity array) enabled(context, array, args, "enable".equals(operation));
                else enabled(context, (StandaloneSensorBlockEntity) target, args, "enable".equals(operation));
            }
            case "remove", "clear" -> {
                if (target instanceof SensorArrayBlockEntity array) remove(context, array, args);
                else remove(context, (StandaloneSensorBlockEntity) target, args);
            }
            case "calibrate", "range" -> {
                if (target instanceof SensorArrayBlockEntity array) calibrate(context, array, args);
                else calibrate(context, (StandaloneSensorBlockEntity) target, args);
            }
            case "interval", "rate" -> {
                if (target instanceof SensorArrayBlockEntity array) interval(context, array, args);
                else interval(context, (StandaloneSensorBlockEntity) target, args);
            }
            default -> fail(context, "sensor: unknown operation; use sensor help");
        }
    }

    private static void help(Context context) {
        context.printLine("sensor list|status");
        context.printLine("sensor read <channel>");
        context.printLine("sensor configure <channel> <kind> <target> <metric> [selector] [ticks]");
        context.printLine("sensor enable|disable <channel>");
        context.printLine("sensor calibrate <channel> <min> <max> [invert]");
        context.printLine("sensor interval <channel> <1-20>");
        context.printLine("sensor remove <channel>");
        context.printLine("kinds: redstone block_state inventory fluid energy entity machine environment network kinetic chemical");
        context.printLine("targets: self north south east west up down");
        context.printLine("individual sensor blocks use the fixed channel: value");
    }

    private static void read(Context context, SensorArrayBlockEntity array, List<String> args) {
        if (args.size() != 2) { fail(context, "sensor: usage: sensor read <channel>"); return; }
        SensorReading reading = array.reading(args.get(1));
        if (reading == null) { fail(context, "sensor: channel not sampled yet or not found"); return; }
        context.printLine("channel=" + reading.channel() + " kind=" + reading.kind().id()
                + " metric=" + reading.metric() + " quality=" + reading.quality().id());
        context.printLine("value=" + (reading.numeric() ? reading.numericValue() : reading.textValue())
                + (reading.unit().isBlank() ? "" : " " + reading.unit())
                + (reading.detail().isBlank() ? "" : " detail=" + reading.detail()));
        context.setExitCode(reading.quality().name().equals("OK") ? 0 : 2);
    }

    private static void read(Context context, StandaloneSensorBlockEntity sensor, List<String> args) {
        if (args.size() != 2 || !"value".equalsIgnoreCase(args.get(1))) {
            fail(context, "sensor: usage: sensor read value");
            return;
        }
        SensorReading reading = sensor.reading();
        if (reading == null) { fail(context, "sensor: sample not available yet"); return; }
        context.printLine("channel=" + reading.channel() + " kind=" + reading.kind().id()
                + " metric=" + reading.metric() + " quality=" + reading.quality().id());
        context.printLine("value=" + (reading.numeric() ? reading.numericValue() : reading.textValue())
                + (reading.unit().isBlank() ? "" : " " + reading.unit())
                + (reading.detail().isBlank() ? "" : " detail=" + reading.detail()));
        context.setExitCode(reading.quality().name().equals("OK") ? 0 : 2);
    }

    private static void configure(Context context, SensorArrayBlockEntity array, List<String> args) {
        if (args.size() < 5 || args.size() > 7) {
            fail(context, "sensor: usage: sensor configure <channel> <kind> <target> <metric> [selector] [ticks]");
            return;
        }
        SensorKind kind = SensorKind.parse(args.get(2));
        int interval = 1;
        if (args.size() == 7) {
            try { interval = Integer.parseInt(args.get(6)); }
            catch (NumberFormatException invalid) { fail(context, "sensor: ticks must be 1..20"); return; }
        }
        String selector = args.size() >= 6 ? args.get(5) : "";
        if (kind == null || !array.configure(args.get(1), kind, args.get(3), args.get(4), selector, interval)) {
            fail(context, "sensor: configuration rejected; check kind, target, metric, and channel capacity");
            return;
        }
        context.printLine("sensor: channel configured");
        context.setExitCode(0);
    }

    private static void configure(Context context, StandaloneSensorBlockEntity sensor, List<String> args) {
        if (args.size() < 2 || args.size() > 4) {
            fail(context, "sensor: usage: sensor configure <metric> [selector] [ticks]");
            return;
        }
        int interval = 1;
        if (args.size() == 4) {
            try { interval = Integer.parseInt(args.get(3)); }
            catch (NumberFormatException invalid) { fail(context, "sensor: ticks must be 1..20"); return; }
        }
        String selector = args.size() == 3 ? args.get(2) : "";
        if (!sensor.configure(args.get(1), selector, interval)) {
            fail(context, "sensor: configuration rejected; check metric, selector, and interval");
            return;
        }
        context.printLine("sensor: individual sensor configured");
        context.setExitCode(0);
    }

    private static void enabled(Context context, SensorArrayBlockEntity array, List<String> args, boolean enabled) {
        if (args.size() != 2 || !array.setEnabled(args.get(1), enabled)) {
            fail(context, "sensor: usage: sensor " + (enabled ? "enable" : "disable") + " <channel>");
            return;
        }
        context.printLine("sensor: channel " + (enabled ? "enabled" : "disabled"));
        context.setExitCode(0);
    }

    private static void enabled(Context context, StandaloneSensorBlockEntity sensor, List<String> args, boolean enabled) {
        if (args.size() != 2 || !"value".equalsIgnoreCase(args.get(1))) {
            fail(context, "sensor: usage: sensor " + (enabled ? "enable" : "disable") + " value");
            return;
        }
        sensor.setEnabled(enabled);
        context.printLine("sensor: individual sensor " + (enabled ? "enabled" : "disabled"));
        context.setExitCode(0);
    }

    private static void remove(Context context, SensorArrayBlockEntity array, List<String> args) {
        if (args.size() != 2 || !array.remove(args.get(1))) {
            fail(context, "sensor: usage: sensor remove <channel>");
            return;
        }
        context.printLine("sensor: channel removed");
        context.setExitCode(0);
    }

    private static void remove(Context context, StandaloneSensorBlockEntity sensor, List<String> args) {
        fail(context, "sensor: individual sensors are removed by breaking the sensor block");
    }

    private static void calibrate(Context context, SensorArrayBlockEntity array, List<String> args) {
        if (args.size() < 4 || args.size() > 5) {
            fail(context, "sensor: usage: sensor calibrate <channel> <min> <max> [invert]");
            return;
        }
        try {
            double minimum = Double.parseDouble(args.get(2));
            double maximum = Double.parseDouble(args.get(3));
            boolean invert = args.size() == 5 && Boolean.parseBoolean(args.get(4));
            if (!array.calibrate(args.get(1), minimum, maximum, invert)) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            fail(context, "sensor: calibration rejected; min/max must be finite and increasing");
            return;
        }
        context.printLine("sensor: calibration updated");
        context.setExitCode(0);
    }

    private static void calibrate(Context context, StandaloneSensorBlockEntity sensor, List<String> args) {
        if (args.size() < 4 || args.size() > 5 || !"value".equalsIgnoreCase(args.get(1))) {
            fail(context, "sensor: usage: sensor calibrate value <min> <max> [invert]");
            return;
        }
        try {
            double minimum = Double.parseDouble(args.get(2));
            double maximum = Double.parseDouble(args.get(3));
            boolean invert = args.size() == 5 && Boolean.parseBoolean(args.get(4));
            if (!sensor.calibrate(minimum, maximum, invert)) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            fail(context, "sensor: calibration rejected; min/max must be finite and increasing");
            return;
        }
        context.printLine("sensor: calibration updated");
        context.setExitCode(0);
    }

    private static void interval(Context context, SensorArrayBlockEntity array, List<String> args) {
        if (args.size() != 3) { fail(context, "sensor: usage: sensor interval <channel> <1-20>"); return; }
        try {
            if (!array.setInterval(args.get(1), Integer.parseInt(args.get(2)))) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            fail(context, "sensor: interval must be an existing channel and 1..20 ticks");
            return;
        }
        context.printLine("sensor: interval updated");
        context.setExitCode(0);
    }

    private static void interval(Context context, StandaloneSensorBlockEntity sensor, List<String> args) {
        if (args.size() != 3 || !"value".equalsIgnoreCase(args.get(1))) {
            fail(context, "sensor: usage: sensor interval value <1-20>");
            return;
        }
        try {
            if (!sensor.setInterval(Integer.parseInt(args.get(2)))) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            fail(context, "sensor: interval must be 1..20 ticks");
            return;
        }
        context.printLine("sensor: interval updated");
        context.setExitCode(0);
    }

    private static BlockEntity findSensor(TerminalHost host) {
        if (host == null || host.getLevel() == null || host.getBlockPos() == null) return null;
        for (Direction direction : Direction.values()) {
            BlockEntity entity = host.getLevel().getBlockEntity(host.getBlockPos().relative(direction));
            if (entity instanceof SensorArrayBlockEntity || entity instanceof StandaloneSensorBlockEntity) return entity;
        }
        return null;
    }

    private static void fail(Context context, String message) {
        context.printLine(message);
        context.setExitCode(1);
    }
}
