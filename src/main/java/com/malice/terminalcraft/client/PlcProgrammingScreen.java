package com.malice.terminalcraft.client;

import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.menu.PlcProgrammingMenu;
import com.malice.terminalcraft.network.ModNetwork;
import com.malice.terminalcraft.plc.PlcProgram;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Full PLC programming, commissioning, and live-watch interface. */
public final class PlcProgrammingScreen extends AbstractContainerScreen<PlcProgrammingMenu> {
    private static final int PANEL_WIDTH = 640;
    private static final int PANEL_HEIGHT = 410;
    private static final int LINE_HEIGHT = 10;
    private static final int HEADER = 24;
    private static final int TOOLBAR = 66;

    private TextEditorBuffer editor;
    private int scrollLine;
    private int scrollColumn;
    private boolean requestPending;
    private boolean ladderMode;
    private PlcLadderModel ladder;
    private String draggedSignal;
    private int draggedRung = -1;
    private String notice = "Ready — edit the program, then Compile & Load";
    private int noticeTicks;

    public PlcProgrammingScreen(PlcProgrammingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_WIDTH;
        imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        imageWidth = Math.max(300, Math.min(PANEL_WIDTH, width - 12));
        imageHeight = Math.max(220, Math.min(PANEL_HEIGHT, height - 12));
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;
        editor = new TextEditorBuffer(currentSource());
        ladder = PlcLadderModel.fromSource(currentSource());
        addToolbarButtons();
    }

    private void addToolbarButtons() {
        int x = leftPos + 12;
        int y = topPos + 31;
        if (compactLayout()) {
            addButton("COMPILE", x, y, 88, this::compile);
            addButton("RUN", x + 92, y, 40, () -> action(ModNetwork.PlcAction.RUN));
            addButton("STOP", x + 136, y, 40, () -> action(ModNetwork.PlcAction.STOP));
            addButton("RESET", x + 180, y, 48, () -> action(ModNetwork.PlcAction.RESET));
            addButton("ALARM", x + 232, y, 56, () -> action(ModNetwork.PlcAction.ACK_ALARM));
            for (int slot = 0; slot < 4; slot++) {
                final int selected = slot;
                addButton("SAVE " + slot, x + slot * 49,
                        y + 24, 45, () -> saveSlot(selected));
                addButton("LOAD " + slot, x + (narrowLayout() ? slot * 49 : 196 + slot * 49),
                        y + (narrowLayout() ? 48 : 24), 45, () -> loadSlot(selected));
            }
            return;
        }
        addButton("COMPILE & LOAD", x, y, 108, this::compile);
        addButton("RUN", x + 112, y, 48, () -> action(ModNetwork.PlcAction.RUN));
        addButton("STOP", x + 164, y, 48, () -> action(ModNetwork.PlcAction.STOP));
        addButton("RESET", x + 216, y, 54, () -> action(ModNetwork.PlcAction.RESET));
        addButton("ACK ALARM", x + 274, y, 76, () -> action(ModNetwork.PlcAction.ACK_ALARM));
        for (int slot = 0; slot < 4; slot++) {
            final int selected = slot;
            addButton("SAVE " + slot, x + slot * 51, y + 24, 47, () -> saveSlot(selected));
            addButton("LOAD " + slot, x + 204 + slot * 51, y + 24, 47, () -> loadSlot(selected));
        }
    }

