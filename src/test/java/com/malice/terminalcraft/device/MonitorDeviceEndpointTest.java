package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Headless characterization tests for the bounded monitor adapter. */
public final class MonitorDeviceEndpointTest {
    private MonitorDeviceEndpointTest() {}

    public static void main(String[] args) {
        TestMonitor monitor = new TestMonitor();
        AtomicBoolean loaded = new AtomicBoolean(true);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000004");
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new MonitorDeviceEndpoint(id, "minecraft:overworld:1,64,2", monitor,
                () -> true, loaded::get));

        DeviceDescriptor descriptor = registry.descriptor(id).orElseThrow();
        assertTrue(descriptor.capabilities().contains("monitor_output"), "monitor capability");
        assertEquals("terminalcraft:monitor", descriptor.adapterId(), "adapter id");
        assertEquals(20.0, numberProperty(descriptor, "max_lines"), "line limit");
        assertTrue(hasMethod(descriptor, "lines.get"), "line reader advertised");
        assertTrue(hasMethod(descriptor, "line.set"), "positioned row writer advertised");
        assertTrue(descriptor.capabilities().contains("monitor_ui"), "scriptable UI capability");

        assertTrue(descriptor.capabilities().contains("computer_terminal"), "ComputerCraft terminal capability");
        assertTrue(hasMethod(descriptor, "term.blit"), "cell blit advertised");
        assertTrue(hasMethod(descriptor, "term.scroll"), "scroll advertised");
        assertTrue(hasMethod(descriptor, "term.delta"), "revisioned surface delta advertised");
        assertTrue(hasMethod(descriptor, "monitor.set_text_scale"), "monitor scale advertised");

