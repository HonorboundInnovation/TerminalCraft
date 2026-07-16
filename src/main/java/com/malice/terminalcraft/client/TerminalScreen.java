package com.malice.terminalcraft.client;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.network.ModNetwork;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game bash terminal screen.
 * Dark CRT-style panel with scrollback and a command input line.
 * Also hosts the interactive {@code edit}/{@code nano} multi-line editor mode
 * so players can author {@code .sh} scripts and save them to mounted floppies.
 *
 * <p>While open, this screen captures all keyboard input so vanilla and other-mod
 * hotkeys cannot fire while the player is typing in the terminal.
 */
public class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 200;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_VISIBLE_LINES = 16;

    private EditBox input;
    private int scrollOffset = 0;
    private int historyIndex = -1;
    private final List<String> localHistory = new ArrayList<>();
    private String historyBuffer = "";
    private TextEditorBuffer editor;
    private String loadedEditorPath;
    private int editorScrollLine;
    private int editorScrollColumn;
    private Button saveButton;
    private Button saveCloseButton;
    private Button discardButton;
    private String editorNotice = "";
    private int editorNoticeTicks;
    private boolean editorRequestPending;
    private boolean awaitingEditorClose;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;

        // Prompt ">" is drawn at leftPos+6; place the field after the glyph so the
        // blinking caret is not jammed against it when the input is empty.
        int promptX = leftPos + 6;
        int inputX = promptX + this.font.width(">") + 4;
        int inputRight = leftPos + imageWidth - 8;
        int inputWidth = Math.max(20, inputRight - inputX);
        input = new EditBox(this.font, inputX, topPos + imageHeight - 18, inputWidth, 12,
                Component.literal("command"));
        input.setMaxLength(Config.maxCommandLength);
        input.setBordered(false);
        int textColor = Config.crtTextColor & 0xFFFFFF;
        input.setTextColor(textColor);
        input.setTextColorUneditable((textColor & 0xFEFEFE) >> 1);
        input.setValue("");
        input.setResponder(s -> {});
        input.setCanLoseFocus(false);
        addRenderableWidget(input);
        setInitialFocus(input);
        input.setFocused(true);

        int buttonY = editorBottom() - 24;
        saveButton = addRenderableWidget(Button.builder(Component.literal("Save  Ctrl+S"),
                button -> saveEditor(false)).bounds(editorRight() - 292, buttonY, 92, 20).build());
        saveCloseButton = addRenderableWidget(Button.builder(Component.literal("Save & Close"),
                button -> saveEditor(true)).bounds(editorRight() - 194, buttonY, 94, 20).build());
        discardButton = addRenderableWidget(Button.builder(Component.literal("Discard"),
                button -> discardEditor()).bounds(editorRight() - 94, buttonY, 84, 20).build());
        updateEditorMode();
    }

    @Override
    public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        String value = input != null ? input.getValue() : "";
        super.resize(minecraft, width, height);
        if (input != null) {
            input.setValue(value);
            setFocused(input);
            input.setFocused(true);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (input != null) {
            input.tick();
        }
        updateEditorMode();
        if (editorNoticeTicks > 0) editorNoticeTicks--;
        if (editor == null && input != null && !input.isFocused()) {
            setFocused(input);
            input.setFocused(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (menu.getShell().isEditorActive()) {
            renderEditor(graphics);
            return;
        }
        int x = leftPos;
        int y = topPos;
        int textColor = Config.crtTextColor & 0xFFFFFF;

        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, 0xFF1A1A1A);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF0A0F0A);
        graphics.fill(x, y, x + imageWidth, y + 14, 0xFF102010);

        BashShell shellForTitle = menu.getShell();
        String title;
        if (shellForTitle.isEditorActive()) {
            title = "TerminalCraft editor";
        } else if (menu.isPocket()) {
            title = "TerminalCraft pocket";
        } else {
            title = "TerminalCraft bash";
        }
        graphics.drawString(font, title, x + 6, y + 3, 0x66FF99, false);

        BashShell shell = menu.getShell();
        int outputWidth = imageWidth - 12;
        List<VisualLine> lines = wrapOutputLines(shell.getOutputLines(), outputWidth);
        int total = lines.size();
        int maxScroll = Math.max(0, total - MAX_VISIBLE_LINES);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        int start = Math.max(0, total - MAX_VISIBLE_LINES - scrollOffset);
        int end = Math.min(total, start + MAX_VISIBLE_LINES);

        int textY = y + 18;
        for (int i = start; i < end; i++) {
            VisualLine line = lines.get(i);
            graphics.drawString(font, line.text(), x + 6, textY,
                    outputColor(line.source(), textColor), false);
            textY += LINE_HEIGHT;
        }

        // Subtle CRT scanlines
        if (Config.crtScanlines) {
            for (int sy = y + 16; sy < y + imageHeight - 22; sy += 2) {
                graphics.fill(x + 2, sy, x + imageWidth - 2, sy + 1, 0x14000000);
            }
        }

        graphics.fill(x + 4, y + imageHeight - 20, x + imageWidth - 4, y + imageHeight - 4, 0xFF051005);
        String promptGlyph = shell.isEditorActive() ? ":" : ">";
        int promptColor = shell.isEditorActive()
                ? 0x88CCFF
                : ((textColor & 0xFEFEFE) >> 1 | 0x002200);
        graphics.drawString(font, promptGlyph, x + 6, y + imageHeight - 16, promptColor, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Custom title drawn in renderBg.
    }

    /**
     * Capture every key press while the terminal is open.
     * Returning true marks the event handled so vanilla/other-mod hotkeys do not fire.
     * Intentionally does not call {@code super.keyPressed} — AbstractContainerScreen would
     * close the GUI on the inventory key and leave other keys unhandled.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editor != null) {
            return editorKeyPressed(keyCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        ensureInputFocused();

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submitInput();
            return true;
        }

        // Editor shortcuts (server authoritative; we send colon-commands).
        BashShell shellKeys = menu.getShell();
        if (shellKeys.isEditorActive()) {
            // Ctrl+S -> :w
            if (keyCode == GLFW.GLFW_KEY_S && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                sendRaw(":w");
                return true;
            }
            // Ctrl+Q -> :q!
            if (keyCode == GLFW.GLFW_KEY_Q && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                sendRaw(":q!");
                return true;
            }
            // Disable shell history while editing; arrows stay with the EditBox caret.
        } else {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                historyUp();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                historyDown();
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scrollOffset++;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            return true;
        }
        // Do not tab-cycle focus away from the command line.
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            return true;
        }

        // Editing keys (backspace, delete, arrows, ctrl+A/C/V/X, home/end, etc.).
        if (input != null) {
            input.keyPressed(keyCode, scanCode, modifiers);
        }

        // Always consume: letter/number/symbol keyPressed events must not fall through
        // to KeyMapping / other-mod hotkey handlers (characters still arrive via charTyped).
        return true;
    }

    /**
     * Capture every key release so held-key hotkeys cannot arm/fire on release either.
     */
    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (input != null) {
            input.keyReleased(keyCode, scanCode, modifiers);
        }
        return true;
    }

    /**
     * All typed characters go to the command line; always consume the event.
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editor != null) {
            if (!Character.isISOControl(codePoint)) {
                editor.insert(String.valueOf(codePoint));
                ensureEditorCursorVisible();
            }
            return true;
        }
        ensureInputFocused();
        if (input != null) {
            input.charTyped(codePoint, modifiers);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (editor != null && button == 0 && insideTextArea(mouseX, mouseY)) {
            int line = editorScrollLine + Math.max(0, (int) ((mouseY - (editorTop() + 34)) / LINE_HEIGHT));
            int column = editorColumnAt(line, (int) mouseX - (editorLeft() + 48));
            editor.setCursor(editor.offsetForLineColumn(line, column), hasShiftDown());
            return true;
        }
        boolean result = super.mouseClicked(mouseX, mouseY, button);
        ensureInputFocused();
        return result;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (editor != null && button == 0) {
            int line = editorScrollLine + Math.max(0, Math.min(visibleEditorLines() - 1,
                    (int) ((mouseY - (editorTop() + 34)) / LINE_HEIGHT)));
            int column = editorColumnAt(line, (int) mouseX - (editorLeft() + 48));
            editor.setCursor(editor.offsetForLineColumn(line, column), true);
            ensureEditorCursorVisible();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (editor != null) {
            editorScrollLine = Math.max(0, editorScrollLine + (delta > 0 ? -3 : 3));
            return true;
        }
        if (delta > 0) {
            scrollOffset++;
        } else if (delta < 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        }
        return true;
    }

    /**
     * Converts logical shell messages into viewport-width visual rows. Minecraft's font splitter
     * wraps on words where possible and hard-wraps long tokens, so no output is discarded.
     */
    private List<VisualLine> wrapOutputLines(List<String> output, int width) {
        List<VisualLine> wrapped = new ArrayList<>();
        int safeWidth = Math.max(1, width);
        for (String source : output) {
            String safeSource = source == null ? "" : source;
            List<FormattedCharSequence> parts = font.split(Component.literal(safeSource), safeWidth);
            if (parts.isEmpty()) {
                wrapped.add(new VisualLine(safeSource, Component.empty().getVisualOrderText()));
                continue;
            }
            for (FormattedCharSequence part : parts) {
                wrapped.add(new VisualLine(safeSource, part));
            }
        }
        return wrapped;
    }

    private static int outputColor(String line, int defaultColor) {
        if (line.startsWith("bash:") || line.contains("command not found") || line.contains("No such")
                || line.startsWith("edit: no write") || line.startsWith("edit: cannot")) {
            return 0xFF6666;
        }
        if (line.startsWith("TerminalCraft") || line.startsWith("Type 'help'")
                || line.startsWith("mounted ") || line.startsWith("unmounted ")
                || line.startsWith("-- editor:") || line.startsWith("edit: wrote")
                || line.startsWith("edit: closed")) {
            return 0x88FFAA;
        }
        if (looksLikeEditorLine(line)) {
            return 0xAADDFF;
        }
        return defaultColor;
    }

    private record VisualLine(String source, FormattedCharSequence text) {}

    private static boolean looksLikeEditorLine(String line) {
        // Numbered buffer lines look like "  1| code" or editor prompt "[file*]> ".
        if (line.startsWith("[") && line.endsWith("> ")) {
            return true;
        }
        int bar = line.indexOf('|');
        if (bar <= 0) {
            return false;
        }
        String left = line.substring(0, bar).trim();
        if (left.isEmpty()) {
            return false;
        }
        for (int i = 0; i < left.length(); i++) {
            if (!Character.isDigit(left.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void ensureInputFocused() {
        if (input == null) {
            return;
        }
        if (getFocused() != input) {
            setFocused(input);
        }
        if (!input.isFocused()) {
            input.setFocused(true);
        }
    }

    private void submitInput() {
        String value = input.getValue();
        if (value == null) {
            value = "";
        }
        boolean editing = menu.getShell().isEditorActive();
        String trimmed = value.trim();
        // Shell history only for normal mode; editor lines are buffer content, not commands.
        if (!editing && !trimmed.isEmpty()) {
            localHistory.add(trimmed);
        }
        historyIndex = -1;
        historyBuffer = "";
        if (Config.terminalSounds && minecraft != null && minecraft.player != null) {
            float pitch = editing ? 1.85f : 1.6f;
            minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.35f, pitch);
        }
        // Server executes and syncs block-entity shell state back to the client.
        ModNetwork.sendCommand(menu.containerId, value);
        input.setValue("");
        scrollOffset = 0;
        ensureInputFocused();
    }

    /** Submit a synthetic command without touching the visible input box history. */
    private void sendRaw(String command) {
        if (Config.terminalSounds && minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 1.9f);
        }
        ModNetwork.sendCommand(menu.containerId, command == null ? "" : command);
        scrollOffset = 0;
        ensureInputFocused();
    }

    private void historyUp() {
        if (localHistory.isEmpty()) {
            List<String> shellHist = menu.getShell().getCommandHistory();
            localHistory.clear();
            localHistory.addAll(shellHist);
        }
        if (localHistory.isEmpty()) {
            return;
        }
        if (historyIndex == -1) {
            historyBuffer = input.getValue();
            historyIndex = localHistory.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        }
        input.setValue(localHistory.get(historyIndex));
        input.moveCursorToEnd();
    }

    private void historyDown() {
        if (historyIndex == -1) {
            return;
        }
        if (historyIndex < localHistory.size() - 1) {
            historyIndex++;
            input.setValue(localHistory.get(historyIndex));
        } else {
            historyIndex = -1;
            input.setValue(historyBuffer);
        }
        input.moveCursorToEnd();
    }

    private void updateEditorMode() {
        boolean shellActive = menu.getShell().isEditorActive();
        if (!shellActive) awaitingEditorClose = false;
        boolean active = shellActive && !awaitingEditorClose;
        String path = menu.getShell().getEditorPath();
        if (active && (editor == null || !java.util.Objects.equals(path, loadedEditorPath))) {
            editor = new TextEditorBuffer(menu.getShell().getEditorText());
            loadedEditorPath = path;
            editorScrollLine = 0;
            editorScrollColumn = 0;
            setFocused(null);
        } else if (!active) {
            editor = null;
            loadedEditorPath = null;
        }
        if (input != null) input.visible = !active;
        if (saveButton != null) {
            saveButton.visible = active;
            saveCloseButton.visible = active;
            discardButton.visible = active;
            saveButton.active = active && !editorRequestPending;
            saveCloseButton.active = active && !editorRequestPending;
            discardButton.active = active && !editorRequestPending;
        }
    }

    private int editorWidth() { return Math.min(620, Math.max(360, width - 24)); }
    private int editorHeight() { return Math.min(390, Math.max(240, height - 24)); }
    private int editorLeft() { return (width - editorWidth()) / 2; }
    private int editorTop() { return (height - editorHeight()) / 2; }
    private int editorRight() { return editorLeft() + editorWidth(); }
    private int editorBottom() { return editorTop() + editorHeight(); }
    private int visibleEditorLines() { return Math.max(1, (editorHeight() - 76) / LINE_HEIGHT); }

    private void renderEditor(GuiGraphics graphics) {
        if (editor == null) updateEditorMode();
        if (editor == null) return;
        int left = editorLeft(), top = editorTop(), right = editorRight(), bottom = editorBottom();
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, 0xFF252A30);
        graphics.fill(left, top, right, bottom, 0xFF101418);
        graphics.fill(left, top, right, top + 26, 0xFF193047);
        String path = loadedEditorPath == null ? "untitled" : loadedEditorPath;
        graphics.drawString(font, "Script Editor — " + path + (editor.dirty() ? "  *" : ""), left + 8, top + 9, 0xFFE7F4FF, false);

        int textTop = top + 34;
        int textBottom = bottom - 38;
        graphics.fill(left + 8, textTop - 3, right - 8, textBottom, 0xFF090C10);
        graphics.enableScissor(left + 8, textTop - 3, right - 8, textBottom);
        List<String> lines = editor.lines();
        int selectionStart = editor.selectionStart();
        int selectionEnd = editor.selectionEnd();
        for (int visual = 0; visual < visibleEditorLines(); visual++) {
            int lineIndex = editorScrollLine + visual;
            if (lineIndex >= lines.size()) break;
            int y = textTop + visual * LINE_HEIGHT;
            String number = String.format("%4d", lineIndex + 1);
            graphics.drawString(font, number, left + 12, y, 0xFF607080, false);
            String line = lines.get(lineIndex);
            int lineStart = editor.offsetForLineColumn(lineIndex, 0);
            int from = Math.min(line.length(), editorScrollColumn);
            String visible = line.substring(from);
            visible = fitToWidth(visible, editorWidth() - 68);

            int selectedFrom = Math.max(lineStart + from, selectionStart);
            int selectedTo = Math.min(lineStart + from + visible.length(), selectionEnd);
            if (selectedFrom < selectedTo) {
                int sx = left + 48 + font.width(line.substring(from, selectedFrom - lineStart));
                int ex = left + 48 + font.width(line.substring(from, selectedTo - lineStart));
                graphics.fill(sx, y - 1, ex, y + 9, 0xFF315A78);
            }
            graphics.drawString(font, visible, left + 48, y, 0xFFD8E8F0, false);
        }
        TextEditorBuffer.Position caret = editor.position(editor.cursor());
        int caretVisualLine = caret.line() - editorScrollLine;
        int caretColumn = caret.column() - editorScrollColumn;
        if (caretVisualLine >= 0 && caretVisualLine < visibleEditorLines() && caretColumn >= 0) {
            String caretLine = caret.line() < lines.size() ? lines.get(caret.line()) : "";
            int caretFrom = Math.min(editorScrollColumn, caretLine.length());
            int caretTo = Math.min(caret.line() < lines.size() ? caret.column() : 0, caretLine.length());
            int cx = left + 48 + font.width(caretLine.substring(caretFrom, Math.max(caretFrom, caretTo)));
            int cy = textTop + caretVisualLine * LINE_HEIGHT;
            if ((System.currentTimeMillis() / 500L) % 2 == 0) graphics.fill(cx, cy - 1, cx + 1, cy + 9, 0xFFFFFFFF);
        }
        graphics.disableScissor();

        TextEditorBuffer.Position pos = editor.position(editor.cursor());
        String status = editorNoticeTicks > 0 ? editorNotice
                : "Ln " + (pos.line() + 1) + ", Col " + (pos.column() + 1)
                + "   Ctrl+S Save   Ctrl+Shift+S Save & Close   Ctrl+Z/Y Undo/Redo";
        graphics.drawString(font, status, left + 10, bottom - 31, 0xFF9FB3C2, false);
    }

    private boolean editorKeyPressed(int keyCode, int modifiers) {
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (control) {
            if (keyCode == GLFW.GLFW_KEY_S) { saveEditor(shift); return true; }
            if (keyCode == GLFW.GLFW_KEY_A) { editor.selectAll(); return true; }
            if (keyCode == GLFW.GLFW_KEY_C) { if (editor.hasSelection()) minecraft.keyboardHandler.setClipboard(editor.selectedText()); return true; }
            if (keyCode == GLFW.GLFW_KEY_X) { if (editor.hasSelection()) { minecraft.keyboardHandler.setClipboard(editor.selectedText()); editor.insert(""); } return true; }
            if (keyCode == GLFW.GLFW_KEY_V) { editor.insert(minecraft.keyboardHandler.getClipboard()); ensureEditorCursorVisible(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Z) { editor.undo(); ensureEditorCursorVisible(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Y) { editor.redo(); ensureEditorCursorVisible(); return true; }
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (editor.dirty()) showEditorNotice("Unsaved changes — use Save or Discard");
                else discardEditor();
            }
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
            case GLFW.GLFW_KEY_PAGE_UP -> editor.moveVertical(-visibleEditorLines(), shift);
            case GLFW.GLFW_KEY_PAGE_DOWN -> editor.moveVertical(visibleEditorLines(), shift);
            default -> { return true; }
        }
        ensureEditorCursorVisible();
        return true;
    }

    private void ensureEditorCursorVisible() {
        if (editor == null) return;
        TextEditorBuffer.Position position = editor.position(editor.cursor());
        if (position.line() < editorScrollLine) editorScrollLine = position.line();
        if (position.line() >= editorScrollLine + visibleEditorLines()) editorScrollLine = position.line() - visibleEditorLines() + 1;
        int visibleColumns = Math.max(1, (editorWidth() - 68) / Math.max(1, font.width("m")));
        if (position.column() < editorScrollColumn) editorScrollColumn = position.column();
        if (position.column() >= editorScrollColumn + visibleColumns) editorScrollColumn = position.column() - visibleColumns + 1;
    }

    private int editorColumnAt(int lineIndex, int relativeX) {
        if (editor == null) return 0;
        List<String> lines = editor.lines();
        if (lineIndex < 0 || lineIndex >= lines.size()) return 0;
        String line = lines.get(lineIndex);
        int column = Math.min(editorScrollColumn, line.length());
        int x = Math.max(0, relativeX);
        while (column < line.length()) {
            int width = font.width(line.substring(editorScrollColumn, column + 1));
            int previous = font.width(line.substring(editorScrollColumn, column));
            if (x < (previous + width) / 2) break;
            column++;
        }
        return column;
    }

    private String fitToWidth(String value, int width) {
        int end = 0;
        while (end < value.length() && font.width(value.substring(0, end + 1)) <= width) end++;
        return value.substring(0, end);
    }

    private boolean insideTextArea(double mouseX, double mouseY) {
        return mouseX >= editorLeft() + 8 && mouseX < editorRight() - 8
                && mouseY >= editorTop() + 31 && mouseY < editorBottom() - 38;
    }

    private void saveEditor(boolean close) {
        if (editor == null) return;
        if (editorRequestPending) return;
        editorRequestPending = true;
        ModNetwork.sendEditorSave(menu.containerId, editor.text(), close);
        showEditorNotice(close ? "Saving and closing…" : "Saving…");
    }

    private void discardEditor() {
        if (editorRequestPending) return;
        editorRequestPending = true;
        ModNetwork.sendEditorClose(menu.containerId);
        showEditorNotice("Discarding changes…");
    }

    private void showEditorNotice(String message) {
        editorNotice = message;
        editorNoticeTicks = 80;
    }

    public void applyEditorResult(boolean success, boolean closed, String message) {
        editorRequestPending = false;
        if (success && editor != null) editor.markSaved();
        if (success && closed) {
            awaitingEditorClose = true;
            editor = null;
            loadedEditorPath = null;
        }
        showEditorNotice(message == null || message.isBlank() ? (success ? "Saved" : "Save failed") : message);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Only ESC should close the terminal; inventory key and other close shortcuts are blocked.
     */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
