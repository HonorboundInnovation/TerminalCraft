package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceDnsShellCommand;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceShellCommand;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.TerminalBuffer;
import com.malice.terminalcraft.network.RednetAddress;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.plc.PlcProgramTemplates;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative full-screen device setup program rendered on the shared terminal surface.
 * The client sends only bounded navigation and text-entry actions; discovery, DNS mutations, and
 * device calls are all resolved again through the authenticated caller-bound {@link DeviceAccess}.
 */
public final class ControlCenterProgram {
    private static final int DEVICE_LIMIT = 64;
    private static final int FIRST_CONTENT_ROW = 3;
    private static final int CONTENT_ROWS = 10;
    private static final int DIVIDER_COLUMN = 15;
    private static final int RIGHT_COLUMN = DIVIDER_COLUMN + 1;
    private static final int RIGHT_WIDTH = 40 - RIGHT_COLUMN;
    private static final int MAX_NOTICE_LENGTH = 120;

    private static final int WHITE = 0;
    private static final int GREEN = 5;
    private static final int GRAY = 8;
    private static final int CYAN = 9;
    private static final int BLUE = 11;
    private static final int RED = 14;
    private static final int BLACK = 15;

    public enum Action {
        UP, DOWN, LEFT, RIGHT,
        NEXT_TAB, PREVIOUS_TAB,
        ACTIVATE, REFRESH, POLL, RENAME,
        CLICK, SUBMIT_TEXT, CANCEL_INPUT, CLOSE,
        ADD, EDIT, DELETE,
        RESIZE_UP, RESIZE_DOWN, RESIZE_LEFT, RESIZE_RIGHT
    }

    private enum Tab { OVERVIEW, METHODS, TEMPLATES }
    private enum Pane { DEVICES, DETAIL }
    private enum InputMode { NONE, DNS_ALIAS, METHOD_ARGUMENTS }

    private boolean active;
    private Tab tab = Tab.OVERVIEW;
    private Pane pane = Pane.DEVICES;
    private InputMode inputMode = InputMode.NONE;
    private int deviceIndex;
    private int deviceOffset;
    private int detailIndex;
    private int detailOffset;
    private String pendingMethod = "";
    private String notice = "";
    private boolean noticeError;

    private transient List<DeviceDescriptor> devices = List.of();
    private transient Map<UUID, String> preferredNames = Map.of();

    public boolean active() { return active; }
    public boolean inputActive() { return active && inputMode != InputMode.NONE; }

    public String inputPrompt() {
        if (inputMode == InputMode.DNS_ALIAS) return "DNS alias (letters, numbers, hyphens)";
        if (inputMode == InputMode.METHOD_ARGUMENTS) {
            DeviceMethodDescriptor method = selectedMethod();
            if (method != null) return methodSignature(method);
            return pendingMethod + " arguments";
        }
        return "";
    }

    public void open(DeviceAccess access, Level level, TerminalBuffer surface) {
        active = true;
        tab = Tab.OVERVIEW;
        pane = Pane.DEVICES;
        inputMode = InputMode.NONE;
        pendingMethod = "";
        notice = "Discovering authenticated devices...";
        noticeError = false;
        refresh(access, level, true);
        if (!devices.isEmpty()) setNotice(devices.size() + " device(s) available", false);
        render(surface);
    }

