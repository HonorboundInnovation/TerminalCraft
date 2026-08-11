package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.TerminalBuffer;
import com.malice.terminalcraft.scada.ScadaAction;
import com.malice.terminalcraft.scada.ScadaHmiDashboard;
import com.malice.terminalcraft.scada.ScadaHmiFrame;
import com.malice.terminalcraft.scada.ScadaHmiPage;
import com.malice.terminalcraft.scada.ScadaHmiRenderer;
import com.malice.terminalcraft.scada.ScadaHmiWidget;
import com.malice.terminalcraft.scada.ScadaRuntime;
import com.malice.terminalcraft.scada.ScadaSavedData;
import com.malice.terminalcraft.scada.ScadaScalar;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/** Server-authoritative graphical advanced-HMI viewer and normalized-grid layout editor. */
final class ScadaHmiProgram {
    private enum InputMode { NONE, ADD_WIDGET, EDIT_WIDGET }

    private boolean active;
    private boolean designMode;
    private InputMode inputMode = InputMode.NONE;
    private int dashboardIndex;
    private int widgetIndex;
    private String notice = "";
    private boolean noticeError;
    private int noticePolls;
    private String pendingDelete = "";

    boolean active() { return active; }
    boolean inputActive() { return active && inputMode != InputMode.NONE; }
    String title() { return designMode ? "HMI DESIGNER" : "ADVANCED HMI"; }

    String inputPrompt() {
        return switch (inputMode) {
            case ADD_WIDGET -> "id type x y w h source|- arg|- label";
            case EDIT_WIDGET -> "replace: id type x y w h source|- arg|- label";
            case NONE -> "";
        };
    }

    boolean open(DeviceAccess access, Level level, TerminalBuffer surface) {
        if (!(level instanceof ServerLevel serverLevel) || access == null
                || !ScadaSavedData.get(serverLevel.getServer()).authorized(access.context(), ScadaAction.VIEW)) {
            active = false;
            return false;
        }
        active = true;
        designMode = false;
        inputMode = InputMode.NONE;
        dashboardIndex = 0;
        widgetIndex = 0;
        pendingDelete = "";
        setNotice("Up/Down dashboard  Left/Right page  Tab widget  F2 design", false);
        render(access, level, surface);
        return true;
    }

    void handle(ControlCenterProgram.Action action, int row, int column, String text,
                DeviceAccess access, Level level, TerminalBuffer surface) {
        if (!active || action == null || !(level instanceof ServerLevel serverLevel) || access == null) return;
        ScadaSavedData data = ScadaSavedData.get(serverLevel.getServer());
        if (!data.authorized(access.context(), ScadaAction.VIEW)) {
            close(surface);
            return;
        }
        if (inputActive() && action != ControlCenterProgram.Action.SUBMIT_TEXT
                && action != ControlCenterProgram.Action.CANCEL_INPUT) return;
        if (action != ControlCenterProgram.Action.DELETE) pendingDelete = "";

        switch (action) {
            case CLOSE -> close(surface);
            case CANCEL_INPUT -> { inputMode = InputMode.NONE; setNotice("Edit cancelled", false); }
            case SUBMIT_TEXT -> submitWidget(text, access, serverLevel, data);
            case REFRESH -> setNotice("HMI refreshed at tick " + serverLevel.getGameTime(), false);
            case POLL -> {
                if (noticePolls > 0 && --noticePolls == 0) notice = "";
            }
            case RENAME -> toggleDesign(access, data);
            case UP -> {
                if (designMode) moveSelected(access, data, serverLevel, 0, -1);
                else moveDashboard(data, -1);
            }
            case DOWN -> {
                if (designMode) moveSelected(access, data, serverLevel, 0, 1);
                else moveDashboard(data, 1);
            }
            case LEFT -> {
                if (designMode) moveSelected(access, data, serverLevel, -1, 0);
                else movePage(access, data, serverLevel, -1);
            }
            case RIGHT -> {
                if (designMode) moveSelected(access, data, serverLevel, 1, 0);
                else movePage(access, data, serverLevel, 1);
            }
            case NEXT_TAB -> cycleWidget(data, 1);
            case PREVIOUS_TAB -> cycleWidget(data, -1);
            case ACTIVATE -> {
                if (designMode) beginEdit(data);
                else activateSelected(access, data, serverLevel);
            }
            case CLICK -> click(access, data, serverLevel, column, row, surface.width(), surface.height());
            case ADD -> beginAdd(access, data);
            case EDIT -> beginEdit(data);
            case DELETE -> deleteSelected(access, data, serverLevel);
            case RESIZE_UP -> resizeSelected(access, data, serverLevel, 0, -1);
            case RESIZE_DOWN -> resizeSelected(access, data, serverLevel, 0, 1);
            case RESIZE_LEFT -> resizeSelected(access, data, serverLevel, -1, 0);
            case RESIZE_RIGHT -> resizeSelected(access, data, serverLevel, 1, 0);
        }
        if (active) render(access, level, surface);
    }

