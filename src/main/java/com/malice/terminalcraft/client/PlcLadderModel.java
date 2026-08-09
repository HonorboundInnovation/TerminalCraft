package com.malice.terminalcraft.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, source-preserving ladder projection used by the PLC programmer screen.
 * Declarations and advanced instructions remain intact while RUNG lines become
 * draggable rails with contacts and a coil.
 */
public final class PlcLadderModel {
    private static final Pattern RUNG = Pattern.compile(
            "^\\s*(?:RUNG|LOGIC)\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*(.*?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final List<String> prefixLines = new ArrayList<>();
    private final List<Rung> rungs = new ArrayList<>();

    private PlcLadderModel() {}

    public static PlcLadderModel fromSource(String source) {
        PlcLadderModel model = new PlcLadderModel();
        String safe = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : safe.split("\n", -1)) {
            Matcher matcher = RUNG.matcher(line);
            if (!matcher.matches()) model.prefixLines.add(line);
            else model.rungs.add(new Rung(matcher.group(1).toUpperCase(Locale.ROOT), matcher.group(2).trim()));
        }
        return model;
    }

    public List<String> prefixLines() { return Collections.unmodifiableList(prefixLines); }
    public List<Rung> rungs() { return Collections.unmodifiableList(rungs); }

    public String toSource() {
        List<String> lines = new ArrayList<>(prefixLines);
        for (Rung rung : rungs) lines.add("RUNG " + rung.output() + " = " + rung.expression());
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        return String.join("\n", lines);
    }

    public boolean addContact(int rungIndex, String signal) {
        if (rungIndex < 0 || rungIndex >= rungs.size() || signal == null) return false;
        String normalized = signal.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z_][A-Z0-9_.]*")) return false;
        Rung rung = rungs.get(rungIndex);
        String expression = rung.expression().isBlank() || "OFF".equals(rung.expression())
                ? normalized : "(" + rung.expression() + ") AND " + normalized;
        rungs.set(rungIndex, new Rung(rung.output(), expression));
        return true;
    }

    public boolean moveRung(int from, int to) {
        if (from < 0 || from >= rungs.size() || to < 0 || to >= rungs.size() || from == to) return false;
        Rung moved = rungs.remove(from);
        rungs.add(to, moved);
        return true;
    }

    public record Rung(String output, String expression) {
        public Rung {
            output = output == null ? "" : output.trim().toUpperCase(Locale.ROOT);
            expression = expression == null ? "" : expression.trim();
        }
    }
}
