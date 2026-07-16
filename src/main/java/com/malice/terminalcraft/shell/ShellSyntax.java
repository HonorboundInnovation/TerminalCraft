package com.malice.terminalcraft.shell;

import java.util.ArrayList;
import java.util.List;

/** Quote-aware lexical helpers extracted from the shell execution runtime. */
final class ShellSyntax {
    private ShellSyntax() {}

    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    /** Splits on one top-level character while preserving quote contents and the || operator. */
    static List<String> splitTopLevel(String line, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == separator) {
                if (separator == '|' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                    current.append(c);
                    continue;
                }
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    static boolean isValidName(String name) {
        if (name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    static String stripComment(String raw) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return raw.substring(0, i).trim();
            }
        }
        return raw.trim();
    }
}
