package com.malice.terminalcraft.client;

/** Keeps a local channel edit until it matches the server, independent of focus. */
final class DisplayChannelDraft {
    private String value;
    private String observed;

    DisplayChannelDraft(String channel) { value = observed = channel; }
    String value() { return value; }
    boolean dirty() { return !value.equals(observed); }
    void edit(String channel) { value = channel; }
    void observe(String channel) {
        if (!dirty()) value = channel;
        observed = channel;
    }
}
