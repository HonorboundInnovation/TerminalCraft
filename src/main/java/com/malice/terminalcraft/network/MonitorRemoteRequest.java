package com.malice.terminalcraft.network;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/** Bounded, non-executable monitor-control payload used by RedNet monitor services. */
public record MonitorRemoteRequest(Operation operation, int row, String text, int foreground, int background) {
    public static final RednetProtocol PROTOCOL = new RednetProtocol(
            "terminalcraft:monitor-control", 1, "application/x-terminalcraft-monitor");
    public static final int MAX_TEXT_CHARS = 320;

    public enum Operation { CLEAR, WRITE, SET, TITLE, PALETTE }

    public MonitorRemoteRequest {
        if (operation == null) throw new IllegalArgumentException("operation is required");
        text = text == null ? "" : text;
        if (text.length() > MAX_TEXT_CHARS || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("monitor text is invalid or too long");
        }
        if (operation == Operation.SET && row < 0) throw new IllegalArgumentException("row must be non-negative");
        if ((foreground & ~0xFFFFFF) != 0 || (background & ~0xFFFFFF) != 0) {
            throw new IllegalArgumentException("color is outside #000000..#FFFFFF");
        }
    }

    public static MonitorRemoteRequest clear() { return new MonitorRemoteRequest(Operation.CLEAR, 0, "", 0, 0); }
    public static MonitorRemoteRequest write(String text) { return new MonitorRemoteRequest(Operation.WRITE, 0, text, 0, 0); }
    public static MonitorRemoteRequest set(int row, String text) { return new MonitorRemoteRequest(Operation.SET, row, text, 0, 0); }
    public static MonitorRemoteRequest title(String text) { return new MonitorRemoteRequest(Operation.TITLE, 0, text, 0, 0); }
    public static MonitorRemoteRequest palette(int foreground, int background) {
        return new MonitorRemoteRequest(Operation.PALETTE, 0, "", foreground, background);
    }

    public String encode() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return "1|" + operation.name().toLowerCase(Locale.ROOT) + "|" + row + "|"
                + foreground + "|" + background + "|" + encoded;
    }

    public static Optional<MonitorRemoteRequest> decode(String payload) {
        if (payload == null || payload.length() > NetworkEnvelope.MAX_PAYLOAD_LENGTH) return Optional.empty();
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6 || !"1".equals(fields[0])) return Optional.empty();
        try {
            Operation operation = Operation.valueOf(fields[1].toUpperCase(Locale.ROOT));
            int row = Integer.parseInt(fields[2]);
            int foreground = Integer.parseInt(fields[3]);
            int background = Integer.parseInt(fields[4]);
            String text = new String(Base64.getUrlDecoder().decode(fields[5]), StandardCharsets.UTF_8);
            return Optional.of(new MonitorRemoteRequest(operation, row, text, foreground, background));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