    void render(DeviceAccess access, Level level, TerminalBuffer surface) {
        if (!active || surface == null || !(level instanceof ServerLevel serverLevel)) return;
        ScadaSavedData data = ScadaSavedData.get(serverLevel.getServer());
        List<ScadaHmiDashboard> dashboards = data.hmiDashboards();
        if (dashboards.isEmpty()) {
            blank(surface);
            draw(surface, 1, 1, "ADVANCED HMI", 15, 5);
            draw(surface, 1, 4, "No dashboards configured.", 4, 15);
            draw(surface, 1, 6, "Use: scada hmi create ...", 8, 15);
            draw(surface, 1, 7, "Then add pages and widgets.", 8, 15);
            surface.setCursorBlink(false);
            return;
        }
        dashboardIndex = Math.floorMod(dashboardIndex, dashboards.size());
        ScadaHmiDashboard dashboard = dashboards.get(dashboardIndex);
        clampWidget(dashboard);
        String highlighted = designMode && !dashboard.selectedPage().widgets().isEmpty()
                ? dashboard.selectedPage().widgets().get(widgetIndex).id() : selectedInteractive(dashboard);
        ScadaHmiFrame frame = ScadaHmiRenderer.render(data, dashboard, serverLevel.getGameTime(),
                surface.width(), surface.height(), highlighted == null ? "" : highlighted);
        apply(surface, frame);
        if (designMode) {
            String selected = dashboard.selectedPage().widgets().isEmpty() ? "(none)"
                    : dashboard.selectedPage().widgets().get(widgetIndex).id();
            overlay(surface, "DESIGN " + dashboard.name() + "/" + dashboard.activePage() + "  " + selected,
                    15, 9);
        }
        if (!notice.isBlank()) overlayBottom(surface, notice, noticeError ? 14 : 4, 15);
        surface.setCursorBlink(false);
    }

    void close(TerminalBuffer surface) {
        active = false;
        designMode = false;
        inputMode = InputMode.NONE;
        pendingDelete = "";
        if (surface != null) surface.setCursorBlink(false);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Active", active);
        tag.putBoolean("Design", designMode);
        tag.putString("Input", inputMode.name());
        tag.putInt("Dashboard", Math.max(0, dashboardIndex));
        tag.putInt("Widget", Math.max(0, widgetIndex));
        if (!notice.isBlank()) tag.putString("Notice", bounded(notice, 120));
        tag.putBoolean("NoticeError", noticeError);
        return tag;
    }

    void load(CompoundTag tag) {
        active = tag != null && tag.getBoolean("Active");
        designMode = active && tag.getBoolean("Design");
        inputMode = tag != null && tag.contains("Input", Tag.TAG_STRING)
                ? readInput(tag.getString("Input")) : InputMode.NONE;
        if (!active) inputMode = InputMode.NONE;
        dashboardIndex = tag == null ? 0 : Math.max(0, Math.min(255, tag.getInt("Dashboard")));
        widgetIndex = tag == null ? 0 : Math.max(0, Math.min(255, tag.getInt("Widget")));
        notice = tag != null && tag.contains("Notice", Tag.TAG_STRING) ? bounded(tag.getString("Notice"), 120) : "";
        noticeError = tag != null && tag.getBoolean("NoticeError");
        noticePolls = notice.isBlank() ? 0 : 4;
        pendingDelete = "";
    }

    private void toggleDesign(DeviceAccess access, ScadaSavedData data) {
        if (!designMode && !data.authorized(access.context(), ScadaAction.CONFIGURE)) {
            setNotice("Engineer role or higher is required for HMI design", true);
            return;
        }
        designMode = !designMode;
        widgetIndex = 0;
        setNotice(designMode
                ? "Designer: click/Tab select, arrows move, Shift+arrows resize, A add, E edit, Del twice"
                : "Operator view: click buttons, arrows change dashboard/page, Tab selects", false);
    }

