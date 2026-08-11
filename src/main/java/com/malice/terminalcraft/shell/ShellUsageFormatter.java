package com.malice.terminalcraft.shell;

import java.util.ArrayList;
import java.util.List;

/** Formats long shell usage diagnostics into readable, line-oriented help. */
final class ShellUsageFormatter {
    private static final int FORMAT_THRESHOLD = 96;
    private static final int DISPLAY_WIDTH = 84;

    private ShellUsageFormatter() {}

    static List<String> format(String line) {
        if (line == null) return List.of("");
        int marker = line.indexOf("usage:");
        if (marker < 0) return List.of(line);

        String header = line.substring(0, marker + "usage:".length()).stripTrailing();
        String body = line.substring(marker + "usage:".length()).trim();
        if (body.isEmpty()) return List.of(line);

        List<String> alternatives = displayAlternatives(body);
        if (line.length() <= FORMAT_THRESHOLD && alternatives.size() == 1) return List.of(line);
        String command = commandName(header);
        List<String> formatted = new ArrayList<>();
        formatted.add(header);
        if (alternatives.size() > 1) {
            for (String alternative : alternatives) {
                String syntax = alternative.trim();
                if (!syntax.startsWith(command + " ") && !syntax.equals(command)) {
                    syntax = command + " " + syntax;
                }
                addWrapped(formatted, "  " + syntax);
            }
        } else {
            addWrapped(formatted, "  " + body);
        }
        return List.copyOf(formatted);
    }

    private static List<String> displayAlternatives(String value) {
        List<String> topLevel = splitAlternatives(value);
        if (topLevel.size() > 1) return topLevel;

        int open = -1;
        int angle = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '<') angle++;
            else if (current == '>') angle = Math.max(0, angle - 1);
            else if (current == '[' && angle == 0) {
                open = index;
                break;
            }
        }
        if (open < 0) return topLevel;
        int close = value.indexOf(']', open + 1);
        if (close < 0) return topLevel;
        List<String> nested = splitAlternatives(value.substring(open + 1, close));
        if (nested.size() <= 1) return topLevel;
        String prefix = value.substring(0, open).trim();
        String suffix = value.substring(close + 1).trim();
        List<String> expanded = new ArrayList<>();
        for (String option : nested) {
            String result = prefix.isEmpty() ? option : prefix + " " + option;
            if (!suffix.isEmpty()) result += " " + suffix;
            expanded.add(result);
        }
        return expanded;
    }

    private static String commandName(String header) {
        int colon = header.indexOf(':');
        if (colon <= 0) return "";
        return header.substring(0, colon).trim();
    }

    private static void addWrapped(List<String> output, String value) {
        String remaining = value.stripTrailing();
        while (remaining.length() > DISPLAY_WIDTH) {
            int split = remaining.lastIndexOf(' ', DISPLAY_WIDTH);
            if (split <= 0) split = DISPLAY_WIDTH;
            output.add(remaining.substring(0, split).stripTrailing());
            remaining = "    " + remaining.substring(split).trim();
        }
        output.add(remaining.stripTrailing());
    }

    /** Splits only top-level alternatives; pipes inside <...>, [...], (...), or quotes stay intact. */
    private static List<String> splitAlternatives(String value) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int angle = 0, square = 0, round = 0, curly = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (current == quote) quote = 0;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            switch (current) {
                case '<' -> angle++;
                case '>' -> angle = Math.max(0, angle - 1);
                case '[' -> square++;
                case ']' -> square = Math.max(0, square - 1);
                case '(' -> round++;
                case ')' -> round = Math.max(0, round - 1);
                case '{' -> curly++;
                case '}' -> curly = Math.max(0, curly - 1);
                case '|' -> {
                    if (angle == 0 && square == 0 && round == 0 && curly == 0) {
                        parts.add(value.substring(start, index).trim());
                        start = index + 1;
                    }
                }
                default -> { }
            }
        }
        parts.add(value.substring(start).trim());
        return parts.stream().filter(part -> !part.isEmpty()).toList();
    }
}
