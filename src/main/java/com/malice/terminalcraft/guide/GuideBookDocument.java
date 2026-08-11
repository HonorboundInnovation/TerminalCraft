package com.malice.terminalcraft.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, client-independent Markdown reader model for the in-game TerminalCraft Guide.
 *
 * <p>The source documentation remains ordinary Markdown in {@code docs/}. This model deliberately
 * understands only the structures the book needs: chapters, sections, paragraphs, lists, code,
 * quotes, tables, and rules. Keeping parsing independent of Minecraft makes the complete bundled
 * manual testable without starting a client.</p>
 */
public final class GuideBookDocument {
    public static final int MAX_SOURCE_CHARACTERS = 512 * 1024;
    public static final int MAX_SOURCE_LINES = 20_000;
    public static final int MAX_CHAPTERS = 128;
    public static final int MAX_GUIDE_IMAGES = 64;

    private static final Pattern LINK = Pattern.compile("!?\\[([^]]*)]\\([^)]+\\)");
    private static final Pattern IMAGE = Pattern.compile("^!\\[([^]]*)]\\((terminalcraft:textures/gui/guide/[a-z0-9_./-]+\\.png)\\)$");
    private static final Pattern ANGLE_LINK = Pattern.compile("<(https?://[^>]+)>");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+[.)])\\s+(.*)$");

    public enum Kind {
        TITLE, CHAPTER, SECTION, BODY, BULLET, NUMBERED, CODE, QUOTE, TABLE, IMAGE, SPACE, RULE
    }

    public record Line(Kind kind, String text) {
        public Line {
            kind = Objects.requireNonNull(kind, "kind");
            text = text == null ? "" : text;
        }
    }

    public record Chapter(String title, List<Line> lines, String searchText) {
        public Chapter {
            title = title == null || title.isBlank() ? "Guide" : title.trim();
            lines = List.copyOf(lines == null ? List.of() : lines);
            searchText = searchText == null ? "" : searchText;
        }

        public boolean matches(String query) {
            String normalized = normalizeQuery(query);
            return normalized.isEmpty() || searchText.contains(normalized);
        }
    }

    private final List<Chapter> chapters;

    private GuideBookDocument(List<Chapter> chapters) {
        this.chapters = List.copyOf(chapters);
    }

    public List<Chapter> chapters() { return chapters; }

    public List<Chapter> search(String query) {
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return chapters;
        return chapters.stream().filter(chapter -> chapter.matches(normalized)).toList();
    }

    public static GuideBookDocument combine(GuideBookDocument... documents) {
        List<Chapter> combined = new ArrayList<>();
        if (documents != null) {
            for (GuideBookDocument document : documents) {
                if (document == null) continue;
                for (Chapter chapter : document.chapters) {
                    if (combined.size() >= MAX_CHAPTERS) break;
                    combined.add(chapter);
                }
            }
        }
        return new GuideBookDocument(combined.isEmpty()
                ? List.of(chapter("Guide unavailable", List.of(
                new Line(Kind.BODY, "The bundled guide could not be loaded.")))) : combined);
    }

    public static GuideBookDocument parse(String markdown) {
        String source = markdown == null ? "" : markdown;
        if (source.length() > MAX_SOURCE_CHARACTERS) {
            source = source.substring(0, MAX_SOURCE_CHARACTERS);
        }
        String[] rawLines = source.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", MAX_SOURCE_LINES + 1);
        int lineCount = Math.min(rawLines.length, MAX_SOURCE_LINES);
        List<Chapter> chapters = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        String currentTitle = "Welcome";
        boolean code = false;
        int imageCount = 0;

        for (int index = 0; index < lineCount; index++) {
            String raw = rawLines[index];
            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                code = !code;
                continue;
            }
            if (code) {
                current.add(new Line(Kind.CODE, raw.replace("\t", "    ")));
                continue;
            }
            if (trimmed.startsWith("## ")) {
                if (!current.isEmpty() && chapters.size() < MAX_CHAPTERS) {
                    chapters.add(chapter(currentTitle, current));
                }
                currentTitle = inline(trimmed.substring(3));
                current = new ArrayList<>();
                current.add(new Line(Kind.CHAPTER, currentTitle));
                continue;
            }
            if (trimmed.startsWith("#### ")) {
                current.add(new Line(Kind.SECTION, inline(trimmed.substring(5))));
                continue;
            }
            if (trimmed.startsWith("### ")) {
                current.add(new Line(Kind.SECTION, inline(trimmed.substring(4))));
                continue;
            }
            if (trimmed.startsWith("# ")) {
                String title = inline(trimmed.substring(2));
                if (current.isEmpty() && chapters.isEmpty()) currentTitle = title;
                current.add(new Line(Kind.TITLE, title));
                continue;
            }
            if (trimmed.isEmpty()) {
                addSpace(current);
                continue;
            }
            if (trimmed.matches("-{3,}")) {
                current.add(new Line(Kind.RULE, ""));
                continue;
            }
            Matcher image = IMAGE.matcher(trimmed);
            if (image.matches()) {
                String resource = image.group(2);
                if (imageCount < MAX_GUIDE_IMAGES && !resource.contains("..") && !resource.contains("//")) {
                    current.add(new Line(Kind.IMAGE, resource + "\t" + inline(image.group(1))));
                    imageCount++;
                } else {
                    current.add(new Line(Kind.QUOTE, inline(image.group(1))));
                }
                continue;
            }
            if (trimmed.startsWith(">")) {
                current.add(new Line(Kind.QUOTE, inline(trimmed.substring(1).trim())));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                current.add(new Line(Kind.BULLET, inline(trimmed.substring(2))));
                continue;
            }
            Matcher numbered = NUMBERED.matcher(trimmed);
            if (numbered.matches()) {
                current.add(new Line(Kind.NUMBERED,
                        numbered.group(1) + " " + inline(numbered.group(2))));
                continue;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                if (!trimmed.matches("^[|: \\-]+$")) {
                    current.add(new Line(Kind.TABLE, inline(trimmed)));
                }
                continue;
            }

            String text = inline(trimmed);
            if (!raw.isEmpty() && Character.isWhitespace(raw.charAt(0))
                    && appendContinuation(current, text)) {
                continue;
            }
            if (!current.isEmpty() && current.get(current.size() - 1).kind() == Kind.BODY) {
                Line previous = current.remove(current.size() - 1);
                current.add(new Line(Kind.BODY, previous.text() + " " + text));
            } else {
                current.add(new Line(Kind.BODY, text));
            }
        }
        if (!current.isEmpty() && chapters.size() < MAX_CHAPTERS) {
            chapters.add(chapter(currentTitle, current));
        }
        if (chapters.isEmpty()) {
            chapters.add(chapter("Guide unavailable", List.of(
                    new Line(Kind.BODY, "The bundled guide is empty or unavailable."))));
        }
        return new GuideBookDocument(chapters);
    }

    private static boolean appendContinuation(List<Line> lines, String text) {
        if (lines.isEmpty()) return false;
        Line previous = lines.get(lines.size() - 1);
        if (previous.kind() != Kind.BULLET && previous.kind() != Kind.NUMBERED
                && previous.kind() != Kind.QUOTE) return false;
        lines.set(lines.size() - 1, new Line(previous.kind(), previous.text() + " " + text));
        return true;
    }

    private static void addSpace(List<Line> lines) {
        if (!lines.isEmpty() && lines.get(lines.size() - 1).kind() != Kind.SPACE) {
            lines.add(new Line(Kind.SPACE, ""));
        }
    }

    /** Resource identifier stored in one validated guide image line. */
    public static String imageResource(Line line) {
        if (line == null || line.kind() != Kind.IMAGE) return "";
        int separator = line.text().indexOf('\t');
        return separator < 0 ? line.text() : line.text().substring(0, separator);
    }

    /** Searchable, player-facing alternative text stored alongside a guide image. */
    public static String imageAlt(Line line) {
        if (line == null || line.kind() != Kind.IMAGE) return "";
        int separator = line.text().indexOf('\t');
        return separator < 0 ? "" : line.text().substring(separator + 1);
    }

    private static Chapter chapter(String title, List<Line> lines) {
        StringBuilder searchable = new StringBuilder(title == null ? "" : title);
        for (Line line : lines) searchable.append(' ').append(line.text());
        return new Chapter(title, lines,
                searchable.toString().toLowerCase(Locale.ROOT));
    }

    private static String inline(String markdown) {
        String text = markdown == null ? "" : markdown;
        Matcher links = LINK.matcher(text);
        text = links.replaceAll("$1");
        Matcher angleLinks = ANGLE_LINK.matcher(text);
        text = angleLinks.replaceAll("$1");
        return text.replace("**", "").replace("__", "")
                .replace("`", "").replace("\\|", "|").trim();
    }

    private static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }
}