        assertLines(List.of("boot"), registry.call(id, "lines.get", List.of()), "initial lines");
        DeviceAccess readOnly = registry.access(DeviceCallContext.readOnly("reader"));
        DeviceAccess writable = registry.access(new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), "writer",
                java.util.Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                readOnly.call(id, "write", List.of(DeviceValue.of("denied"))), "write permission");
        assertTrue(writable.call(id, "write", List.of(DeviceValue.of("ready"))).isSuccess(), "write");
        assertLines(List.of("boot", "ready"), registry.call(id, "lines.get", List.of()), "written lines");
        assertTrue(writable.call(id, "line.set", List.of(DeviceValue.of(0), DeviceValue.of("STATUS"))).isSuccess(), "positioned write");
        assertLines(List.of("STATUS", "ready"), registry.call(id, "lines.get", List.of()), "positioned rows");
        assertTrue(writable.call(id, "palette.set", List.of(DeviceValue.of(0xFFFFFF), DeviceValue.of(0x000044))).isSuccess(), "palette set");
        DeviceValue.MapValue palette = (DeviceValue.MapValue) registry.call(id, "palette.get", List.of()).value().orElseThrow();
        assertEquals(16777215.0, ((DeviceValue.NumberValue) palette.values().get("foreground")).value(), "foreground");
        assertTrue(writable.call(id, "title.set", List.of(DeviceValue.of("Factory"))).isSuccess(), "title set");
        assertString("Factory", registry.call(id, "title.get", List.of()), "title read");

        long beforeBlitRevision = (long) numberProperty(registry.descriptor(id).orElseThrow(), "surface_revision");
        assertTrue(writable.call(id, "term.set_cursor_pos", List.of(DeviceValue.of(2), DeviceValue.of(2))).isSuccess(), "cursor set");
        assertTrue(writable.call(id, "term.blit", List.of(DeviceValue.of("GUI"), DeviceValue.of("e1a"), DeviceValue.of("012"))).isSuccess(), "cell blit");
        DeviceValue.MapValue delta = (DeviceValue.MapValue) registry.call(id, "term.delta",
                List.of(DeviceValue.of(beforeBlitRevision), DeviceValue.of(2))).value().orElseThrow();
        assertTrue(!((DeviceValue.BooleanValue) delta.values().get("complete")).value(), "surface delta pages large changes");
        assertEquals(2.0, ((DeviceValue.NumberValue) delta.values().get("next_offset")).value(),
                "surface delta exposes the next page offset");
        DeviceValue.MapValue finalDelta = (DeviceValue.MapValue) registry.call(id, "term.delta",
                List.of(DeviceValue.of(beforeBlitRevision), DeviceValue.of(2), delta.values().get("next_offset")))
                .value().orElseThrow();
        assertTrue(((DeviceValue.BooleanValue) finalDelta.values().get("complete")).value(), "surface delta completes after pagination");
        assertTrue(((DeviceValue.ListValue) finalDelta.values().get("cells")).values().size() >= 1,
                "surface delta carries changed cells");
        DeviceValue.MapValue cursor = (DeviceValue.MapValue) registry.call(id, "term.get_cursor_pos", List.of()).value().orElseThrow();
        assertEquals(5.0, ((DeviceValue.NumberValue) cursor.values().get("x")).value(), "blit advances cursor");
        assertEquals(2.0, ((DeviceValue.NumberValue) cursor.values().get("y")).value(), "cursor row");
        assertTrue(writable.call(id, "term.set_cursor_blink", List.of(DeviceValue.of(true))).isSuccess(), "cursor blink set");
        assertTrue(((DeviceValue.BooleanValue) registry.call(id, "term.get_cursor_blink", List.of()).value().orElseThrow()).value(), "cursor blink read");
        assertTrue(writable.call(id, "term.set_text_color", List.of(DeviceValue.of(16384))).isSuccess(), "ComputerCraft red color flag");
        assertEquals(16384.0, ((DeviceValue.NumberValue) registry.call(id, "term.get_text_color", List.of()).value().orElseThrow()).value(), "color flag round trip");
        assertError(DeviceErrorCode.INVALID_ARGUMENT, writable.call(id, "term.set_text_color", List.of(DeviceValue.of(3))), "combined color flag rejected");
        assertTrue(writable.call(id, "monitor.set_text_scale", List.of(DeviceValue.of(2.5))).isSuccess(), "text scale set");
        assertError(DeviceErrorCode.INVALID_ARGUMENT, writable.call(id, "monitor.set_text_scale", List.of(DeviceValue.of(2.25))), "invalid half-step scale");

        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writable.call(id, "write", List.of(DeviceValue.of("x".repeat(41)))), "oversized line");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writable.call(id, "write", List.of(DeviceValue.of("two\nlines"))), "multiline rejected");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                writable.call(id, "title.set", List.of(DeviceValue.of(" "))), "blank title rejected");
        assertError(DeviceErrorCode.UNSUPPORTED, registry.call(id, "unknown", List.of()), "unknown method");

        assertTrue(writable.call(id, "clear", List.of()).isSuccess(), "clear");
        assertLines(List.of(), registry.call(id, "lines.get", List.of()), "cleared lines");
        loaded.set(false);
        assertError(DeviceErrorCode.CHUNK_UNLOADED,
                registry.call(id, "lines.get", List.of()), "unloaded monitor");

        assertReentrantSurfaceImport();
        assertSharedSurfaceRefreshPreservesCellColors();

        System.out.println("Monitor device endpoint tests: OK");
    }

    private static void assertReentrantSurfaceImport() {
        ReentrantMonitor monitor = new ReentrantMonitor();
        MonitorDeviceEndpoint[] holder = new MonitorDeviceEndpoint[1];
        holder[0] = new MonitorDeviceEndpoint(
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                "minecraft:overworld:3,64,4", monitor, () -> true, () -> true);
        monitor.surface.clearCallback = holder[0]::descriptor;
        monitor.lines.clear();
        monitor.lines.add("changed");

        holder[0].descriptor();
        assertEquals(List.of("changed"), monitor.surface.lines(),
                "re-entrant surface import completes");
    }

    private static void assertSharedSurfaceRefreshPreservesCellColors() {
        ReentrantMonitor monitor = new ReentrantMonitor();
        MonitorDeviceEndpoint endpoint = new MonitorDeviceEndpoint(
                UUID.fromString("00000000-0000-0000-0000-000000000006"),
                "minecraft:overworld:5,64,6", monitor, () -> true, () -> true);

        monitor.surface.setPaletteColor(2, 0xF72585);
        monitor.surface.setPaletteColor(3, 0x2EC4B6);
        monitor.surface.clear();
        monitor.surface.setCell(0, 0, 'C', 2, 3);
        monitor.lines.clear();
        monitor.lines.add("C");
        assertEquals(List.of("C"), monitor.surface.lines(), "shared surface has the new line");

        endpoint.descriptor();

        assertEquals(2, monitor.surface.foregroundAt(0, 0),
                "shared surface refresh preserves the colored foreground index");
        assertEquals(3, monitor.surface.backgroundAt(0, 0),
                "shared surface refresh preserves the colored background index");
        assertEquals(0xF72585, monitor.surface.paletteColor(2),
                "shared surface refresh preserves the foreground palette entry");
        assertEquals(0x2EC4B6, monitor.surface.paletteColor(3),
                "shared surface refresh preserves the background palette entry");
    }

    private static final class TestMonitor implements MonitorDevice {
        private final List<String> lines = new ArrayList<>(List.of("boot"));
        private String title = "Monitor";
        private int foreground = 0x66FF99;
        private int background = 0x050A05;
        @Override public int maxLines() { return 20; }
        @Override public int maxLineLength() { return 40; }
        @Override public String title() { return title; }
        @Override public void setTitle(String title) { this.title = title; }
        @Override public List<String> lines() { return List.copyOf(lines); }
        @Override public void writeLine(String text) { lines.add(text); }
        @Override public void setLine(int row, String text) {
            while (lines.size() <= row) lines.add("");
            lines.set(row, text);
        }
        @Override public int foregroundColor() { return foreground; }
        @Override public int backgroundColor() { return background; }
        @Override public void setPalette(int foreground, int background) {
            this.foreground = foreground;
            this.background = background;
        }
        @Override public void clear() { lines.clear(); }
    }

    private static final class ReentrantMonitor implements MonitorDevice {
        private final List<String> lines = new ArrayList<>(List.of("boot"));
        private final ReentrantSurface surface = new ReentrantSurface();
        private String title = "Monitor";
        private int foreground = 0x66FF99;
        private int background = 0x050A05;

        @Override public int maxLines() { return 20; }
        @Override public int maxLineLength() { return 40; }
        @Override public String title() { return title; }
        @Override public void setTitle(String title) { this.title = title; }
        @Override public List<String> lines() { return List.copyOf(lines); }
        @Override public void writeLine(String text) { lines.add(text); }
        @Override public void setLine(int row, String text) {
            while (lines.size() <= row) lines.add("");
            lines.set(row, text);
        }
        @Override public TerminalBuffer terminalSurface() { return surface; }
        @Override public int foregroundColor() { return foreground; }
        @Override public int backgroundColor() { return background; }
        @Override public void setPalette(int foreground, int background) {
            this.foreground = foreground;
            this.background = background;
        }
        @Override public void clear() { lines.clear(); }
    }

    private static final class ReentrantSurface extends TerminalBuffer {
        private Runnable clearCallback = () -> {};

        private ReentrantSurface() { super(40, 20); }

        @Override public void clear() {
            super.clear();
            clearCallback.run();
        }
    }

    private static boolean hasMethod(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().anyMatch(method -> method.name().equals(name));
    }

    private static double numberProperty(DeviceDescriptor descriptor, String name) {
        return ((DeviceValue.NumberValue) descriptor.properties().get(name)).value();
    }

    private static void assertLines(List<String> expected, DeviceResult result, String message) {
        assertTrue(result.isSuccess(), message + " succeeds");
        DeviceValue.ListValue list = (DeviceValue.ListValue) result.value().orElseThrow();
        List<String> actual = list.values().stream()
                .map(value -> ((DeviceValue.StringValue) value).value()).toList();
        assertEquals(expected, actual, message);
    }

    private static void assertString(String expected, DeviceResult result, String message) {
        assertTrue(result.isSuccess(), message + " succeeds");
        assertEquals(expected, ((DeviceValue.StringValue) result.value().orElseThrow()).value(), message);
    }

    private static void assertError(DeviceErrorCode expected, DeviceResult result, String message) {
        if (result.isSuccess() || result.error().orElseThrow().code() != expected) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + result.error());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
