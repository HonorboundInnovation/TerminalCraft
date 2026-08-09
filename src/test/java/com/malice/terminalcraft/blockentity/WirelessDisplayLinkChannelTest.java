package com.malice.terminalcraft.blockentity;

import java.util.UUID;

/** Headless tests for display-link channel normalization and deterministic pairing names. */
public final class WirelessDisplayLinkChannelTest {
    private WirelessDisplayLinkChannelTest() {}

    public static void main(String[] args) {
        require("factory-floor:main".equals(WirelessDisplayLinkBlockEntity.normalizeChannel(" Factory-Floor:Main ")),
                "channels are trimmed and case normalized");
        require("bad-channel-name".equals(WirelessDisplayLinkBlockEntity.normalizeChannel("bad/channel name")),
                "unsupported channel characters are bounded to safe separators");
        String generated = WirelessDisplayLinkBlockEntity.generatedChannel(
                UUID.fromString("12345678-1234-5678-1234-567812345678"));
        require("player-123456781234".equals(generated), "generated pairing channel is deterministic");
        require(WirelessDisplayLinkBlockEntity.normalizeChannel("x".repeat(100)).length() == 48,
                "channels enforce the persisted length bound");
        System.out.println("Wireless display-link channel tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