    private void addButton(String label, int x, int y, int buttonWidth, Runnable action) {
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal(label), button -> action.run())
                .bounds(x, y, buttonWidth, 20).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (noticeTicks > 0) noticeTicks--;
        ProgrammableLogicControllerBlockEntity plc = currentPlc();
        String visibleSource = ladderMode && ladder != null ? ladder.toSource() : editor == null ? "" : editor.text();
        if (plc != null && editor != null && !editor.dirty() && !requestPending
                && !plc.programSource().equals(visibleSource)) {
            editor.setInitialText(plc.programSource());
            ladder = PlcLadderModel.fromSource(plc.programSource());
            scrollLine = 0;
            scrollColumn = 0;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos, top = topPos, right = left + imageWidth, bottom = top + imageHeight;
        graphics.fill(left - 4, top - 4, right + 4, bottom + 4, 0xFF050708);
        graphics.fill(left, top, right, bottom, 0xFF101820);
        graphics.fill(left, top, right, top + HEADER, 0xFF1B4053);
        graphics.drawString(font, "TERMINALCRAFT // PLC PROGRAMMER", left + 10, top + 7, 0xFFE7F4FF, false);
        String modeLabel = ladderMode ? "LADDER WORKSPACE [F7]" : "IEC-STYLE CONTROL LOGIC [F7]";
        graphics.drawString(font, modeLabel, right - Math.min(190, font.width(modeLabel)), top + 7, 0xFF9BC4D5, false);
        int editorLeft = editorLeft();
        int editorTop = editorTop();
        int editorRight = editorRight();
        int editorBottom = editorBottom();
        graphics.fill(editorLeft, editorTop - 4, editorRight, editorBottom, 0xFF080D11);
        if (ladderMode) renderLadder(graphics, editorLeft, editorTop, editorRight, editorBottom);
        else renderEditor(graphics, editorLeft, editorTop, editorRight, editorBottom);
        if (compactLayout() || ladderMode) {
            if (ladderMode) {
                // The ladder canvas includes its own signal palette.
            } else {
                renderCompactStatus(graphics, left + 12, top + (narrowLayout() ? 107 : 83), right - 12);
            }
        } else {
            graphics.fill(left + 438, top + 28, right - 8, bottom - 28, 0xFF0B1217);
            renderWatch(graphics, left + 448, top + 38, right - 16, bottom - 36);
        }
        graphics.drawString(font, notice, left + 12, bottom - 19,
                noticeTicks > 0 ? (notice.startsWith("Error") ? 0xFFFF7777 : 0xFF8FE6A5) : 0xFF91A8B5, false);
    }

    private void renderEditor(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.drawString(font, "PROGRAM SOURCE", left + 8, top - 16, 0xFF8BD8F2, false);
        graphics.enableScissor(left, top, right, bottom);
        List<String> lines = editor == null ? List.of("") : editor.lines();
        int visible = Math.max(1, (bottom - top) / LINE_HEIGHT);
        int maxLine = Math.max(0, lines.size() - visible);
        scrollLine = Math.min(scrollLine, maxLine);
        for (int visual = 0; visual < visible; visual++) {
            int index = scrollLine + visual;
            if (index >= lines.size()) break;
            int y = top + visual * LINE_HEIGHT;
            graphics.drawString(font, String.format("%03d", index + 1), left + 6, y, 0xFF536775, false);
            String line = lines.get(index);
            String visibleText = line.substring(Math.min(scrollColumn, line.length()));
            visibleText = fitToWidth(visibleText, right - left - 46);
            graphics.drawString(font, visibleText, left + 34, y, 0xFFD8E8F0, false);
        }
        if (editor != null) {
            TextEditorBuffer.Position position = editor.position(editor.cursor());
            int visual = position.line() - scrollLine;
            if (visual >= 0 && visual < visible && (System.currentTimeMillis() / 500L) % 2 == 0) {
                String line = position.line() < lines.size() ? lines.get(position.line()) : "";
                int column = Math.max(0, position.column() - Math.min(scrollColumn, line.length()));
                int x = left + 34 + font.width(line.substring(Math.min(scrollColumn, line.length()),
                        Math.min(line.length(), Math.min(scrollColumn, line.length()) + column)));
                graphics.fill(x, top + visual * LINE_HEIGHT - 1, x + 1, top + visual * LINE_HEIGHT + 9, 0xFFFFFFFF);
            }
        }
        graphics.disableScissor();
        graphics.drawString(font, "Ctrl+S compile   Ctrl+Z/Y undo/redo   Tab inserts spaces", left + 6, bottom + 7,
                0xFF718A99, false);
    }

