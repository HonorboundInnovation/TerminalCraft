package com.malice.terminalcraft.network;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic acknowledgement, timeout, retry, correlation, and bound coverage. */
public final class RednetDeliveryRuntimeTest {
    private RednetDeliveryRuntimeTest() {}

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        NetworkEnvelope request = request(id);
        RednetDeliveryRuntime runtime = new RednetDeliveryRuntime();
        AtomicInteger attempts = new AtomicInteger();

        RednetDeliveryRuntime.Delivery accepted = runtime.submit(
                request, 10, 5, 2, envelope -> attempts.incrementAndGet() > 1);
        check(accepted.state() == RednetDeliveryRuntime.State.ACCEPTED
                        && accepted.attempts() == 1 && accepted.deadline() == 15,
                "rejected first acceptance must remain pending for a bounded retry");
        check(runtime.tick(14, envelope -> { throw new AssertionError("not due"); }) == 0,
                "delivery must not retry before its logical deadline");
        check(runtime.tick(15, envelope -> attempts.incrementAndGet() > 1) == 1,
                "delivery must retry at its logical deadline");
        RednetDeliveryRuntime.Delivery retried = runtime.delivery(id).orElseThrow();
        check(retried.state() == RednetDeliveryRuntime.State.ACCEPTED && retried.attempts() == 2,
                "accepted retry must await acknowledgement");

        NetworkEnvelope wrongAck = acknowledgement(UUID.randomUUID(), UUID.randomUUID());
        check(!runtime.acknowledge(wrongAck, 16), "unknown correlation must be rejected");
        NetworkEnvelope spoofedAck = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION,
                UUID.randomUUID(), "attacker", "sender", 81, 80,
                RednetDeliveryRuntime.ACK_PROTOCOL, NetworkEnvelope.TEXT_PAYLOAD, "accepted",
                16, 8, "", id);
        check(!runtime.acknowledge(spoofedAck, 16),
                "matching correlation from the wrong endpoint must be rejected");
        NetworkEnvelope wrongProtocol = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION,
                UUID.randomUUID(), "receiver", "sender", 81, 80,
                "terminalcraft:not-ack", NetworkEnvelope.TEXT_PAYLOAD, "accepted",
                16, 8, "", id);
        check(!runtime.acknowledge(wrongProtocol, 16),
                "matching endpoints with a non-ack protocol must be rejected");
        NetworkEnvelope wrongPayload = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION,
                UUID.randomUUID(), "receiver", "sender", 81, 80,
                RednetDeliveryRuntime.ACK_PROTOCOL, NetworkEnvelope.TEXT_PAYLOAD, "rejected",
                16, 8, "", id);
        check(!runtime.acknowledge(wrongPayload, 16),
                "acknowledgement payload must use the fixed accepted schema");
        NetworkEnvelope wrongPorts = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION,
                UUID.randomUUID(), "receiver", "sender", 80, 81,
                RednetDeliveryRuntime.ACK_PROTOCOL, NetworkEnvelope.TEXT_PAYLOAD,
                RednetDeliveryRuntime.ACK_PAYLOAD, 16, 8, "", id);
        check(!runtime.acknowledge(wrongPorts, 16),
                "acknowledgement ports must reverse the request ports");
        NetworkEnvelope ack = acknowledgement(UUID.randomUUID(), id);
        check(runtime.acknowledge(ack, 16), "matching acknowledgement must complete delivery");
        check(!runtime.acknowledge(ack, 17), "terminal delivery must reject duplicate acknowledgement");
        check(runtime.delivery(id).orElseThrow().state() == RednetDeliveryRuntime.State.ACKNOWLEDGED,
                "acknowledged delivery must remain terminal");
        check(runtime.submit(request, 20, 5, 2, envelope -> {
            throw new AssertionError("message-ID replay must not transmit again");
        }).state() == RednetDeliveryRuntime.State.ACKNOWLEDGED,
                "message-ID replay must return the existing result");

        UUID timeoutId = UUID.randomUUID();
        runtime.submit(request(timeoutId), 30, 2, 0, envelope -> true);
        check(runtime.tick(32, envelope -> true) == 0
                        && runtime.delivery(timeoutId).orElseThrow().state()
                        == RednetDeliveryRuntime.State.TIMED_OUT,
                "zero-retry delivery must time out without another attempt");

        UUID rejectedId = UUID.randomUUID();
        runtime.submit(request(rejectedId), 40, 2, 1, envelope -> false);
        check(runtime.tick(42, envelope -> false) == 1,
                "one configured retry must run once");
        check(runtime.delivery(rejectedId).orElseThrow().state()
                        == RednetDeliveryRuntime.State.REJECTED,
                "exhausted rejected attempts must become terminal");
        RednetDeliveryRuntime.Diagnostics diagnostics = runtime.diagnostics();
        check(diagnostics.retained() == 3 && diagnostics.pending() == 0
                        && diagnostics.attempting() == 0 && diagnostics.accepted() == 0
                        && diagnostics.acknowledged() == 1 && diagnostics.rejected() == 1
                        && diagnostics.timedOut() == 1,
                "aggregate diagnostics must classify every retained delivery without exposing envelopes");

        expectFailure(() -> runtime.submit(request(UUID.randomUUID()), -1, 1, 0, envelope -> true));
        expectFailure(() -> runtime.submit(request(UUID.randomUUID()), 0, 0, 0, envelope -> true));
        expectFailure(() -> runtime.submit(request(UUID.randomUUID()), 0, 1,
                RednetDeliveryRuntime.MAX_RETRIES + 1, envelope -> true));
        NetworkEnvelope alreadyCorrelated = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION,
                UUID.randomUUID(), "a", "b", 1, 2, "terminalcraft:test",
                NetworkEnvelope.TEXT_PAYLOAD, "payload", 0, 8, "a:2", UUID.randomUUID());
        expectFailure(() -> runtime.submit(alreadyCorrelated, 0, 1, 0, envelope -> true));

        System.out.println("RednetDeliveryRuntimeTest: all tests passed");
    }

    private static NetworkEnvelope request(UUID id) {
        return new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "sender", "receiver",
                80, 81, "terminalcraft:test", NetworkEnvelope.TEXT_PAYLOAD, "hello",
                10, 8, "sender:81", null);
    }

    private static NetworkEnvelope acknowledgement(UUID id, UUID correlation) {
        return new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, id, "receiver", "sender",
                81, 80, RednetDeliveryRuntime.ACK_PROTOCOL, NetworkEnvelope.TEXT_PAYLOAD,
                RednetDeliveryRuntime.ACK_PAYLOAD, 16, 8, "", correlation);
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); throw new AssertionError("expected validation failure"); }
        catch (IllegalArgumentException expected) { /* Expected. */ }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
