package com.malice.terminalcraft.network;

import java.util.UUID;

/** Headless coverage for the beginner-facing RedNet defaults. */
public final class RednetAutoConfigurationTest {
    private RednetAutoConfigurationTest() {}

    public static void main(String[] args) {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        check(RednetAutoConfiguration.DEFAULT_CHANNEL == 42, "default data channel must remain discoverable");
        check(RednetAutoConfiguration.hostname(id).equals("node-123456781234"),
                "automatic hostname must be deterministic and readable");
        check(RednetHostName.normalize(RednetAutoConfiguration.hostname(id)).isPresent(),
                "automatic hostname must obey RedNet hostname rules");
        check(RednetAutoConfiguration.isDefaultChannel(42)
                        && !RednetAutoConfiguration.isDefaultChannel(41),
                "default-channel predicate must be exact");
        check(RednetProtocol.CHANNEL.id().equals(RednetAutoConfiguration.CHANNEL_PROTOCOL_ID)
                        && RednetProtocol.CHANNEL.version() == 1,
                "default channel must advertise the built-in protocol contract");

        System.out.println("RednetAutoConfigurationTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