    private void renderLadder(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.drawString(font, "LADDER LOGIC — drag a signal onto a rung; drag a rung to reorder",
                left + 8, top - 16, 0xFF8BD8F2, false);
        int paletteLeft = Math.max(left + 220, right - 154);
        graphics.fill(paletteLeft, top, right, bottom, 0xFF0D171E);
        graphics.drawString(font, "SIGNAL PALETTE", paletteLeft + 8, top + 8, 0xFFF1FA8C, false);
        int paletteRow = top + 25;
        for (String signal : ladderSignals()) {
            if (paletteRow + 18 >= bottom) break;
            graphics.fill(paletteLeft + 5, paletteRow - 2, right - 5, paletteRow + 14, 0xFF1C3440);
            graphics.drawString(font, fitToWidth(signal, right - paletteLeft - 20), paletteLeft + 10,
                    paletteRow + 2, 0xFFD8E8F0, false);
            paletteRow += 19;
        }
        int railRight = paletteLeft - 14;
        int row = top + 28;
        int rungHeight = 42;
        List<PlcLadderModel.Rung> rungs = ladder == null ? List.of() : ladder.rungs();
        for (int index = 0; index < rungs.size() && row + rungHeight < bottom; index++) {
            PlcLadderModel.Rung rung = rungs.get(index);
            int lineY = row + 19;
            graphics.drawString(font, String.format("%02d", index + 1), left + 5, row + 14, 0xFF536775, false);
            graphics.fill(left + 31, lineY, railRight, lineY + 2, 0xFF607D8B);
            graphics.fill(left + 35, lineY - 8, left + 40, lineY + 10, 0xFF607D8B);
            graphics.fill(railRight - 5, lineY - 8, railRight, lineY + 10, 0xFF607D8B);
            graphics.drawString(font, "[ " + fitToWidth(rung.expression(), Math.max(25, railRight - left - 100)) + " ]",
                    left + 50, row + 7, 0xFF8BE9FD, false);
            graphics.drawString(font, "( " + fitToWidth(rung.output(), 18) + " )",
                    Math.max(left + 50, railRight - 76), row + 23, 0xFF50FA7B, false);
            row += rungHeight;
        }
        if (rungs.isEmpty()) {
            graphics.drawString(font, "No RUNG instructions yet. Add one in source mode, then return here.",
                    left + 12, top + 36, 0xFFFFB86C, false);
        }
        graphics.drawString(font, "F7 toggles source/ladder • Compile & Load accepts the generated source",
                left + 6, bottom + 7, 0xFF718A99, false);
    }

    private List<String> ladderSignals() {
        ProgrammableLogicControllerBlockEntity plc = currentPlc();
        if (plc == null) return List.of();
        List<String> signals = new java.util.ArrayList<>();
        plc.dashboardProgram().inputs().forEach(binding -> signals.add(binding.name()));
        plc.dashboardProgram().rules().forEach(rule -> signals.add(rule.name()));
        plc.dashboardProgram().timers().forEach(timer -> signals.add(timer.name() + ".DONE"));
        plc.dashboardProgram().counters().forEach(counter -> signals.add(counter.name() + ".DONE"));
        return signals.stream().distinct().limit(12).toList();
    }

    private void renderCompactStatus(GuiGraphics graphics, int left, int top, int right) {
        ProgrammableLogicControllerBlockEntity plc = currentPlc();
        if (plc == null) return;
        String state = !plc.compileError().isEmpty() ? "FAULT" : plc.isRunning() ? "RUNNING" : "STOPPED";
        String status = "STATE " + state + "   SCAN " + plc.dashboardScanCount()
                + "   ALARM " + (plc.alarmLatched() ? "LATCHED" : "CLEAR");
        graphics.drawString(font, fitToWidth(status, right - left), left, top, 0xFF9BC4D5, false);
    }

