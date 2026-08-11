package com.malice.terminalcraft.client;

import com.malice.terminalcraft.TerminalCraftMod;
import com.malice.terminalcraft.guide.GuideBookDocument;
import com.malice.terminalcraft.guide.GuideBookDocument.Chapter;
import com.malice.terminalcraft.guide.GuideBookDocument.Kind;
import com.malice.terminalcraft.guide.GuideBookDocument.Line;
import com.malice.terminalcraft.guide.GuideBookSources;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Searchable, chapter-based reader for the complete bundled TerminalCraft manual and cookbook. */
public final class GuideBookScreen extends Screen {
    private static final ResourceLocation MANUAL = new ResourceLocation(
            TerminalCraftMod.MODID, "guide/terminalcraft_guide.md");
    private static final ResourceLocation COOKBOOK = new ResourceLocation(
            TerminalCraftMod.MODID, "guide/advanced_script_cookbook.md");
    private static final int HEADER_HEIGHT = 52;
    private static final int FOOTER_HEIGHT = 34;
    private static final int CHAPTER_ROW_HEIGHT = 12;
    private static final int MIN_PANEL_WIDTH = 300;
    private static final int MIN_PANEL_HEIGHT = 210;
    private static final int GUIDE_IMAGE_WIDTH = 768;
    private static final int GUIDE_IMAGE_HEIGHT = 512;

    private GuideBookDocument document = GuideBookDocument.parse("");
    private EditBox search;
    private Button previousButton;
    private Button nextButton;
    private int selectedChapter;
    private int chapterScroll;
    private int contentScroll;
    private int cachedChapter = -1;
    private int cachedWidth = -1;
    private List<VisualRow> visualRows = List.of();
    private int contentHeight;

    public GuideBookScreen() {
        super(Component.translatable("screen.terminalcraft.guide_book"));
    }

    @Override
    protected void init() {
        document = loadDocument();
        selectedChapter = Math.max(0, Math.min(selectedChapter, document.chapters().size() - 1));
        int left = panelLeft();
        int top = panelTop();
        int sidebar = sidebarWidth();
        search = new EditBox(font, left + 10, top + 27, sidebar - 20, 18,
                Component.translatable("screen.terminalcraft.guide_book.search"));
        search.setMaxLength(64);
        search.setSuggestion("Search chapters...");
        search.setResponder(this::searchChanged);
        addRenderableWidget(search);

        int bottom = top + panelHeight();
        previousButton = addRenderableWidget(Button.builder(Component.literal("< Previous"),
                        button -> changeChapter(-1))
                .bounds(left + sidebar + 12, bottom - 27, 80, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal("Next >"),
                        button -> changeChapter(1))
                .bounds(left + panelWidth() - 91, bottom - 27, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + panelWidth() - 57, top + 5, 46, 18).build());
        updateButtons();
    }

    private GuideBookDocument loadDocument() {
        String manual = readResource(MANUAL);
        String cookbook = readResource(COOKBOOK);
        GuideBookDocument main = GuideBookDocument.parse(manual);
        GuideBookDocument examples = GuideBookDocument.parse(GuideBookSources.plcTemplateLibrary());
        if (cookbook.isBlank()) return GuideBookDocument.combine(main, examples);
        return GuideBookDocument.combine(main, GuideBookDocument.parse(cookbook), examples);
    }

    private String readResource(ResourceLocation location) {
        if (minecraft == null) return "";
        return minecraft.getResourceManager().getResource(location).map(resource -> {
            try (InputStream stream = resource.open()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return "";
            }
        }).orElse("");
    }

    private void searchChanged(String query) {
        List<Chapter> visible = visibleChapters();
        if (!visible.isEmpty() && !visible.contains(currentChapter())) {
            selectedChapter = document.chapters().indexOf(visible.get(0));
            contentScroll = 0;
            invalidateRows();
        }
        chapterScroll = 0;
        ensureSelectedChapterVisible();
        updateButtons();
    }

    private List<Chapter> visibleChapters() {
        return document.search(search == null ? "" : search.getValue());
    }

    private Chapter currentChapter() {
        if (document.chapters().isEmpty()) return null;
        selectedChapter = Math.max(0, Math.min(selectedChapter, document.chapters().size() - 1));
        return document.chapters().get(selectedChapter);
    }

    private void selectChapter(Chapter chapter) {
        int index = document.chapters().indexOf(chapter);
        if (index < 0) return;
        selectedChapter = index;
        contentScroll = 0;
        invalidateRows();
        ensureSelectedChapterVisible();
        updateButtons();
    }

