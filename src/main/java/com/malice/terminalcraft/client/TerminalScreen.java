package com.malice.terminalcraft.client;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.device.TerminalBuffer;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.network.ModNetwork;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.ControlCenterProgram;
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
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 270;
    private static final int HEADER_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 26;
    private static final int SIDE_RAIL_WIDTH = 106;
    private static final int LINE_HEIGHT = 11;
    private static final int MAX_VISIBLE_LINES = 18;

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
    private boolean surfaceMode;
    private List<VisualLine> renderedOutputLines = List.of();
    private int renderedOutputStart;
    private OutputPosition outputSelectionAnchor;
    private OutputPosition outputSelectionCursor;
    private boolean outputSelecting;
    private CompletionCycle completionCycle;
    private boolean controlInputWasActive;
    private boolean controlWasActive;
    private boolean surfaceModeBeforeControl;
    private int hmiRefreshTicker;

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

        // Prompt ">" is drawn in the output column; keep the input away from the status rail.
        int promptX = outputLeft() + 2;
        int inputX = promptX + this.font.width(">") + 4;
        int inputRight = outputRight() - 6;
        int inputWidth = Math.max(20, inputRight - inputX);
        input = new EditBox(this.font, inputX, footerTop() + 5, inputWidth, 12,
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
        if (menu.getShell().isAdvancedHmiActive() && !menu.getShell().isControlCenterInputActive()) {
            if (++hmiRefreshTicker >= 20) {
                hmiRefreshTicker = 0;
                ModNetwork.sendControlCenterAction(menu.containerId, ControlCenterProgram.Action.POLL);
            }
        } else {
            hmiRefreshTicker = 0;
        }
        boolean controlCanType = !menu.getShell().isControlCenterActive()
                || menu.getShell().isControlCenterInputActive();
        if (editor == null && controlCanType && input != null && !input.isFocused()) {
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
        updateControlSurfaceMode();
        if (menu.getShell().isEditorActive()) {
            renderedOutputLines = List.of();
            renderEditor(graphics);
            return;
        }
        if (surfaceMode) {
            renderedOutputLines = List.of();
            renderSurface(graphics);
            return;
        }
        int textColor = Config.crtTextColor & 0xFFFFFF;
        BashShell shellForTitle = menu.getShell();
        String title = menu.isPocket() ? "POCKET TERMINAL" : "TERMINAL NODE";
        renderFrame(graphics, title, shellForTitle.isEditorActive() ? "EDITOR" : "BASH");

        BashShell shell = shellForTitle;
        int outputWidth = outputRight() - outputLeft() - 4;
        List<VisualLine> lines = wrapOutputLines(shell.getOutputLines(), outputWidth);
        int total = lines.size();
        int maxScroll = Math.max(0, total - MAX_VISIBLE_LINES);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        int start = Math.max(0, total - MAX_VISIBLE_LINES - scrollOffset);
        int end = Math.min(total, start + MAX_VISIBLE_LINES);
        renderedOutputLines = lines;
        renderedOutputStart = start;

        int textY = contentTop() + 4;
        for (int i = start; i < end; i++) {
            VisualLine line = lines.get(i);
            drawOutputLine(graphics, line, i, textY, outputColor(line.source(), textColor));
            textY += LINE_HEIGHT;
        }

        // Subtle CRT scanlines
        if (Config.crtScanlines) {
            for (int sy = contentTop(); sy < footerTop(); sy += 2) {
                graphics.fill(outputLeft(), sy, outputRight(), sy + 1, 0x14000000);
            }
        }

        String promptGlyph = shell.isEditorActive() ? ":" : ">";
        int promptColor = shell.isEditorActive()
                ? 0x88CCFF
                : ((textColor & 0xFEFEFE) >> 1 | 0x002200);
        graphics.drawString(font, promptGlyph, outputLeft() + 2, footerTop() + 8, promptColor, false);
        renderStatusRail(graphics, shell, false);
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
        if (menu.getShell().isControlCenterActive()) {
            return controlCenterKeyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F6) {
            surfaceMode = !surfaceMode;
            scrollOffset = 0;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_R && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            reverseSearchHistory();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_L && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            input.setValue("");
            historyIndex = -1;
            historyBuffer = "";
            clearCompletion();
            return true;
        }

        // Ctrl+C copies selected scrollback. With no scrollback selection, the EditBox keeps
        // its normal clipboard behavior for selected command-line text.
        if (keyCode == GLFW.GLFW_KEY_C && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && hasOutputSelection()) {
            copyOutputSelection();
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
        // Tab completes commands and VFS paths instead of cycling focus away from the terminal.
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            tabComplete();
            return true;
        }

        clearCompletion();

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
        if (menu.getShell().isControlCenterActive()) {
            if (menu.getShell().isControlCenterInputActive()) {
                ensureInputFocused();
                if (input != null) input.charTyped(codePoint, modifiers);
            }
            return true;
        }
        ensureInputFocused();
        clearCompletion();
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
        if (editor == null && menu.getShell().isControlCenterActive()) {
            if (button == 0) {
                int[] cell = surfaceCellAt(mouseX, mouseY);
                if (cell != null) {
                    ModNetwork.sendControlCenterAction(menu.containerId,
                            ControlCenterProgram.Action.CLICK, cell[1], cell[0], "");
                    return true;
                }
                if (menu.getShell().isControlCenterInputActive()) {
                    boolean result = super.mouseClicked(mouseX, mouseY, button);
                    ensureInputFocused();
                    return result;
                }
            }
            return true;
        }
        if (editor == null && !surfaceMode && button == 0 && insideOutput(mouseX, mouseY)) {
            OutputPosition position = outputPositionAt(mouseX, mouseY);
            if (position != null) {
                outputSelectionAnchor = position;
                outputSelectionCursor = position;
                outputSelecting = true;
                return true;
            }
        }
        if (button == 0 && !insideOutput(mouseX, mouseY)) {
            clearOutputSelection();
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
        if (!surfaceMode && button == 0 && outputSelecting) {
            OutputPosition position = outputPositionAt(mouseX, mouseY);
            if (position != null) {
                outputSelectionCursor = position;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && outputSelecting) {
            outputSelecting = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (editor != null) {
            editorScrollLine = Math.max(0, editorScrollLine + (delta > 0 ? -3 : 3));
            return true;
        }
        if (menu.getShell().isControlCenterActive()) {
            if (delta != 0) ModNetwork.sendControlCenterAction(menu.containerId,
                    delta > 0 ? ControlCenterProgram.Action.UP : ControlCenterProgram.Action.DOWN);
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
            String safeSource = source == null ? "" : source.replace('\n', ' ');
            if (safeSource.isEmpty()) {
                wrapped.add(new VisualLine(safeSource, "", Component.empty().getVisualOrderText()));
                continue;
            }
            String remaining = safeSource;
            while (!remaining.isEmpty()) {
                String part = font.plainSubstrByWidth(remaining, safeWidth);
                if (part.isEmpty()) {
                    part = remaining.substring(0, 1);
                }
                wrapped.add(new VisualLine(safeSource, part, Component.literal(part).getVisualOrderText()));
                remaining = remaining.substring(part.length());
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

    private void drawOutputLine(GuiGraphics graphics, VisualLine line, int row, int y, int color) {
        int left = outputLeft() + 2;
        int selectionFrom = selectedColumnStart(row, line.plain().length());
        int selectionTo = selectedColumnEnd(row, line.plain().length());
        if (selectionFrom < selectionTo) {
            int startX = left + font.width(line.plain().substring(0, selectionFrom));
            int endX = left + font.width(line.plain().substring(0, selectionTo));
            graphics.fill(startX, y - 1, Math.max(startX + 1, endX), y + LINE_HEIGHT - 1, 0xFF315A78);
        }
        graphics.drawString(font, line.text(), left, y, color, false);
    }

    private int selectedColumnStart(int row, int lineLength) {
        if (!hasOutputSelection()) return 0;
        int first = Math.min(outputSelectionAnchor.row(), outputSelectionCursor.row());
        int last = Math.max(outputSelectionAnchor.row(), outputSelectionCursor.row());
        if (row < first || row > last) return 0;
        if (first == last) return Math.min(outputSelectionAnchor.column(), outputSelectionCursor.column());
        if (row == first) {
            return outputSelectionAnchor.row() < outputSelectionCursor.row()
                    ? outputSelectionAnchor.column() : outputSelectionCursor.column();
        }
        return 0;
    }

    private int selectedColumnEnd(int row, int lineLength) {
        if (!hasOutputSelection()) return 0;
        int first = Math.min(outputSelectionAnchor.row(), outputSelectionCursor.row());
        int last = Math.max(outputSelectionAnchor.row(), outputSelectionCursor.row());
        if (row < first || row > last) return 0;
        if (first == last) return Math.min(lineLength,
                Math.max(outputSelectionAnchor.column(), outputSelectionCursor.column()));
        if (row == last) {
            return outputSelectionAnchor.row() > outputSelectionCursor.row()
                    ? Math.min(lineLength, outputSelectionAnchor.column())
                    : Math.min(lineLength, outputSelectionCursor.column());
        }
        return lineLength;
    }

    private record VisualLine(String source, String plain, FormattedCharSequence text) {}

    private record OutputPosition(int row, int column) {}

    private static final class CompletionCycle {
        private final int tokenStart;
        private final int tokenEnd;
        private final List<String> candidates;
        private int index;
        private String lastLine;
        private int lastCursor;

        private CompletionCycle(int tokenStart, int tokenEnd, List<String> candidates) {
            this.tokenStart = tokenStart;
            this.tokenEnd = tokenEnd;
            this.candidates = List.copyOf(candidates);
            this.index = -1;
        }

        private boolean continues(String line, int cursor) {
            return line.equals(lastLine) && cursor == lastCursor;
        }

        private String apply(String line, String replacement) {
            String next = line.substring(0, tokenStart) + replacement + line.substring(tokenEnd);
            lastLine = next;
            lastCursor = tokenStart + replacement.length();
            return next;
        }

        private String nextCandidate() {
            index = (index + 1) % candidates.size();
            return candidates.get(index);
        }
    }

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
        clearCompletion();
        clearOutputSelection();
        scrollOffset = 0;
        ensureInputFocused();
    }

    /** Submit a synthetic command without touching the visible input box history. */
    private void sendRaw(String command) {
        if (Config.terminalSounds && minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 1.9f);
        }
        ModNetwork.sendCommand(menu.containerId, command == null ? "" : command);
        clearCompletion();
        scrollOffset = 0;
        ensureInputFocused();
    }

    private void tabComplete() {
        if (input == null) return;
        String line = input.getValue();
        int cursor = input.getCursorPosition();
        if (completionCycle != null && completionCycle.continues(line, cursor)) {
            String next = completionCycle.apply(line, completionCycle.nextCandidate());
            setInputValueAndCursor(next, completionCycle.lastCursor);
            return;
        }
        BashShell.CompletionResult result = menu.getShell().complete(line, cursor);
        if (!result.hasMatches()) {
            clearCompletion();
            return;
        }

        completionCycle = new CompletionCycle(result.tokenStart(), result.tokenEnd(), result.candidates());
        String common = result.commonPrefix();
        String typed = line.substring(result.tokenStart(), result.tokenEnd());
        if (common.length() > typed.length()) {
            String next = completionCycle.apply(line, common);
            setInputValueAndCursor(next, completionCycle.lastCursor);
            return;
        }

        String next = completionCycle.apply(line, completionCycle.nextCandidate());
        setInputValueAndCursor(next, completionCycle.lastCursor);
    }

    private void setInputValueAndCursor(String value, int cursor) {
        input.setValue(value);
        int bounded = Math.max(0, Math.min(value.length(), cursor));
        input.setCursorPosition(bounded);
        input.setHighlightPos(bounded);
        ensureInputFocused();
    }

    private void clearCompletion() {
        completionCycle = null;
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

    /** Bash-style Ctrl+R search over the local shell history without sending a command. */
    private void reverseSearchHistory() {
        if (localHistory.isEmpty()) localHistory.addAll(menu.getShell().getCommandHistory());
        String query = input == null ? "" : input.getValue();
        for (int index = localHistory.size() - 1; index >= 0; index--) {
            String candidate = localHistory.get(index);
            if (query.isBlank() || candidate.contains(query)) {
                input.setValue(candidate);
                input.moveCursorToEnd();
                historyIndex = index;
                return;
            }
        }
    }

    private boolean insideOutput(double mouseX, double mouseY) {
        return !renderedOutputLines.isEmpty()
                && mouseX >= outputLeft() + 1 && mouseX < outputRight() - 1
                && mouseY >= contentTop() + 1 && mouseY < footerTop() - 1;
    }

    private OutputPosition outputPositionAt(double mouseX, double mouseY) {
        if (renderedOutputLines.isEmpty()) return null;
        int visualRow = renderedOutputStart + Math.max(0,
                Math.min(MAX_VISIBLE_LINES - 1, (int) ((mouseY - (contentTop() + 4)) / LINE_HEIGHT)));
        visualRow = Math.max(0, Math.min(renderedOutputLines.size() - 1, visualRow));
        VisualLine line = renderedOutputLines.get(visualRow);
        int relativeX = Math.max(0, (int) mouseX - (outputLeft() + 2));
        int column = 0;
        while (column < line.plain().length()) {
            int previous = font.width(line.plain().substring(0, column));
            int next = font.width(line.plain().substring(0, column + 1));
            if (relativeX < (previous + next) / 2) break;
            column++;
        }
        return new OutputPosition(visualRow, column);
    }

    private boolean hasOutputSelection() {
        return outputSelectionAnchor != null && outputSelectionCursor != null
                && (outputSelectionAnchor.row() != outputSelectionCursor.row()
                || outputSelectionAnchor.column() != outputSelectionCursor.column());
    }

    private void clearOutputSelection() {
        outputSelectionAnchor = null;
        outputSelectionCursor = null;
        outputSelecting = false;
    }

    private void copyOutputSelection() {
        if (!hasOutputSelection() || minecraft == null) return;
        int first = Math.min(outputSelectionAnchor.row(), outputSelectionCursor.row());
        int last = Math.max(outputSelectionAnchor.row(), outputSelectionCursor.row());
        StringBuilder copied = new StringBuilder();
        for (int row = first; row <= last && row < renderedOutputLines.size(); row++) {
            VisualLine line = renderedOutputLines.get(row);
            int from = selectedColumnStart(row, line.plain().length());
            int to = selectedColumnEnd(row, line.plain().length());
            if (row > first) copied.append('\n');
            if (from < to) copied.append(line.plain(), from, to);
        }
        minecraft.keyboardHandler.setClipboard(copied.toString());
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
        boolean controlActive = menu.getShell().isControlCenterActive();
        boolean controlInput = controlActive && menu.getShell().isControlCenterInputActive();
        updateControlSurfaceMode();
        if (input != null) {
            input.visible = !active && (!controlActive || controlInput);
            if (controlInput && !controlInputWasActive) {
                input.setValue("");
                setFocused(input);
                input.setFocused(true);
            } else if (!controlInput && controlInputWasActive) {
                input.setValue("");
            }
        }
        controlInputWasActive = controlInput;
        if (saveButton != null) {
            saveButton.visible = active;
            saveCloseButton.visible = active;
            discardButton.visible = active;
            saveButton.active = active && !editorRequestPending;
            saveCloseButton.active = active && !editorRequestPending;
            discardButton.active = active && !editorRequestPending;
        }
    }

    /** Gives a full-screen program temporary surface ownership, then restores the prior view. */
    private void updateControlSurfaceMode() {
        boolean active = menu.getShell().isControlCenterActive();
        if (active && !controlWasActive) {
            surfaceModeBeforeControl = surfaceMode;
            surfaceMode = true;
        } else if (!active && controlWasActive) {
            surfaceMode = surfaceModeBeforeControl;
        }
        controlWasActive = active;
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

    /** Passive rendering of the server-synchronized character-cell surface. */
    private void renderSurface(GuiGraphics graphics) {
        TerminalBuffer surface = menu.getComputer().terminalSurface();
        if (surface == null) {
            surfaceMode = false;
            return;
        }
        BashShell shell = menu.getShell();
        boolean controlCenter = shell.isControlCenterActive();
        renderFrame(graphics, menu.isPocket() ? "POCKET TERMINAL" : "TERMINAL NODE",
                controlCenter ? shell.getFullScreenProgramTitle() : "SURFACE // F6 LOG");
        int areaLeft = outputLeft() + 4;
        int areaTop = contentTop() + 4;
        int areaRight = outputRight() - 4;
        int areaBottom = footerTop() - 4;
        int cellWidth = Math.max(1, (areaRight - areaLeft) / surface.width());
        int cellHeight = Math.max(1, (areaBottom - areaTop) / surface.height());
        int renderedWidth = cellWidth * surface.width();
        int renderedHeight = cellHeight * surface.height();
        graphics.enableScissor(areaLeft, areaTop, areaLeft + renderedWidth, areaTop + renderedHeight);
        for (int row = 0; row < surface.height(); row++) {
            for (int column = 0; column < surface.width(); column++) {
                int cellLeft = areaLeft + column * cellWidth;
                int cellTop = areaTop + row * cellHeight;
                int background = 0xFF000000 | surface.paletteColor(surface.backgroundAt(column, row));
                graphics.fill(cellLeft, cellTop, cellLeft + cellWidth, cellTop + cellHeight, background);
                char character = surface.characterAt(column, row);
                if (character != ' ') {
                    int foreground = 0xFF000000 | surface.paletteColor(surface.foregroundAt(column, row));
                    graphics.drawString(font, String.valueOf(character), cellLeft, cellTop,
                            foreground, false);
                }
            }
        }
        if (surface.cursorBlink() && surface.cursorX() >= 1 && surface.cursorX() <= surface.width()
                && surface.cursorY() >= 1 && surface.cursorY() <= surface.height()
                && (System.currentTimeMillis() / 500L) % 2 == 0) {
            int cursorLeft = areaLeft + (surface.cursorX() - 1) * cellWidth;
            int cursorTop = areaTop + (surface.cursorY() - 1) * cellHeight;
            graphics.fill(cursorLeft, cursorTop, cursorLeft + 1, cursorTop + cellHeight, 0xFFFFFFFF);
        }
        graphics.disableScissor();
        graphics.drawString(font, shell.isControlCenterInputActive() ? ":" : ">",
                outputLeft() + 2, footerTop() + 8, 0x66FF99, false);
        renderStatusRail(graphics, shell, true);
    }

    private void renderFrame(GuiGraphics graphics, String title, String mode) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 4, y - 4, x + imageWidth + 4, y + imageHeight + 4, 0xFF080A09);
        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, 0xFF3A403C);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF0B110E);
        graphics.fill(x, y, x + imageWidth, y + HEADER_HEIGHT, 0xFF17251D);
        graphics.fill(x, y + HEADER_HEIGHT - 2, x + imageWidth, y + HEADER_HEIGHT,
                0xFF3B8A58);
        graphics.fill(outputLeft(), contentTop(), outputRight(), footerTop(), 0xFF050A07);
        graphics.fill(outputLeft(), footerTop(), outputRight(), y + imageHeight - 8, 0xFF0B1A11);
        graphics.fill(railLeft(), contentTop(), x + imageWidth - 10, footerTop(), 0xFF101A16);
        graphics.fill(railLeft(), contentTop(), railLeft() + 1, footerTop(), 0xFF2C6042);
        graphics.drawString(font, title, x + 12, y + 6, 0xFFB8F5C8, false);
        graphics.drawString(font, "// " + mode, x + imageWidth - 92, y + 6, 0xFF6FAD82, false);
        graphics.fill(x + imageWidth - 24, y + 7, x + imageWidth - 18, y + 13, 0xFF55E684);
    }

    private void renderStatusRail(GuiGraphics graphics, BashShell shell, boolean surface) {
        int left = railLeft() + 8;
        int top = contentTop() + 10;
        int text = 0xFFB8D6C0;
        graphics.drawString(font, "STATUS", left, top, 0xFF7AE39A, false);
        graphics.fill(left, top + 15, left + 6, top + 21,
                shell.getLastExitCode() == 0 ? 0xFF55E684 : 0xFFE26666);
        graphics.drawString(font, "ONLINE", left + 10, top + 14, text, false);
        String mode = shell.isAdvancedHmiActive() ? "HMI" : shell.isControlCenterActive() ? "CONTROL" : surface ? "SURFACE"
                : shell.isEditorActive() ? "EDITOR" : "BASH";
        drawStatusRow(graphics, "MODE", mode, top + 34, text);
        drawStatusRow(graphics, "DIR", fitToWidth(shell.getCwd(), 70), top + 54, text);
        drawStatusRow(graphics, "EXIT", Integer.toString(shell.getLastExitCode()), top + 74, text);
        drawStatusRow(graphics, "HISTORY", Integer.toString(shell.getCommandHistory().size()), top + 94, text);
        drawStatusRow(graphics, "VIEW", shell.isAdvancedHmiActive() ? "PLANT UI"
                : shell.isControlCenterActive() ? "DEVICE UI"
                : surface ? "F6 LOG" : "F6 SURFACE", top + 114, 0xFF7FADCE);
        graphics.drawString(font, shell.isAdvancedHmiActive() ? "Tab widgets" : shell.isControlCenterActive() ? "Tab sections" : "↑↓ history",
                left, top + 151, 0xFF718A79, false);
        graphics.drawString(font, shell.isAdvancedHmiActive() ? "F2 design  F5/R" : shell.isControlCenterActive() ? "F2/N DNS  F5/R" : "PgUp/PgDn",
                left, top + 163, 0xFF718A79, false);
        graphics.drawString(font, shell.isControlCenterActive() ? "ESC back" : "ESC close",
                left, top + 175, 0xFF718A79, false);
    }

    private void drawStatusRow(GuiGraphics graphics, String label, String value, int y, int color) {
        graphics.drawString(font, label, railLeft() + 8, y, 0xFF718A79, false);
        graphics.drawString(font, fitToWidth(value, SIDE_RAIL_WIDTH - 20), railLeft() + 8, y + 9, color, false);
    }

    private int outputLeft() { return leftPos + 10; }
    private int outputRight() { return leftPos + imageWidth - SIDE_RAIL_WIDTH; }
    private int railLeft() { return outputRight() + 8; }
    private int contentTop() { return topPos + HEADER_HEIGHT + 5; }
    private int footerTop() { return topPos + imageHeight - FOOTER_HEIGHT; }

    private boolean controlCenterKeyPressed(int keyCode, int scanCode, int modifiers) {
        BashShell shell = menu.getShell();
        if (shell.isControlCenterInputActive()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                input.setValue("");
                ModNetwork.sendControlCenterAction(menu.containerId,
                        ControlCenterProgram.Action.CANCEL_INPUT);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                String value = input == null ? "" : input.getValue();
                ModNetwork.sendControlCenterAction(menu.containerId,
                        ControlCenterProgram.Action.SUBMIT_TEXT, -1, -1, value);
                if (input != null) input.setValue("");
                return true;
            }
            ensureInputFocused();
            if (input != null) input.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean plainKey = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT
                | GLFW.GLFW_MOD_SUPER)) == 0;
        boolean hmi = shell.isAdvancedHmiActive();
        ControlCenterProgram.Action action = switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> ControlCenterProgram.Action.CLOSE;
            case GLFW.GLFW_KEY_UP -> hmi && shift ? ControlCenterProgram.Action.RESIZE_UP : ControlCenterProgram.Action.UP;
            case GLFW.GLFW_KEY_DOWN -> hmi && shift ? ControlCenterProgram.Action.RESIZE_DOWN : ControlCenterProgram.Action.DOWN;
            case GLFW.GLFW_KEY_LEFT -> hmi && shift ? ControlCenterProgram.Action.RESIZE_LEFT : ControlCenterProgram.Action.LEFT;
            case GLFW.GLFW_KEY_RIGHT -> hmi && shift ? ControlCenterProgram.Action.RESIZE_RIGHT : ControlCenterProgram.Action.RIGHT;
            case GLFW.GLFW_KEY_PAGE_UP -> ControlCenterProgram.Action.UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> ControlCenterProgram.Action.DOWN;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> ControlCenterProgram.Action.ACTIVATE;
            case GLFW.GLFW_KEY_TAB -> shift ? ControlCenterProgram.Action.PREVIOUS_TAB
                    : ControlCenterProgram.Action.NEXT_TAB;
            case GLFW.GLFW_KEY_F2 -> ControlCenterProgram.Action.RENAME;
            case GLFW.GLFW_KEY_F5 -> ControlCenterProgram.Action.REFRESH;
            // Letter fallbacks remain available when a modpack binds or intercepts function keys.
            case GLFW.GLFW_KEY_N -> !hmi && plainKey ? ControlCenterProgram.Action.RENAME : null;
            case GLFW.GLFW_KEY_R -> plainKey ? ControlCenterProgram.Action.REFRESH : null;
            case GLFW.GLFW_KEY_A -> hmi && plainKey ? ControlCenterProgram.Action.ADD : null;
            case GLFW.GLFW_KEY_E -> hmi && plainKey ? ControlCenterProgram.Action.EDIT : null;
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> hmi && plainKey
                    ? ControlCenterProgram.Action.DELETE : null;
            default -> null;
        };
        if (action != null) ModNetwork.sendControlCenterAction(menu.containerId, action);
        return true;
    }

    /** Converts a click in the rendered 40x16 surface back to its authoritative cell. */
    private int[] surfaceCellAt(double mouseX, double mouseY) {
        TerminalBuffer surface = menu.getComputer().terminalSurface();
        if (surface == null) return null;
        int areaLeft = outputLeft() + 4;
        int areaTop = contentTop() + 4;
        int areaRight = outputRight() - 4;
        int areaBottom = footerTop() - 4;
        int cellWidth = Math.max(1, (areaRight - areaLeft) / surface.width());
        int cellHeight = Math.max(1, (areaBottom - areaTop) / surface.height());
        int renderedWidth = cellWidth * surface.width();
        int renderedHeight = cellHeight * surface.height();
        if (mouseX < areaLeft || mouseX >= areaLeft + renderedWidth
                || mouseY < areaTop || mouseY >= areaTop + renderedHeight) return null;
        int column = Math.max(0, Math.min(surface.width() - 1,
                (int) ((mouseX - areaLeft) / cellWidth)));
        int row = Math.max(0, Math.min(surface.height() - 1,
                (int) ((mouseY - areaTop) / cellHeight)));
        return new int[]{column, row};
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