    private void renderWatch(GuiGraphics graphics, int left, int top, int right, int bottom) {
        ProgrammableLogicControllerBlockEntity plc = currentPlc();
        if (plc == null) return;
        String state = !plc.compileError().isEmpty() ? "FAULT" : plc.isRunning() ? "RUNNING" : "STOPPED";
        graphics.drawString(font, "LIVE CONTROLLER", left, top, 0xFF8BD8F2, false);
        drawValue(graphics, "STATE", state, left, top + 19, state.equals("RUNNING") ? 0xFF55E684 : 0xFFFFB86C);
        drawValue(graphics, "SCAN", Long.toString(plc.dashboardScanCount()), left, top + 38, 0xFFC8D7DF);
        drawValue(graphics, "INTERVAL", plc.dashboardProgram().scanIntervalTicks() + " ticks", left, top + 57, 0xFFC8D7DF);
        drawValue(graphics, "ALARM", plc.alarmLatched() ? "LATCHED" : "CLEAR", left, top + 76,
                plc.alarmLatched() ? 0xFFFF5555 : 0xFF55E684);
        int row = top + 108;
        graphics.drawString(font, "I/O WATCH", left, row, 0xFFF1FA8C, false);
        row += 16;
        for (PlcProgram.Binding input : plc.dashboardProgram().inputs()) {
            if (row > bottom - 12) break;
            boolean value = plc.dashboardSignals().getOrDefault(input.name(), false);
            graphics.drawString(font, (plc.forcedInputs().containsKey(input.name()) ? "F " : "  ")
                    + input.name(), left, row, 0xFFC8D7DF, false);
            graphics.drawString(font, value ? "ON" : "OFF", right - 32, row,
                    value ? 0xFF55E684 : 0xFF718A99, false);
            row += 12;
        }
        for (PlcProgram.Binding output : plc.dashboardProgram().outputs()) {
            if (row > bottom - 12) break;
            boolean value = plc.dashboardSignals().getOrDefault(output.name(), false);
            graphics.drawString(font, (plc.forcedOutputs().containsKey(output.name()) ? "F " : "  ")
                    + output.name(), left, row, 0xFFC8D7DF, false);
            graphics.drawString(font, value ? "ON" : "OFF", right - 32, row,
                    value ? 0xFF55E684 : 0xFF718A99, false);
            row += 12;
        }
        if (!plc.compileError().isEmpty()) graphics.drawString(font, "Error: " + fitToWidth(plc.compileError(), right - left),
                left, bottom - 26, 0xFFFF6666, false);
        else if (!plc.controllerFault().isEmpty()) graphics.drawString(font, "Fault: " + fitToWidth(plc.controllerFault(), right - left),
                left, bottom - 26, 0xFFFF6666, false);
    }

    private void drawValue(GuiGraphics graphics, String label, String value, int x, int y, int color) {
        graphics.drawString(font, label, x, y, 0xFF718A99, false);
        graphics.drawString(font, value, x + 64, y, color, false);
    }

    private ProgrammableLogicControllerBlockEntity currentPlc() {
        if (minecraft == null || minecraft.level == null) return null;
        return minecraft.level.getBlockEntity(menu.targetPosition())
                instanceof ProgrammableLogicControllerBlockEntity plc ? plc : null;
    }

    private String currentSource() {
        ProgrammableLogicControllerBlockEntity plc = currentPlc();
        return plc == null ? "" : plc.programSource();
    }

    private void compile() {
        if (editor == null || requestPending) return;
        requestPending = true;
        ModNetwork.sendPlcCompile(menu.containerId, ladderMode && ladder != null ? ladder.toSource() : editor.text());
        notice = "Compiling…";
        noticeTicks = 80;
    }

    private void toggleLadderMode() {
        if (editor == null) return;
        if (!ladderMode) {
            ladder = PlcLadderModel.fromSource(editor.text());
            ladderMode = true;
            notice = "Ladder mode: drag contacts from the palette onto a rung";
        } else {
            String source = ladder == null ? editor.text() : ladder.toSource();
            boolean changed = !source.equals(editor.text());
            editor.setInitialText(source);
            if (changed) editor.markDirty();
            ladderMode = false;
            notice = "Source mode: generated rung lines are ready to compile";
        }
        noticeTicks = 100;
    }

    private void action(ModNetwork.PlcAction action) {
        if (requestPending) return;
        requestPending = true;
        ModNetwork.sendPlcAction(menu.containerId, action);
        notice = "Sending " + action.name().toLowerCase() + "…";
        noticeTicks = 80;
    }