    private void changeChapter(int delta) {
        List<Chapter> visible = visibleChapters();
        if (visible.isEmpty()) return;
        int current = visible.indexOf(currentChapter());
        if (current < 0) current = 0;
        int next = Math.max(0, Math.min(visible.size() - 1, current + delta));
        selectChapter(visible.get(next));
    }

    private void updateButtons() {
        if (previousButton == null || nextButton == null) return;
        List<Chapter> visible = visibleChapters();
        int current = visible.indexOf(currentChapter());
        previousButton.active = current > 0;
        nextButton.active = current >= 0 && current + 1 < visible.size();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderPanel(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();
        int sidebar = sidebarWidth();
        graphics.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF050807);
        graphics.fill(left, top, right, bottom, 0xFF0B1110);
        graphics.fill(left, top, right, top + 25, 0xFF173425);
        graphics.fill(left + sidebar, top + 25, left + sidebar + 1, bottom, 0xFF315E43);
        graphics.fill(left + sidebar + 1, top + HEADER_HEIGHT - 1, right, top + HEADER_HEIGHT,
                0xFF315E43);
        graphics.fill(left + sidebar + 1, bottom - FOOTER_HEIGHT, right, bottom - FOOTER_HEIGHT + 1,
                0xFF315E43);
        graphics.drawString(font, "TERMINALCRAFT FIELD MANUAL", left + 10, top + 9,
                0xFF9CF6B6, false);
        Chapter current = currentChapter();
        String position = current == null ? "No chapter selected"
                : (selectedChapter + 1) + "/" + document.chapters().size() + "  " + current.title();
        int positionWidth = Math.max(20, panelWidth() - sidebar - 80);
        graphics.drawString(font, font.plainSubstrByWidth(position, positionWidth),
                left + sidebar + 12, top + 34, 0xFF7FA58B, false);
        renderChapterList(graphics, mouseX, mouseY);
        renderContent(graphics);
        renderFooter(graphics);
    }

    private void renderChapterList(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft();
        int top = panelTop();
        int sidebar = sidebarWidth();
        int listTop = chapterListTop();
        int listBottom = top + panelHeight() - 8;
        List<Chapter> chapters = visibleChapters();
        int capacity = Math.max(1, (listBottom - listTop) / CHAPTER_ROW_HEIGHT);
        chapterScroll = Math.max(0, Math.min(chapterScroll, Math.max(0, chapters.size() - capacity)));
        graphics.drawString(font, "CHAPTERS  " + chapters.size() + "/" + document.chapters().size(),
                left + 10, top + 47, 0xFF6FAA82, false);
        if (chapters.isEmpty()) {
            graphics.drawString(font, "No matching chapters", left + 10, listTop + 4,
                    0xFFB87878, false);
            return;
        }
        for (int visible = 0; visible < capacity; visible++) {
            int index = chapterScroll + visible;
            if (index >= chapters.size()) break;
            Chapter chapter = chapters.get(index);
            int y = listTop + visible * CHAPTER_ROW_HEIGHT;
            boolean selected = chapter.equals(currentChapter());
            boolean hover = mouseX >= left + 4 && mouseX < left + sidebar - 3
                    && mouseY >= y && mouseY < y + CHAPTER_ROW_HEIGHT;
            if (selected) graphics.fill(left + 4, y, left + sidebar - 4, y + 11, 0xFF285E3D);
            else if (hover) graphics.fill(left + 4, y, left + sidebar - 4, y + 11, 0xFF172A21);
            String title = font.plainSubstrByWidth(chapter.title(), sidebar - 36);
            graphics.drawString(font, selected ? "> " + title : "  " + title,
                    left + 7, y + 2, selected ? 0xFFB7FFD0 : 0xFFAAB9AE, false);
        }
    }