    private void moveDashboard(ScadaSavedData data, int delta) {
        List<ScadaHmiDashboard> dashboards = data.hmiDashboards();
        if (dashboards.isEmpty()) return;
        dashboardIndex = Math.floorMod(dashboardIndex + delta, dashboards.size());
        widgetIndex = 0;
        setNotice("Dashboard " + dashboards.get(dashboardIndex).name(), false);
    }

    private void movePage(DeviceAccess access, ScadaSavedData data, ServerLevel level, int delta) {
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (dashboard == null || dashboard.pages().isEmpty()) return;
        int current = 0;
        for (int index = 0; index < dashboard.pages().size(); index++) {
            if (dashboard.pages().get(index).name().equals(dashboard.activePage())) current = index;
        }
        String next = dashboard.pages().get(Math.floorMod(current + delta, dashboard.pages().size())).name();
        apply(data.selectHmiPage(access.context(), dashboard.name(), next, level.getGameTime()));
        widgetIndex = 0;
    }

    private void cycleWidget(ScadaSavedData data, int delta) {
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (dashboard == null) return;
        List<ScadaHmiWidget> widgets = designMode ? dashboard.selectedPage().widgets()
                : dashboard.selectedPage().widgets().stream().filter(widget -> widget.type().interactive()).toList();
        if (widgets.isEmpty()) { widgetIndex = 0; setNotice("This page has no selectable widgets", true); return; }
        widgetIndex = Math.floorMod(widgetIndex + delta, widgets.size());
        setNotice("Selected " + widgets.get(widgetIndex).id(), false);
    }

    private void activateSelected(DeviceAccess access, ScadaSavedData data, ServerLevel level) {
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (dashboard == null) return;
        List<ScadaHmiWidget> interactive = dashboard.selectedPage().widgets().stream()
                .filter(widget -> widget.type().interactive()).toList();
        if (interactive.isEmpty()) { setNotice("This page has no interactive widgets", true); return; }
        widgetIndex = Math.floorMod(widgetIndex, interactive.size());
        apply(ScadaRuntime.activateHmiWidget(level.getServer(), access.context(), dashboard.name(),
                interactive.get(widgetIndex).id(), level.getGameTime()));
    }

    private void click(DeviceAccess access, ScadaSavedData data, ServerLevel level,
                       int column, int row, int width, int height) {
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (dashboard == null) return;
        ScadaHmiWidget widget = ScadaHmiRenderer.widgetAt(dashboard, width, height, column, row, false);
        if (widget == null) return;
        List<ScadaHmiWidget> selection = designMode ? dashboard.selectedPage().widgets()
                : dashboard.selectedPage().widgets().stream().filter(candidate -> candidate.type().interactive()).toList();
        for (int index = 0; index < selection.size(); index++) if (selection.get(index).id().equals(widget.id())) widgetIndex = index;
        if (!designMode && widget.type().interactive()) {
            apply(ScadaRuntime.activateHmiWidget(level.getServer(), access.context(), dashboard.name(),
                    widget.id(), level.getGameTime()));
        } else setNotice("Selected " + widget.id(), false);
    }

    private void beginAdd(DeviceAccess access, ScadaSavedData data) {
        if (!designMode || !data.authorized(access.context(), ScadaAction.CONFIGURE)) {
            setNotice("Open designer with F2 before adding a widget", true); return;
        }
        inputMode = InputMode.ADD_WIDGET;
        setNotice("Example: temp value 0 0 6 3 factory.temp - Temperature", false);
    }

    private void beginEdit(ScadaSavedData data) {
        if (!designMode) return;
        ScadaHmiWidget widget = selectedWidget(data);
        if (widget == null) { setNotice("Select a widget before editing", true); return; }
        inputMode = InputMode.EDIT_WIDGET;
        setNotice("Current: " + specification(widget), false);
    }