    public void handle(Action action, int row, int column, String text,
                       DeviceAccess access, Level level, TerminalBuffer surface) {
        if (!active || action == null) return;
        if (inputActive() && action != Action.SUBMIT_TEXT && action != Action.CANCEL_INPUT) return;

        switch (action) {
            case CLOSE -> close(surface);
            case CANCEL_INPUT -> cancelInput();
            case SUBMIT_TEXT -> submitText(text, access, level);
            case REFRESH -> {
                refresh(access, level, true);
                if (!devices.isEmpty()) setNotice(devices.size() + " device(s) refreshed", false);
            }
            case RENAME -> beginDnsAlias();
            case NEXT_TAB -> changeTab(1);
            case PREVIOUS_TAB -> changeTab(-1);
            case LEFT -> { pane = Pane.DEVICES; clearNotice(); }
            case RIGHT -> { pane = tab == Tab.OVERVIEW ? Pane.DEVICES : Pane.DETAIL; clearNotice(); }
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(1);
            case ACTIVATE -> activate(access, level);
            case CLICK -> click(row, column);
            case POLL, ADD, EDIT, DELETE, RESIZE_UP, RESIZE_DOWN, RESIZE_LEFT, RESIZE_RIGHT -> {
                // Advanced-HMI-only actions are deliberately ignored by the device Control Center.
            }
        }
        if (active) render(surface);
    }

    public void close(TerminalBuffer surface) {
        active = false;
        inputMode = InputMode.NONE;
        pendingMethod = "";
        devices = List.of();
        preferredNames = Map.of();
        surface.setCursorBlink(false);
    }