    private void renderContent(GuiGraphics graphics) {
        int left = contentLeft();
        int top = panelTop() + HEADER_HEIGHT + 5;
        int right = panelLeft() + panelWidth() - 10;
        int bottom = panelTop() + panelHeight() - FOOTER_HEIGHT - 4;
        rebuildRowsIfNeeded(right - left - 9);
        int viewport = Math.max(1, bottom - top);
        contentScroll = Math.max(0, Math.min(contentScroll, Math.max(0, contentHeight - viewport)));
        graphics.enableScissor(left - 3, top, right, bottom);
        int y = top - contentScroll;
        for (VisualRow row : visualRows) {
            if (y + row.height() >= top && y < bottom) {
                if (row.image() != null) {
                    int imageX = left + Math.max(0, (right - left - 3 - row.imageWidth()) / 2);
                    graphics.fill(imageX - 1, y + 2, imageX + row.imageWidth() + 1,
                            y + 3 + row.imageHeight(), 0xFF315E43);
                    graphics.blit(row.image(), imageX, y + 3, 0, 0,
                            row.imageWidth(), row.imageHeight(), GUIDE_IMAGE_WIDTH, GUIDE_IMAGE_HEIGHT);
                } else if (row.rule()) {
                    graphics.fill(left, y + 3, right - 3, y + 4, 0xFF315E43);
                } else {
                    if (row.kind() == Kind.CODE) {
                        graphics.fill(left, y, right - 3, y + row.height(), 0xFF050A08);
                    } else if (row.kind() == Kind.QUOTE) {
                        graphics.fill(left, y, left + 2, y + row.height(), 0xFF4A9B68);
                    }
                    graphics.drawString(font, row.text(), left + row.indent(),
                            y + Math.max(0, (row.height() - 9) / 2), color(row.kind()), false);
                }
            }
            y += row.height();
        }
        graphics.disableScissor();
        renderContentScrollbar(graphics, right, top, bottom, viewport);
    }

    private void renderContentScrollbar(GuiGraphics graphics, int right, int top, int bottom, int viewport) {
        if (contentHeight <= viewport) return;
        int track = bottom - top;
        int thumb = Math.max(16, track * viewport / contentHeight);
        int maxScroll = contentHeight - viewport;
        int thumbY = top + (track - thumb) * contentScroll / Math.max(1, maxScroll);
        graphics.fill(right - 2, top, right, bottom, 0xFF17221C);
        graphics.fill(right - 2, thumbY, right, thumbY + thumb, 0xFF4A9B68);
    }

    private void renderFooter(GuiGraphics graphics) {
        int left = contentLeft();
        int bottom = panelTop() + panelHeight();
        int hintLeft = left + 87;
        int hintWidth = Math.max(20, panelLeft() + panelWidth() - 102 - hintLeft);
        String hint = hintWidth < 190 ? "Ctrl+F search  PgUp/PgDn scroll"
                : "Ctrl+F search  PgUp/PgDn scroll  Left/Right chapters";
        graphics.drawString(font, font.plainSubstrByWidth(hint, hintWidth),
                hintLeft, bottom - 18, 0xFF65766B, false);
    }

    private void rebuildRowsIfNeeded(int contentWidth) {
        if (cachedChapter == selectedChapter && cachedWidth == contentWidth) return;
        cachedChapter = selectedChapter;
        cachedWidth = contentWidth;
        List<VisualRow> rows = new ArrayList<>();
        Chapter chapter = currentChapter();
        if (chapter != null) {
            for (Line line : chapter.lines()) addWrapped(rows, line, contentWidth);
        }
        visualRows = List.copyOf(rows);
        contentHeight = rows.stream().mapToInt(VisualRow::height).sum();
    }

    private void addWrapped(List<VisualRow> rows, Line line, int width) {
        if (line.kind() == Kind.SPACE) {
            rows.add(VisualRow.text(Component.empty().getVisualOrderText(), line.kind(), 0, 6, false));
            return;
        }
        if (line.kind() == Kind.RULE) {
            rows.add(VisualRow.text(Component.empty().getVisualOrderText(), line.kind(), 0, 8, true));
            return;
        }
        if (line.kind() == Kind.IMAGE) {
            ResourceLocation image = ResourceLocation.tryParse(GuideBookDocument.imageResource(line));
            if (image != null && TerminalCraftMod.MODID.equals(image.getNamespace())
                    && image.getPath().startsWith("textures/gui/guide/")) {
                int imageWidth = Math.max(64, Math.min(width - 4, GUIDE_IMAGE_WIDTH));
                int imageHeight = Math.max(43, imageWidth * GUIDE_IMAGE_HEIGHT / GUIDE_IMAGE_WIDTH);
                rows.add(VisualRow.image(image, imageWidth, imageHeight));
            } else {
                addWrapped(rows, new Line(Kind.QUOTE, GuideBookDocument.imageAlt(line)), width);
            }
            return;
        }
        int indent = switch (line.kind()) {
            case BULLET, NUMBERED -> 8;
            case CODE -> 6;
            case QUOTE -> 7;
            case TABLE -> 2;
            default -> 0;
        };
        String display = line.kind() == Kind.BULLET ? "• " + line.text() : line.text();
        int available = Math.max(20, width - indent);
        List<FormattedCharSequence> split = font.split(Component.literal(display), available);
        if (split.isEmpty()) split = List.of(Component.empty().getVisualOrderText());
        int height = switch (line.kind()) {
            case TITLE -> 17;
            case CHAPTER -> 16;
            case SECTION -> 14;
            case SPACE -> 6;
            default -> 10;
        };
        for (FormattedCharSequence part : split) {
            rows.add(VisualRow.text(part, line.kind(), indent, height, false));
            if (line.kind() == Kind.TITLE || line.kind() == Kind.CHAPTER) height = 11;
        }
    }