    private void submitWidget(String text, DeviceAccess access, ServerLevel level, ScadaSavedData data) {
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (dashboard == null) { inputMode = InputMode.NONE; return; }
        try {
            List<String> tokens = ShellSyntax.tokenize(text == null ? "" : text);
            if (tokens.size() < 8) throw new IllegalArgumentException(
                    "expected: id type x y w h source|- arg|- [label...]");
            ScadaHmiWidget.Type type = ScadaHmiWidget.Type.parse(tokens.get(1));
            double minimum = 0;
            double maximum = 100;
            ScadaScalar actionValue = null;
            String argument = tokens.get(7);
            if (type == ScadaHmiWidget.Type.GAUGE) {
                String[] range = argument.split(":", -1);
                if (range.length != 2) throw new IllegalArgumentException("gauge arg must be min:max");
                minimum = Double.parseDouble(range[0]);
                maximum = Double.parseDouble(range[1]);
            } else if (type == ScadaHmiWidget.Type.BUTTON && !"command".equalsIgnoreCase(argument)) {
                actionValue = ScadaScalar.parseToken(argument);
            }
            String label = tokens.size() > 8 ? String.join(" ", tokens.subList(8, tokens.size())) : tokens.get(0);
            ScadaHmiWidget widget = new ScadaHmiWidget(tokens.get(0), type,
                    boundedInt(tokens.get(2), 0, 11, "x"), boundedInt(tokens.get(3), 0, 11, "y"),
                    boundedInt(tokens.get(4), 1, 12, "width"), boundedInt(tokens.get(5), 1, 12, "height"),
                    "-".equals(tokens.get(6)) ? "" : tokens.get(6), label, minimum, maximum, actionValue);
            if (inputMode == InputMode.EDIT_WIDGET) {
                ScadaHmiWidget selected = selectedWidget(data);
                if (selected != null && !selected.id().equals(widget.id())) {
                    throw new IllegalArgumentException("editing must retain widget id " + selected.id());
                }
            }
            ScadaSavedData.Operation result = data.putHmiWidget(access.context(), dashboard.name(),
                    dashboard.activePage(), widget, level.getGameTime());
            apply(result);
            if (result.success()) inputMode = InputMode.NONE;
        } catch (IllegalArgumentException invalid) {
            setNotice(invalid.getMessage() == null ? "invalid HMI widget" : invalid.getMessage(), true);
        }
    }

    private void moveSelected(DeviceAccess access, ScadaSavedData data, ServerLevel level, int dx, int dy) {
        ScadaHmiWidget widget = selectedWidget(data);
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (widget == null || dashboard == null) return;
        int x = Math.max(0, Math.min(ScadaHmiWidget.GRID_WIDTH - widget.width(), widget.x() + dx));
        int y = Math.max(0, Math.min(ScadaHmiWidget.GRID_HEIGHT - widget.height(), widget.y() + dy));
        apply(data.putHmiWidget(access.context(), dashboard.name(), dashboard.activePage(),
                widget.withBounds(x, y, widget.width(), widget.height()), level.getGameTime()));
    }

    private void resizeSelected(DeviceAccess access, ScadaSavedData data, ServerLevel level, int dw, int dh) {
        if (!designMode) return;
        ScadaHmiWidget widget = selectedWidget(data);
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (widget == null || dashboard == null) return;
        int width = Math.max(1, Math.min(ScadaHmiWidget.GRID_WIDTH - widget.x(), widget.width() + dw));
        int height = Math.max(1, Math.min(ScadaHmiWidget.GRID_HEIGHT - widget.y(), widget.height() + dh));
        apply(data.putHmiWidget(access.context(), dashboard.name(), dashboard.activePage(),
                widget.withBounds(widget.x(), widget.y(), width, height), level.getGameTime()));
    }

    private void deleteSelected(DeviceAccess access, ScadaSavedData data, ServerLevel level) {
        if (!designMode) return;
        ScadaHmiWidget widget = selectedWidget(data);
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (widget == null || dashboard == null) return;
        if (!pendingDelete.equals(widget.id())) {
            pendingDelete = widget.id();
            setNotice("Press Delete again to remove " + widget.id(), true);
            return;
        }
        apply(data.removeHmiWidget(access.context(), dashboard.name(), dashboard.activePage(),
                widget.id(), level.getGameTime()));
        pendingDelete = "";
        widgetIndex = Math.max(0, widgetIndex - 1);
    }

    private ScadaHmiDashboard selectedDashboard(ScadaSavedData data) {
        List<ScadaHmiDashboard> dashboards = data.hmiDashboards();
        if (dashboards.isEmpty()) return null;
        dashboardIndex = Math.floorMod(dashboardIndex, dashboards.size());
        return dashboards.get(dashboardIndex);
    }

