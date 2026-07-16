package com.malice.terminalcraft.network;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Headless protocol-envelope validation and numeric-channel compatibility tests. */
public final class NetworkEnvelopeTest {
    private NetworkEnvelopeTest() {}

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        NetworkEnvelope original = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id,
                "terminal-a", "terminal-b", 80, 81, "terminalcraft:test",
                "application/json", "{}", 123L, 8, "terminal-a:81", correlation);
        NetworkEnvelope decoded = NetworkEnvelope.load(original.save()).orElseThrow();
        check(decoded.equals(original), "valid version-2 envelope must round-trip exactly");
        check(decoded.forwarded().hopLimit() == 7, "forwarding must decrement hop limit");
        check(decoded.forwarded(3).hopLimit() == 5, "multi-hop forwarding must decrement exactly once per router");
        check(decoded.forwarded(0) == decoded, "zero-hop delivery may retain the immutable envelope instance");
        expectStateFailure(() -> decoded.forwarded(9), "forwarding beyond the remaining limit must fail");

        CompoundTag legacy = original.save();
        legacy.putInt("Version", NetworkEnvelope.LEGACY_VERSION);
        legacy.remove("PayloadType");
        legacy.remove("ReplyTo");
        NetworkEnvelope migrated = NetworkEnvelope.load(legacy).orElseThrow();
        check(migrated.version() == NetworkEnvelope.CURRENT_VERSION,
                "legacy envelopes migrate to the current in-memory version");
        check(NetworkEnvelope.TEXT_PAYLOAD.equals(migrated.payloadType()) && migrated.replyTo().isEmpty(),
                "legacy envelopes receive explicit text and empty reply-to defaults");

        NetworkEnvelope channel = NumericChannelCompatibilityAdapter.encode(
                id, "terminal-a", 12, 34, "hello", 50);
        NumericChannelCompatibilityAdapter.ChannelMessage projected =
                NumericChannelCompatibilityAdapter.decode(channel);
        check(projected.channel() == 12 && projected.replyChannel() == 34
                        && projected.message().equals("hello"),
                "numeric channel adapter preserves channel, reply channel, and message");
        check(channel.version() == NetworkEnvelope.CURRENT_VERSION
                        && channel.destination().equals("*")
                        && channel.protocol().equals(NumericChannelCompatibilityAdapter.PROTOCOL),
                "numeric channel traffic uses the documented current envelope mapping");
        expectFailure(() -> NumericChannelCompatibilityAdapter.decode(original),
                "non-channel envelopes must not be projected as numeric messages");

        CompoundTag unsupported = original.save();
        unsupported.putInt("Version", 99);
        check(NetworkEnvelope.load(unsupported).isEmpty(), "unsupported version must be rejected");

        CompoundTag missingId = original.save();
        missingId.remove("MessageId");
        check(NetworkEnvelope.load(missingId).isEmpty(), "missing message ID must be rejected");

        CompoundTag oversized = original.save();
        oversized.putString("Payload", "x".repeat(NetworkEnvelope.MAX_PAYLOAD_LENGTH + 1));
        check(NetworkEnvelope.load(oversized).isEmpty(), "oversized payload must be rejected");

        CompoundTag missingV2Type = original.save();
        missingV2Type.remove("PayloadType");
        check(NetworkEnvelope.load(missingV2Type).isEmpty(), "version-2 payload type is required");
        CompoundTag missingV2ReplyTo = original.save();
        missingV2ReplyTo.remove("ReplyTo");
        check(NetworkEnvelope.load(missingV2ReplyTo).isEmpty(), "version-2 reply-to is required");

        expectFailure(() -> new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "a", "b",
                -1, 0, "test", NetworkEnvelope.TEXT_PAYLOAD, "", 0, 1, "", null),
                "invalid port must be rejected");
        expectFailure(() -> new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "a", "b",
                0, 0, "test", NetworkEnvelope.TEXT_PAYLOAD, "", 0,
                NetworkEnvelope.MAX_HOPS + 1, "", null), "invalid hop limit must be rejected");
        expectFailure(() -> new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "a", "b",
                0, 0, "", NetworkEnvelope.TEXT_PAYLOAD, "", 0, 1, "", null),
                "blank protocol must be rejected");
        expectFailure(() -> new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "a", "b",
                0, 0, "test", "", "", 0, 1, "", null),
                "blank payload type must be rejected");
        expectFailure(() -> new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "a", "b",
                0, 0, "test", NetworkEnvelope.TEXT_PAYLOAD, "", -1, 1, "", null),
                "negative logical time must be rejected");

        for (String requiredNumeric : java.util.List.of(
                "Version", "Port", "ReplyPort", "GameTime", "HopLimit")) {
            CompoundTag missing = original.save().copy();
            missing.remove(requiredNumeric);
            check(NetworkEnvelope.load(missing).isEmpty(),
                    "missing required numeric field rejected: " + requiredNumeric);
        }

        System.out.println("NetworkEnvelopeTest: all tests passed");
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { /* Expected validation failure. */ }
    }

    private static void expectStateFailure(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalStateException expected) { /* Expected exhausted-hop failure. */ }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