    private static int color(Kind kind) {
        return switch (kind) {
            case TITLE -> 0xFF7CFF9E;
            case CHAPTER -> 0xFF9CF6B6;
            case SECTION -> 0xFF78CFA0;
            case CODE -> 0xFFB9E8C6;
            case QUOTE -> 0xFF9EB9A6;
            case BULLET, NUMBERED -> 0xFFD0DBD3;
            case TABLE -> 0xFFC1D4C6;
            default -> 0xFFC7D0CA;
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F && hasControlDown()) {
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (search != null && search.isFocused()) {
                search.setFocused(false);
                setFocused(null);
                return true;
            }
            onClose();
            return true;
        }
        if (search != null && search.isFocused()) {
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                    && !visibleChapters().isEmpty()) {
                selectChapter(visibleChapters().get(0));
                search.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        int page = Math.max(20, contentViewportHeight() - 18);
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> changeChapter(-1);
            case GLFW.GLFW_KEY_RIGHT -> changeChapter(1);
            case GLFW.GLFW_KEY_UP -> scrollContent(-12);
            case GLFW.GLFW_KEY_DOWN -> scrollContent(12);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollContent(-page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollContent(page);
            case GLFW.GLFW_KEY_HOME -> contentScroll = 0;
            case GLFW.GLFW_KEY_END -> contentScroll = Integer.MAX_VALUE;
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int left = panelLeft();
        int top = chapterListTop();
        int sidebar = sidebarWidth();
        if (mouseX < left + 4 || mouseX >= left + sidebar - 3 || mouseY < top) return false;
        int visibleIndex = (int) ((mouseY - top) / CHAPTER_ROW_HEIGHT);
        List<Chapter> chapters = visibleChapters();
        int index = chapterScroll + visibleIndex;
        if (index >= 0 && index < chapters.size()) {
            selectChapter(chapters.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < panelLeft() + sidebarWidth()) {
            List<Chapter> chapters = visibleChapters();
            int capacity = chapterCapacity();
            chapterScroll = Math.max(0, Math.min(Math.max(0, chapters.size() - capacity),
                    chapterScroll + (delta > 0 ? -3 : 3)));
        } else {
            scrollContent(delta > 0 ? -30 : 30);
        }
        return true;
    }

    private void scrollContent(int amount) {
        long next = contentScroll + (long) amount;
        contentScroll = (int) Math.max(0, Math.min(Integer.MAX_VALUE, next));
    }

    private void ensureSelectedChapterVisible() {
        List<Chapter> chapters = visibleChapters();
        int selected = chapters.indexOf(currentChapter());
        int capacity = chapterCapacity();
        if (selected < chapterScroll) chapterScroll = selected;
        if (selected >= chapterScroll + capacity) chapterScroll = selected - capacity + 1;
        chapterScroll = Math.max(0, chapterScroll);
    }

    private void invalidateRows() { cachedChapter = -1; }

    private int panelWidth() { return Math.max(MIN_PANEL_WIDTH, Math.min(820, width - 24)); }
    private int panelHeight() { return Math.max(MIN_PANEL_HEIGHT, Math.min(480, height - 24)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }
    private int sidebarWidth() { return Math.max(118, Math.min(210, panelWidth() / 4)); }
    private int contentLeft() { return panelLeft() + sidebarWidth() + 12; }
    private int contentViewportHeight() { return panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT - 9; }
    private int chapterListTop() { return panelTop() + HEADER_HEIGHT + 9; }
    private int chapterCapacity() {
        return Math.max(1, (panelTop() + panelHeight() - 8 - chapterListTop()) / CHAPTER_ROW_HEIGHT);
    }

    @Override public boolean isPauseScreen() { return false; }

    private record VisualRow(FormattedCharSequence text, Kind kind, int indent, int height,
                             boolean rule, ResourceLocation image, int imageWidth, int imageHeight) {
        private static VisualRow text(FormattedCharSequence text, Kind kind, int indent, int height,
                                      boolean rule) {
            return new VisualRow(text, kind, indent, height, rule, null, 0, 0);
        }

        private static VisualRow image(ResourceLocation image, int width, int height) {
            return new VisualRow(Component.empty().getVisualOrderText(), Kind.IMAGE, 0,
                    height + 7, false, image, width, height);
        }
    }
}
