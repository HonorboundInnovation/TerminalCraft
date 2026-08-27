package com.malice.terminalcraft.client;

import com.malice.terminalcraft.network.OperationsProjectPackets;

import java.util.Objects;
import java.util.function.Consumer;

/** Client-only delivery point used by Operations Project editor screens. */
public final class ClientOperationsPackets {
    private static Consumer<OperationsProjectPackets.Response> listener = ignored -> {};

    private ClientOperationsPackets() {}

    public static void setListener(Consumer<OperationsProjectPackets.Response> next) {
        listener = Objects.requireNonNull(next, "next");
    }

    public static void clearListener() {
        listener = ignored -> {};
    }

    public static void apply(OperationsProjectPackets.Response response) {
        listener.accept(Objects.requireNonNull(response, "response"));
    }
}
