package com.malice.terminalcraft.shell;

import java.util.List;

/**
 * Immutable result of a shell execution request.
 *
 * @param exitCode final shell exit code
 * @param outputLines lines emitted during this request, excluding pre-existing scrollback
 */
public record ShellCommandResult(int exitCode, List<String> outputLines) {
    public ShellCommandResult {
        outputLines = List.copyOf(outputLines);
    }

    public boolean succeeded() {
        return exitCode == 0;
    }
}
