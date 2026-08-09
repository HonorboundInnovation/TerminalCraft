package com.malice.terminalcraft.shell;

import java.util.List;

/**
 * Independently installable shell command group.
 *
 * <p>Modules receive only a bounded command context and registrar, allowing new conceptual command
 * families to live outside {@link BashShell}.</p>
 */
public interface ShellCommandModule {
    void register(Registrar registrar);

    @FunctionalInterface
    interface Handler {
        void execute(Context context, List<String> arguments);
    }

    interface Registrar {
        void register(String name, Handler handler, String... aliases);
    }

    interface Context {
        TerminalHostServices hostServices();
        /** Raw world host for command families that expose a specialized controller API. */
        default TerminalHost worldHost() { return null; }
        /** Reads a shell/VFS path relative to the active shell's working directory. */
        default String readFile(String path) { return null; }
        com.malice.terminalcraft.device.DeviceCallContext callerContext();
        void printLine(String line);
        void setExitCode(int exitCode);
    }
}