    private void saveSlot(int slot) {
        if (editor == null || requestPending) return;
        requestPending = true;
        ModNetwork.sendPlcSlot(menu.containerId, ModNetwork.PlcAction.SAVE_SLOT, slot, editor.text());
        notice = "Saving program slot " + slot + "…";
        noticeTicks = 80;
    }

    private void loadSlot(int slot) {
        if (requestPending) return;
        requestPending = true;
        ModNetwork.sendPlcSlot(menu.containerId, ModNetwork.PlcAction.LOAD_SLOT, slot, "");
        notice = "Loading program slot " + slot + "…";
        noticeTicks = 80;
    }

    public static void applyResult(int containerId, boolean success, String message) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof PlcProgrammingScreen screen
                && screen.menu.containerId == containerId) {
            screen.requestPending = false;
            screen.notice = (success ? "OK: " : "Error: ") + (message == null ? "" : message);
            screen.noticeTicks = 120;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (editor == null) return true;
        if (keyCode == GLFW.GLFW_KEY_F7) { toggleLadderMode(); return true; }
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (control) {
            if (keyCode == GLFW.GLFW_KEY_S) { compile(); return true; }
            if (keyCode == GLFW.GLFW_KEY_A) { editor.selectAll(); return true; }
            if (keyCode == GLFW.GLFW_KEY_C) { if (editor.hasSelection()) minecraft.keyboardHandler.setClipboard(editor.selectedText()); return true; }
            if (keyCode == GLFW.GLFW_KEY_X) { if (editor.hasSelection()) { minecraft.keyboardHandler.setClipboard(editor.selectedText()); editor.insert(""); } return true; }
            if (keyCode == GLFW.GLFW_KEY_V) { editor.insert(minecraft.keyboardHandler.getClipboard()); return true; }
            if (keyCode == GLFW.GLFW_KEY_Z) { editor.undo(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Y) { editor.redo(); return true; }
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> editor.insert("\n");
            case GLFW.GLFW_KEY_TAB -> editor.insert("    ");
            case GLFW.GLFW_KEY_BACKSPACE -> editor.backspace();
            case GLFW.GLFW_KEY_DELETE -> editor.delete();
            case GLFW.GLFW_KEY_LEFT -> editor.moveHorizontal(-1, shift, control);
            case GLFW.GLFW_KEY_RIGHT -> editor.moveHorizontal(1, shift, control);
            case GLFW.GLFW_KEY_UP -> editor.moveVertical(-1, shift);
            case GLFW.GLFW_KEY_DOWN -> editor.moveVertical(1, shift);
            case GLFW.GLFW_KEY_HOME -> editor.moveLineBoundary(false, shift, control);
            case GLFW.GLFW_KEY_END -> editor.moveLineBoundary(true, shift, control);
            case GLFW.GLFW_KEY_PAGE_UP -> editor.moveVertical(-15, shift);
            case GLFW.GLFW_KEY_PAGE_DOWN -> editor.moveVertical(15, shift);
            default -> { return true; }
        }
        ensureCursorVisible();
        return true;
    }

    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) { return true; }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editor != null && !Character.isISOControl(codePoint)) editor.insert(String.valueOf(codePoint));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ladderMode && insideLadder(mouseX, mouseY)) {
            int paletteLeft = Math.max(editorLeft() + 220, editorRight() - 154);
            if (mouseX >= paletteLeft) {
                int paletteRow = editorTop() + 25;
                for (String signal : ladderSignals()) {
                    if (mouseY >= paletteRow - 2 && mouseY < paletteRow + 15) {
                        draggedSignal = signal;
                        draggedRung = -1;
                        notice = "Dragging " + signal + " — drop it on a rung";
                        noticeTicks = 80;
                        return true;
                    }
                    paletteRow += 19;
                }
            } else {
                draggedRung = ladderRowAt(mouseY);
                draggedSignal = null;
                return draggedRung >= 0;
            }
        }
        if (button == 0 && editor != null && insideEditor(mouseX, mouseY)) {
            int line = scrollLine + Math.max(0, (int) ((mouseY - editorTop()) / LINE_HEIGHT));
            int column = editorColumnAt(line, (int) mouseX - (editorLeft() + 34));
            editor.setCursor(editor.offsetForLineColumn(line, column), hasShiftDown());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && ladderMode && insideLadder(mouseX, mouseY)) return true;
        if (button == 0 && editor != null && insideEditor(mouseX, mouseY)) {
            int line = scrollLine + Math.max(0, (int) ((mouseY - editorTop()) / LINE_HEIGHT));
            int column = editorColumnAt(line, (int) mouseX - (editorLeft() + 34));
            editor.setCursor(editor.offsetForLineColumn(line, column), true);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && ladderMode) {
            if (draggedSignal != null) {
                int target = ladderRowAt(mouseY);
                if (target >= 0 && ladder != null && ladder.addContact(target, draggedSignal)) {
                    editor.markDirty();
                    notice = "Added contact " + draggedSignal + " to rung " + (target + 1);
                    noticeTicks = 100;
                }
            } else if (draggedRung >= 0) {
                int target = ladderRowAt(mouseY);
                if (target >= 0 && ladder != null && ladder.moveRung(draggedRung, target)) {
                    editor.markDirty();
                    notice = "Moved rung " + (draggedRung + 1) + " to position " + (target + 1);
                    noticeTicks = 100;
                }
            }
            draggedSignal = null;
            draggedRung = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (insideEditor(mouseX, mouseY)) {
            scrollLine = Math.max(0, scrollLine + (delta > 0 ? -3 : 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean insideEditor(double x, double y) {
        return x >= editorLeft() && x < editorRight() && y >= editorTop() - 4 && y < editorBottom();
    }

    private boolean insideLadder(double x, double y) { return ladderMode && insideEditor(x, y); }

    private int ladderRowAt(double y) {
        if (ladder == null) return -1;
        int row = editorTop() + 28;
        int index = (int) ((y - row) / 42);
        return y >= row && index >= 0 && index < ladder.rungs().size() ? index : -1;
    }

    private int editorColumnAt(int lineIndex, int relativeX) {
        if (editor == null || lineIndex < 0 || lineIndex >= editor.lines().size()) return 0;
        String line = editor.lines().get(lineIndex);
        int from = Math.min(scrollColumn, line.length());
        int column = from;
        while (column < line.length()) {
            int previous = font.width(line.substring(from, column));
            int next = font.width(line.substring(from, column + 1));
            if (relativeX < (previous + next) / 2) break;
            column++;
        }
        return column;
    }

    private void ensureCursorVisible() {
        if (editor == null) return;
        TextEditorBuffer.Position pos = editor.position(editor.cursor());
        int visible = Math.max(1, (editorBottom() - editorTop()) / LINE_HEIGHT);
        if (pos.line() < scrollLine) scrollLine = pos.line();
        if (pos.line() >= scrollLine + visible) scrollLine = pos.line() - visible + 1;
        if (pos.column() < scrollColumn) scrollColumn = pos.column();
        int visibleColumns = Math.max(1, (editorRight() - editorLeft() - 42) / Math.max(1, font.width("m")));
        if (pos.column() >= scrollColumn + visibleColumns) scrollColumn = pos.column() - visibleColumns + 1;
    }

    private String fitToWidth(String value, int width) {
        int end = 0;
        while (end < value.length() && font.width(value.substring(0, end + 1)) <= width) end++;
        return value.substring(0, end);
    }

    private boolean compactLayout() { return imageWidth < 560; }
    private int editorLeft() { return leftPos + 8; }
    private int editorRight() { return leftPos + imageWidth - 8; }
    private int editorTop() { return topPos + (narrowLayout() ? 132 : compactLayout() ? 108 : 86); }
    private int editorBottom() { return topPos + imageHeight - 32; }
    private boolean narrowLayout() { return imageWidth < 440; }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public boolean isPauseScreen() { return false; }
}