    public void render(TerminalBuffer surface) {
        if (!active || surface == null) return;
        fill(surface, ' ', WHITE, BLACK);
        draw(surface, 0, 0, " TERMINALCRAFT CONTROL CENTER ", BLACK, GREEN);
        draw(surface, 29, 0, devices.size() + " DEV", GREEN, BLACK);
        renderTabs(surface);
        draw(surface, 0, 2, pane == Pane.DEVICES ? "> DEVICES" : "  DEVICES", CYAN, BLACK);
        draw(surface, RIGHT_COLUMN, 2, detailHeading(), pane == Pane.DETAIL ? CYAN : GRAY, BLACK);
        for (int row = 2; row < 13; row++) put(surface, DIVIDER_COLUMN, row, '|', GRAY, BLACK);
        renderDevices(surface);
        switch (tab) {
            case OVERVIEW -> renderOverview(surface);
            case METHODS -> renderMethods(surface);
            case TEMPLATES -> renderTemplates(surface);
        }
        renderFooter(surface);
        surface.setCursor(1, 14);
        surface.setCursorBlink(false);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Active", active);
        tag.putString("Tab", tab.name());
        tag.putString("Pane", pane.name());
        tag.putString("InputMode", inputMode.name());
        tag.putInt("DeviceIndex", deviceIndex);
        tag.putInt("DeviceOffset", deviceOffset);
        tag.putInt("DetailIndex", detailIndex);
        tag.putInt("DetailOffset", detailOffset);
        if (!pendingMethod.isEmpty()) tag.putString("PendingMethod", bounded(pendingMethod, 64));
        if (!notice.isEmpty()) tag.putString("Notice", bounded(notice, MAX_NOTICE_LENGTH));
        tag.putBoolean("NoticeError", noticeError);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag == null) return;
        active = tag.getBoolean("Active");
        tab = readEnum(tag, "Tab", Tab.class, Tab.OVERVIEW);
        pane = readEnum(tag, "Pane", Pane.class, Pane.DEVICES);
        inputMode = readEnum(tag, "InputMode", InputMode.class, InputMode.NONE);
        deviceIndex = boundedIndex(tag.getInt("DeviceIndex"));
        deviceOffset = boundedIndex(tag.getInt("DeviceOffset"));
        detailIndex = boundedIndex(tag.getInt("DetailIndex"));
        detailOffset = boundedIndex(tag.getInt("DetailOffset"));
        pendingMethod = tag.contains("PendingMethod", Tag.TAG_STRING)
                ? bounded(tag.getString("PendingMethod"), 64) : "";
        notice = tag.contains("Notice", Tag.TAG_STRING)
                ? bounded(tag.getString("Notice"), MAX_NOTICE_LENGTH) : "";
        noticeError = tag.getBoolean("NoticeError");
        if (!active) {
            inputMode = InputMode.NONE;
            pendingMethod = "";
        }
        devices = List.of();
        preferredNames = Map.of();
    }

    private void refresh(DeviceAccess access, Level level, boolean preserveSelection) {
        UUID selectedId = preserveSelection && selectedDevice() != null
                ? selectedDevice().deviceId() : null;
        List<DeviceDescriptor> discovered = access == null
                ? List.of() : access.descriptors(DEVICE_LIMIT);
        Map<UUID, String> names = new HashMap<>();
        if (level != null && !level.isClientSide) {
            for (DeviceDescriptor descriptor : discovered) {
                String name = RednetNetwork.hostname(level, descriptor.deviceId());
                if (!name.isBlank()) names.put(descriptor.deviceId(), name);
            }
        }
        discovered = new ArrayList<>(discovered);
        discovered.sort(Comparator
                .comparing((DeviceDescriptor descriptor) -> displayName(descriptor, names),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(descriptor -> descriptor.deviceId().toString()));
        devices = List.copyOf(discovered);
        preferredNames = Map.copyOf(names);

        if (selectedId != null) {
            for (int index = 0; index < devices.size(); index++) {
                if (devices.get(index).deviceId().equals(selectedId)) {
                    deviceIndex = index;
                    break;
                }
            }
        }
        clampDeviceSelection();
        clampDetailSelection();
        if (devices.isEmpty()) setNotice("No discoverable devices in this network scope", true);
    }

    private void beginDnsAlias() {
        if (selectedDevice() == null) {
            setNotice("Select a device before assigning a DNS alias", true);
            return;
        }
        inputMode = InputMode.DNS_ALIAS;
        pendingMethod = "";
        setNotice("Type a DNS alias below; Enter saves, Esc cancels", false);
    }

    private void cancelInput() {
        inputMode = InputMode.NONE;
        pendingMethod = "";
        setNotice("Input cancelled", false);
    }

    private void submitText(String text, DeviceAccess access, Level level) {
        String value = text == null ? "" : text.trim();
        if (inputMode == InputMode.DNS_ALIAS) {
            DeviceDescriptor selected = selectedDevice();
            if (selected == null) {
                setNotice("Selected device is no longer available", true);
                inputMode = InputMode.NONE;
                return;
            }
            DeviceShellCommand.Outcome outcome = DeviceDnsShellCommand.execute(level, access,
                    List.of("add", selected.deviceId().toString(), value));
            applyOutcome(outcome);
            if (outcome.exitCode() == 0) {
                inputMode = InputMode.NONE;
                refresh(access, level, true);
            }
            return;
        }
        if (inputMode == InputMode.METHOD_ARGUMENTS) {
            DeviceDescriptor selected = selectedDevice();
            if (selected == null || pendingMethod.isEmpty()) {
                setNotice("Selected method is no longer available", true);
                inputMode = InputMode.NONE;
                pendingMethod = "";
                return;
            }
            List<String> arguments = new ArrayList<>();
            arguments.add("call");
            arguments.add(selected.deviceId().toString());
            arguments.add(pendingMethod);
            arguments.addAll(ShellSyntax.tokenize(value));
            DeviceShellCommand.Outcome outcome = DeviceShellCommand.execute(access, arguments,
                    selector -> resolve(level, selector));
            applyOutcome(outcome);
            if (outcome.exitCode() == 0) {
                inputMode = InputMode.NONE;
                pendingMethod = "";
                refresh(access, level, true);
            }
        }
    }

    private void activate(DeviceAccess access, Level level) {
        if (pane == Pane.DEVICES) {
            if (tab == Tab.OVERVIEW) {
                tab = Tab.METHODS;
                detailIndex = 0;
                detailOffset = 0;
                pane = Pane.DETAIL;
                clampDetailSelection();
                setNotice(methods().isEmpty()
                        ? "The selected device advertises no callable methods"
                        : "Methods opened; press Enter to run the selected method",
                        methods().isEmpty());
            } else {
                pane = Pane.DETAIL;
                clearNotice();
            }
            return;
        }
        if (tab == Tab.METHODS) activateMethod(access, level);
        else if (tab == Tab.TEMPLATES) activateTemplate(access, level);
    }

    private void activateMethod(DeviceAccess access, Level level) {
        DeviceDescriptor device = selectedDevice();
        DeviceMethodDescriptor method = selectedMethod();
        if (device == null || method == null) {
            setNotice("No callable method selected", true);
            return;
        }
        if (!method.parameters().isEmpty()) {
            pendingMethod = method.name();
            inputMode = InputMode.METHOD_ARGUMENTS;
            setNotice("Enter " + methodSignature(method) + "; quote text containing spaces", false);
            return;
        }
        DeviceShellCommand.Outcome outcome = DeviceShellCommand.execute(access,
                List.of("call", device.deviceId().toString(), method.name()),
                selector -> resolve(level, selector));
        applyOutcome(outcome);
        refresh(access, level, true);
    }

    private void activateTemplate(DeviceAccess access, Level level) {
        DeviceDescriptor device = selectedDevice();
        if (device == null || !isPlc(device)) {
            setNotice("PLC templates can only be loaded into a selected PLC", true);
            return;
        }
        List<PlcProgramTemplates.Template> templates = PlcProgramTemplates.all();
        if (templates.isEmpty() || detailIndex < 0 || detailIndex >= templates.size()) {
            setNotice("No PLC template selected", true);
            return;
        }
        PlcProgramTemplates.Template template = templates.get(detailIndex);
        DeviceShellCommand.Outcome outcome = DeviceShellCommand.execute(access,
                List.of("call", device.deviceId().toString(), "program.set", template.source()),
                selector -> resolve(level, selector));
        applyOutcome(outcome.exitCode() == 0
                ? new DeviceShellCommand.Outcome(0, List.of("Loaded " + template.category()
                + "/" + template.id() + " into " + displayName(device)))
                : outcome);
        refresh(access, level, true);
    }

    private void click(int row, int column) {
        clearNotice();
        if (row == 1) {
            if (column < 12) selectTab(Tab.OVERVIEW);
            else if (column < 24) selectTab(Tab.METHODS);
            else selectTab(Tab.TEMPLATES);
            return;
        }
        if (row < FIRST_CONTENT_ROW || row >= FIRST_CONTENT_ROW + CONTENT_ROWS) return;
        int visibleIndex = row - FIRST_CONTENT_ROW;
        if (column <= DIVIDER_COLUMN) {
            int target = deviceOffset + visibleIndex;
            if (target < devices.size()) {
                deviceIndex = target;
                pane = Pane.DEVICES;
                detailIndex = 0;
                detailOffset = 0;
            }
        } else if (tab != Tab.OVERVIEW) {
            int target = detailOffset + visibleIndex;
            if (target < detailCount()) {
                detailIndex = target;
                pane = Pane.DETAIL;
            }
        }
    }

    private void moveSelection(int delta) {
        clearNotice();
        if (pane == Pane.DETAIL && tab != Tab.OVERVIEW) {
            int count = detailCount();
            if (count > 0) detailIndex = Math.max(0, Math.min(count - 1, detailIndex + delta));
            detailOffset = visibleOffset(detailIndex, detailOffset, count);
            return;
        }
        if (!devices.isEmpty()) {
            deviceIndex = Math.max(0, Math.min(devices.size() - 1, deviceIndex + delta));
            deviceOffset = visibleOffset(deviceIndex, deviceOffset, devices.size());
            detailIndex = 0;
            detailOffset = 0;
        }
    }

    private void changeTab(int delta) {
        Tab[] values = Tab.values();
        int next = Math.floorMod(tab.ordinal() + delta, values.length);
        selectTab(values[next]);
    }

    private void selectTab(Tab selected) {
        clearNotice();
        tab = selected;
        detailIndex = 0;
        detailOffset = 0;
        pane = selected == Tab.OVERVIEW ? Pane.DEVICES : pane;
        clampDetailSelection();
    }

    private void renderTabs(TerminalBuffer surface) {
        tab(surface, 0, 1, 12, "OVERVIEW", Tab.OVERVIEW);
        tab(surface, 12, 1, 12, "METHODS", Tab.METHODS);
        tab(surface, 24, 1, 16, "PLC TEMPLATES", Tab.TEMPLATES);
    }

    private void tab(TerminalBuffer surface, int x, int y, int width, String label, Tab candidate) {
        String value = center(label, width);
        draw(surface, x, y, value, tab == candidate ? BLACK : GRAY,
                tab == candidate ? CYAN : BLACK);
    }

    private void renderDevices(TerminalBuffer surface) {
        if (devices.isEmpty()) {
            draw(surface, 0, FIRST_CONTENT_ROW, " (none)", GRAY, BLACK);
            return;
        }
        deviceOffset = visibleOffset(deviceIndex, deviceOffset, devices.size());
        for (int visible = 0; visible < CONTENT_ROWS; visible++) {
            int index = deviceOffset + visible;
            if (index >= devices.size()) break;
            DeviceDescriptor descriptor = devices.get(index);
            String state = !descriptor.loaded() ? "-" : descriptor.online() ? "+" : "!";
            String label = state + " " + bounded(displayName(descriptor), DIVIDER_COLUMN - 2);
            boolean selected = index == deviceIndex;
            draw(surface, 0, FIRST_CONTENT_ROW + visible, pad(label, DIVIDER_COLUMN),
                    selected ? BLACK : descriptor.online() ? WHITE : RED,
                    selected ? GREEN : BLACK);
        }
    }

    private void renderOverview(TerminalBuffer surface) {
        DeviceDescriptor descriptor = selectedDevice();
        if (descriptor == null) {
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW, "No device selected", GRAY, BLACK);
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Name: " + displayName(descriptor));
        lines.add("Type: " + descriptor.typeName());
        lines.add("State: " + (!descriptor.loaded() ? "unloaded" : descriptor.online() ? "online" : "offline"));
        lines.add("Source: " + descriptor.modSource());
        lines.add("ID: " + descriptor.deviceId().toString().substring(0, 8));
        lines.add("Addr: " + descriptor.address());
        descriptor.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(4).forEach(entry -> lines.add(entry.getKey() + "=" + compact(entry.getValue())));
        for (int index = 0; index < Math.min(CONTENT_ROWS, lines.size()); index++) {
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW + index,
                    bounded(lines.get(index), RIGHT_WIDTH), WHITE, BLACK);
        }
    }

    private void renderMethods(TerminalBuffer surface) {
        List<DeviceMethodDescriptor> methods = methods();
        if (methods.isEmpty()) {
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW, "(no methods)", GRAY, BLACK);
            return;
        }
        detailOffset = visibleOffset(detailIndex, detailOffset, methods.size());
        for (int visible = 0; visible < CONTENT_ROWS; visible++) {
            int index = detailOffset + visible;
            if (index >= methods.size()) break;
            DeviceMethodDescriptor method = methods.get(index);
            boolean selected = index == detailIndex;
            String suffix = method.requiredPermission().equals("device.write") ? " *" : "";
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW + visible,
                    pad(bounded(method.name() + suffix, RIGHT_WIDTH), RIGHT_WIDTH),
                    selected ? BLACK : WHITE, selected ? CYAN : BLACK);
        }
    }

    private void renderTemplates(TerminalBuffer surface) {
        DeviceDescriptor descriptor = selectedDevice();
        if (descriptor == null || !isPlc(descriptor)) {
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW, "Select a PLC", RED, BLACK);
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW + 1, "to load programs", GRAY, BLACK);
            return;
        }
        List<PlcProgramTemplates.Template> templates = PlcProgramTemplates.all();
        detailOffset = visibleOffset(detailIndex, detailOffset, templates.size());
        for (int visible = 0; visible < CONTENT_ROWS; visible++) {
            int index = detailOffset + visible;
            if (index >= templates.size()) break;
            PlcProgramTemplates.Template template = templates.get(index);
            boolean selected = index == detailIndex;
            String category = switch (template.category()) {
                case "create" -> "C";
                case "mekanism" -> "M";
                case "securitycraft" -> "S";
                default -> "G";
            };
            String label = "[" + category + "] " + template.id();
            draw(surface, RIGHT_COLUMN, FIRST_CONTENT_ROW + visible,
                    pad(bounded(label, RIGHT_WIDTH), RIGHT_WIDTH),
                    selected ? BLACK : WHITE, selected ? BLUE : BLACK);
        }
    }

    private void renderFooter(TerminalBuffer surface) {
        String message = notice;
        if (!inputActive() && message.isBlank()) {
            if (pane == Pane.DETAIL && tab == Tab.METHODS && selectedMethod() != null) {
                message = selectedMethod().description();
            } else if (pane == Pane.DETAIL && tab == Tab.TEMPLATES && selectedTemplate() != null) {
                message = selectedTemplate().description();
            } else {
                message = "Ready";
            }
        }
        if (inputActive()) message = "> " + inputPrompt();
        draw(surface, 0, 13, pad(bounded(message, 40), 40), noticeError ? RED : CYAN, BLACK);
        draw(surface, 0, 14, "Arrows navigate  Tab tabs  Enter select", GRAY, BLACK);
        draw(surface, 0, 15, "F2/N DNS  F5/R refresh  Esc back/close", GRAY, BLACK);
    }

    private String detailHeading() {
        return switch (tab) {
            case OVERVIEW -> "OVERVIEW";
            case METHODS -> (pane == Pane.DETAIL ? "> " : "  ") + "METHODS " + detailCount();
            case TEMPLATES -> (pane == Pane.DETAIL ? "> " : "  ") + "PROGRAMS " + detailCount();
        };
    }

    private List<DeviceMethodDescriptor> methods() {
        DeviceDescriptor descriptor = selectedDevice();
        if (descriptor == null) return List.of();
        return descriptor.methods().stream().sorted(Comparator.comparing(DeviceMethodDescriptor::name)).toList();
    }

    private DeviceDescriptor selectedDevice() {
        return deviceIndex >= 0 && deviceIndex < devices.size() ? devices.get(deviceIndex) : null;
    }

    private DeviceMethodDescriptor selectedMethod() {
        List<DeviceMethodDescriptor> methods = methods();
        return detailIndex >= 0 && detailIndex < methods.size() ? methods.get(detailIndex) : null;
    }

    private PlcProgramTemplates.Template selectedTemplate() {
        List<PlcProgramTemplates.Template> templates = PlcProgramTemplates.all();
        return detailIndex >= 0 && detailIndex < templates.size() ? templates.get(detailIndex) : null;
    }

    private int detailCount() {
        return switch (tab) {
            case OVERVIEW -> 0;
            case METHODS -> methods().size();
            case TEMPLATES -> PlcProgramTemplates.all().size();
        };
    }

    private void clampDeviceSelection() {
        deviceIndex = devices.isEmpty() ? 0 : Math.max(0, Math.min(deviceIndex, devices.size() - 1));
        deviceOffset = visibleOffset(deviceIndex, deviceOffset, devices.size());
    }

    private void clampDetailSelection() {
        int count = detailCount();
        detailIndex = count == 0 ? 0 : Math.max(0, Math.min(detailIndex, count - 1));
        detailOffset = visibleOffset(detailIndex, detailOffset, count);
    }

    private static int visibleOffset(int index, int offset, int count) {
        if (count <= CONTENT_ROWS) return 0;
        int result = Math.max(0, Math.min(offset, count - CONTENT_ROWS));
        if (index < result) result = index;
        if (index >= result + CONTENT_ROWS) result = index - CONTENT_ROWS + 1;
        return Math.max(0, Math.min(result, count - CONTENT_ROWS));
    }

    private String displayName(DeviceDescriptor descriptor) {
        return displayName(descriptor, preferredNames);
    }

    private static String displayName(DeviceDescriptor descriptor, Map<UUID, String> names) {
        String dns = names.get(descriptor.deviceId());
        return dns == null || dns.isBlank() ? descriptor.displayName() : dns;
    }

    private static boolean isPlc(DeviceDescriptor descriptor) {
        return descriptor.capabilities().contains("plc")
                || "programmable_logic_controller".equals(descriptor.typeName());
    }

    private static UUID resolve(Level level, String selector) {
        if (selector == null) return null;
        try {
            return UUID.fromString(selector);
        } catch (IllegalArgumentException ignored) {
            return level == null ? null
                    : RednetNetwork.resolveAddress(level, selector).map(RednetAddress::deviceId).orElse(null);
        }
    }

    private static String methodSignature(DeviceMethodDescriptor method) {
        StringBuilder result = new StringBuilder(method.name()).append('(');
        for (int index = 0; index < method.parameters().size(); index++) {
            if (index > 0) result.append(' ');
            DeviceParameterDescriptor parameter = method.parameters().get(index);
            result.append(parameter.name()).append(':')
                    .append(parameter.type().name().toLowerCase(Locale.ROOT));
            if (!parameter.required()) result.append('?');
        }
        return result.append(')').toString();
    }

    private void applyOutcome(DeviceShellCommand.Outcome outcome) {
        String message = outcome.lines().isEmpty() ? (outcome.exitCode() == 0 ? "Done" : "Operation failed")
                : String.join(" | ", outcome.lines());
        setNotice(message, outcome.exitCode() != 0);
    }

    private void setNotice(String value, boolean error) {
        String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        notice = bounded(safe, MAX_NOTICE_LENGTH);
        noticeError = error;
    }

    private void clearNotice() {
        notice = "";
        noticeError = false;
    }

    private static String compact(DeviceValue value) {
        return bounded(DeviceShellCommand.formatValue(value), RIGHT_WIDTH - 1);
    }

    private static void fill(TerminalBuffer surface, char value, int foreground, int background) {
        for (int row = 0; row < surface.height(); row++) {
            for (int column = 0; column < surface.width(); column++) {
                put(surface, column, row, value, foreground, background);
            }
        }
    }

    private static void draw(TerminalBuffer surface, int x, int y, String value,
                             int foreground, int background) {
        if (value == null || y < 0 || y >= surface.height()) return;
        for (int index = 0; index < value.length() && x + index < surface.width(); index++) {
            if (x + index >= 0) put(surface, x + index, y, value.charAt(index), foreground, background);
        }
    }

    private static void put(TerminalBuffer surface, int x, int y, char value,
                            int foreground, int background) {
        if (x < 0 || y < 0 || x >= surface.width() || y >= surface.height()) return;
        if (surface.characterAt(x, y) != value || surface.foregroundAt(x, y) != foreground
                || surface.backgroundAt(x, y) != background) {
            surface.setCell(x, y, value, foreground, background);
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    private static String pad(String value, int width) {
        String bounded = bounded(value, width);
        return bounded + " ".repeat(Math.max(0, width - bounded.length()));
    }

    private static String center(String value, int width) {
        String bounded = bounded(value, width);
        int left = Math.max(0, (width - bounded.length()) / 2);
        return " ".repeat(left) + bounded + " ".repeat(Math.max(0, width - left - bounded.length()));
    }

    private static int boundedIndex(int value) {
        return Math.max(0, Math.min(DEVICE_LIMIT * 2, value));
    }

    private static <T extends Enum<T>> T readEnum(CompoundTag tag, String key,
                                                   Class<T> type, T fallback) {
        if (!tag.contains(key, Tag.TAG_STRING)) return fallback;
        try {
            return Enum.valueOf(type, tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
