package com.malice.terminalcraft.network;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side admission policy for interactive client-to-server shell mutations.
 *
 * <p>The caller remains responsible for authenticating the sender and checking the concrete menu
 * type. This class centralizes bounded payload, state, and shared per-player rate checks without a
 * dependency on Minecraft network contexts, so hostile and boundary cases remain headlessly
 * testable.</p>
 */
final class CommandSubmissionGuard {
    static final int TICKS_PER_SECOND = 20;

    enum Decision {
        ACCEPT,
        INVALID_MENU,
        INVALID_STATE,
        INVALID_ACTION,
        PAYLOAD_TOO_LONG,
        RATE_LIMITED
    }

    private final Map<Object, Window> windows = new WeakHashMap<>();

    /** Compatibility entry point for command admission. */
    synchronized Decision evaluate(Object senderIdentity, long gameTick, String command,
                                   boolean menuValid, int maxCommandLength,
                                   int maxCommandsPerSecond) {
        if (senderIdentity == null || !menuValid) {
            return Decision.INVALID_MENU;
        }
        if (command == null || command.length() > Math.max(0, maxCommandLength)) {
            return Decision.PAYLOAD_TOO_LONG;
        }
        return consume(senderIdentity, gameTick, maxCommandsPerSecond);
    }

    /**
     * Admits an editor action against the server-owned editor session. Close carries no content;
     * save actions carry a bounded complete replacement buffer.
     */
    synchronized Decision evaluateEditor(Object senderIdentity, long gameTick, String content,
                                         boolean menuValid, boolean editorActive,
                                         boolean closeAction, int maxContentLength,
                                         int maxActionsPerSecond) {
        if (senderIdentity == null || !menuValid) {
            return Decision.INVALID_MENU;
        }
        if (!editorActive) {
            return Decision.INVALID_STATE;
        }
        if (content == null || content.length() > Math.max(0, maxContentLength)) {
            return Decision.PAYLOAD_TOO_LONG;
        }
        if (closeAction && !content.isEmpty()) {
            return Decision.INVALID_ACTION;
        }
        return consume(senderIdentity, gameTick, maxActionsPerSecond);
    }

    synchronized void forget(Object senderIdentity) {
        windows.remove(senderIdentity);
    }

    private Decision consume(Object senderIdentity, long gameTick, int maximumPerSecond) {
        int limit = Math.max(1, maximumPerSecond);
        Window window = windows.get(senderIdentity);
        if (window == null || gameTick < window.startTick
                || gameTick - window.startTick >= TICKS_PER_SECOND) {
            windows.put(senderIdentity, new Window(gameTick, 1));
            return Decision.ACCEPT;
        }
        if (window.accepted >= limit) {
            return Decision.RATE_LIMITED;
        }
        window.accepted++;
        return Decision.ACCEPT;
    }

    private static final class Window {
        private final long startTick;
        private int accepted;

        private Window(long startTick, int accepted) {
            this.startTick = startTick;
            this.accepted = accepted;
        }
    }
}
