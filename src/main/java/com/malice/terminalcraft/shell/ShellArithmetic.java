package com.malice.terminalcraft.shell;

import java.util.Map;

/** Bounded signed-integer expression evaluator for shell arithmetic. */
final class ShellArithmetic {
    private static final int MAX_EXPRESSION_CHARS = 256;
    private static final int MAX_PARSE_DEPTH = 16;

    private final String input;
    private final Map<String, String> variables;
    private int cursor;
    private int depth;

    private ShellArithmetic(String input, Map<String, String> variables) {
        this.input = input;
        this.variables = variables;
    }

    static long evaluate(String expression, Map<String, String> variables) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("empty expression");
        if (expression.length() > MAX_EXPRESSION_CHARS) throw new IllegalArgumentException("expression too long");
        ShellArithmetic parser = new ShellArithmetic(expression, variables);
        long value = parser.parseOr();
        parser.skipSpace();
        if (parser.cursor != parser.input.length()) {
            throw new IllegalArgumentException("unexpected token at column " + (parser.cursor + 1));
        }
        return value;
    }

    private long parseOr() {
        long value = parseAnd();
        while (consume("||")) {
            long right = parseAnd();
            value = truth(value) || truth(right) ? 1 : 0;
        }
        return value;
    }

    private long parseAnd() {
        long value = parseEquality();
        while (consume("&&")) {
            long right = parseEquality();
            value = truth(value) && truth(right) ? 1 : 0;
        }
        return value;
    }

    private long parseEquality() {
        long value = parseComparison();
        while (true) {
            if (consume("==")) value = value == parseComparison() ? 1 : 0;
            else if (consume("!=")) value = value != parseComparison() ? 1 : 0;
            else return value;
        }
    }

    private long parseComparison() {
        long value = parseAdditive();
        while (true) {
            if (consume("<=")) value = value <= parseAdditive() ? 1 : 0;
            else if (consume(">=")) value = value >= parseAdditive() ? 1 : 0;
            else if (consume("<")) value = value < parseAdditive() ? 1 : 0;
            else if (consume(">")) value = value > parseAdditive() ? 1 : 0;
            else return value;
        }
    }

    private long parseAdditive() {
        long value = parseMultiplicative();
        while (true) {
            if (consume("+")) value = Math.addExact(value, parseMultiplicative());
            else if (consume("-")) value = Math.subtractExact(value, parseMultiplicative());
            else return value;
        }
    }

    private long parseMultiplicative() {
        long value = parseUnary();
        while (true) {
            if (consume("*")) value = Math.multiplyExact(value, parseUnary());
            else if (consume("/")) {
                long divisor = parseUnary();
                if (divisor == 0) throw new IllegalArgumentException("division by zero");
                if (value == Long.MIN_VALUE && divisor == -1) throw new ArithmeticException("long overflow");
                value /= divisor;
            } else if (consume("%")) {
                long divisor = parseUnary();
                if (divisor == 0) throw new IllegalArgumentException("division by zero");
                value %= divisor;
            } else return value;
        }
    }

    private long parseUnary() {
        if (consume("+")) return parseUnary();
        if (consume("-")) return Math.negateExact(parseUnary());
        if (consume("!")) return truth(parseUnary()) ? 0 : 1;
        return parsePrimary();
    }

    private long parsePrimary() {
        skipSpace();
        if (consume("(")) {
            if (++depth > MAX_PARSE_DEPTH) throw new IllegalArgumentException("expression nesting too deep");
            long value = parseOr();
            depth--;
            if (!consume(")")) throw new IllegalArgumentException("missing ')'");
            return value;
        }
        if (cursor < input.length() && Character.isDigit(input.charAt(cursor))) {
            int start = cursor;
            while (cursor < input.length() && Character.isDigit(input.charAt(cursor))) cursor++;
            try {
                return Long.parseLong(input.substring(start, cursor));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("integer out of range");
            }
        }
        if (cursor < input.length() && (Character.isLetter(input.charAt(cursor)) || input.charAt(cursor) == '_')) {
            int start = cursor++;
            while (cursor < input.length()) {
                char next = input.charAt(cursor);
                if (!Character.isLetterOrDigit(next) && next != '_') break;
                cursor++;
            }
            String raw = variables.getOrDefault(input.substring(start, cursor), "0").trim();
            if (raw.isEmpty()) return 0;
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("variable is not an integer");
            }
        }
        throw new IllegalArgumentException("expected integer at column " + (cursor + 1));
    }

    private boolean consume(String token) {
        skipSpace();
        if (!input.startsWith(token, cursor)) return false;
        cursor += token.length();
        return true;
    }

    private void skipSpace() {
        while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++;
    }

    private static boolean truth(long value) {
        return value != 0;
    }
}