    private ScadaHmiWidget selectedWidget(ScadaSavedData data) {
        ScadaHmiDashboard dashboard = selectedDashboard(data);
        if (dashboard == null || dashboard.selectedPage().widgets().isEmpty()) return null;
        widgetIndex = Math.floorMod(widgetIndex, dashboard.selectedPage().widgets().size());
        return dashboard.selectedPage().widgets().get(widgetIndex);
    }

    private String selectedInteractive(ScadaHmiDashboard dashboard) {
        List<ScadaHmiWidget> interactive = dashboard.selectedPage().widgets().stream()
                .filter(widget -> widget.type().interactive()).toList();
        if (interactive.isEmpty()) return "";
        widgetIndex = Math.floorMod(widgetIndex, interactive.size());
        return interactive.get(widgetIndex).id();
    }

    private void clampWidget(ScadaHmiDashboard dashboard) {
        int size = designMode ? dashboard.selectedPage().widgets().size()
                : (int) dashboard.selectedPage().widgets().stream().filter(widget -> widget.type().interactive()).count();
        widgetIndex = size == 0 ? 0 : Math.floorMod(widgetIndex, size);
    }

    private void apply(ScadaSavedData.Operation operation) {
        setNotice(operation.message(), !operation.success());
    }

    private void setNotice(String message, boolean error) {
        notice = bounded(message, 120);
        noticeError = error;
        noticePolls = notice.isBlank() ? 0 : 4;
    }

    private static void apply(TerminalBuffer surface, ScadaHmiFrame frame) {
        for (int color = 0; color < frame.palette().size(); color++) surface.setPaletteColor(color, frame.palette().get(color));
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                surface.setCell(x, y, frame.lines().get(y).charAt(x),
                        Character.digit(frame.foreground().get(y).charAt(x), 16),
                        Character.digit(frame.background().get(y).charAt(x), 16));
            }
        }
    }

    private static void blank(TerminalBuffer surface) {
        for (int row = 0; row < surface.height(); row++) {
            for (int column = 0; column < surface.width(); column++) surface.setCell(column, row, ' ', 0, 15);
        }
    }

    private static void overlay(TerminalBuffer surface, String value, int fg, int bg) {
        for (int x = 0; x < surface.width(); x++) surface.setCell(x, 0, ' ', fg, bg);
        draw(surface, 0, 0, bounded(value, surface.width()), fg, bg);
    }

    private static void overlayBottom(TerminalBuffer surface, String value, int fg, int bg) {
        int row = surface.height() - 1;
        for (int x = 0; x < surface.width(); x++) surface.setCell(x, row, ' ', fg, bg);
        draw(surface, 0, row, bounded(value, surface.width()), fg, bg);
    }

    private static void draw(TerminalBuffer surface, int x, int y, String value, int fg, int bg) {
        if (value == null || y < 0 || y >= surface.height()) return;
        for (int index = 0; index < value.length() && x + index < surface.width(); index++) {
            if (x + index >= 0) surface.setCell(x + index, y, value.charAt(index), fg, bg);
        }
    }

    private static String specification(ScadaHmiWidget widget) {
        String argument = widget.type() == ScadaHmiWidget.Type.GAUGE
                ? widget.minimum() + ":" + widget.maximum()
                : widget.type() == ScadaHmiWidget.Type.BUTTON
                ? widget.actionValue() == null ? "command" : token(widget.actionValue()) : "-";
        return widget.id() + " " + widget.type().name().toLowerCase(Locale.ROOT) + " "
                + widget.x() + " " + widget.y() + " " + widget.width() + " " + widget.height() + " "
                + (widget.source().isBlank() ? "-" : widget.source()) + " " + argument + " " + widget.label();
    }

    private static String token(ScadaScalar value) {
        return switch (value.type()) {
            case NUMBER -> "n:" + value.numberValue();
            case BOOLEAN -> "b:" + value.booleanValue();
            case STRING -> "s:" + value.textValue();
        };
    }

    private static int boundedInt(String value, int minimum, int maximum, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be " + minimum + ".." + maximum);
        }
    }

    private static String bounded(String value, int maximum) {
        String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static InputMode readInput(String value) {
        try { return InputMode.valueOf(value); }
        catch (IllegalArgumentException invalid) { return InputMode.NONE; }
    }
}
