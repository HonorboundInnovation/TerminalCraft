package com.malice.terminalcraft.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry-backed boundary for shell builtins.
 *
 * <p>The registry deliberately knows nothing about parsing, the virtual filesystem, Minecraft,
 * or shell persistence. It only maps command names and aliases to bounded handlers owned by the
 * shell runtime.</p>
 */
final class ShellCommandRegistry {
    @FunctionalInterface
    interface Handler {
        void execute(List<String> arguments);
    }

    private final Map<String, Handler> handlers = new LinkedHashMap<>();

    void register(String name, Handler handler, String... aliases) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        put(name, handler);
        for (String alias : aliases) {
            put(alias, handler);
        }
    }

    void install(ShellCommandModule module, ShellCommandModule.Context context) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(context, "context");
        module.register((name, handler, aliases) ->
                register(name, arguments -> handler.execute(context, arguments), aliases));
    }

    boolean dispatch(String name, List<String> arguments) {
        Handler handler = handlers.get(name);
        if (handler == null) {
            return false;
        }
        handler.execute(List.copyOf(arguments));
        return true;
    }

    List<String> commandNames() {
        return List.copyOf(new ArrayList<>(handlers.keySet()));
    }

    private void put(String name, Handler handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Command names must not be blank");
        }
        if (handlers.putIfAbsent(name, handler) != null) {
            throw new IllegalArgumentException("Duplicate shell command: " + name);
        }
    }
}
