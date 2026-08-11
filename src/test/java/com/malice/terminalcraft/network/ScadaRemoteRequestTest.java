package com.malice.terminalcraft.network;

/** Headless protocol tests for bounded SCADA RedNet requests. */
public final class ScadaRemoteRequestTest {
    private ScadaRemoteRequestTest() {}

    public static void main(String[] args) {
        roundTripAllOperations();
        malformedAndOversizedRequestsFailClosed();
        System.out.println("SCADA remote request tests: OK");
    }

    private static void roundTripAllOperations() {
        for (ScadaRemoteRequest.Operation operation : ScadaRemoteRequest.Operation.values()) {
            String selector = operation == ScadaRemoteRequest.Operation.READ
                    || operation == ScadaRemoteRequest.Operation.HISTORY ? "factory.boiler.temperature" : "";
            ScadaRemoteRequest request = new ScadaRemoteRequest(operation, selector, 32);
            require(ScadaRemoteRequest.decode(request.encode()).orElseThrow().equals(request),
                    "SCADA protocol round trip " + operation);
        }
    }

    private static void malformedAndOversizedRequestsFailClosed() {
        require(ScadaRemoteRequest.decode("1|read||32").isEmpty(), "read requires selector");
        require(ScadaRemoteRequest.decode("1|tags||999").isEmpty(), "limit is bounded");
        require(ScadaRemoteRequest.decode("2|status||1").isEmpty(), "unknown protocol version rejected");
        require(ScadaRemoteRequest.decode("garbage").isEmpty(), "malformed payload rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
