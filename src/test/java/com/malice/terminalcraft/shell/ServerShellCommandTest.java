package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceCallContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless coverage for the server-rack job shell surface and caller-context forwarding. */
public final class ServerShellCommandTest {
    private ServerShellCommandTest() {}

    public static void main(String[] args) {
        FakeHost host = new FakeHost();
        BashShell shell = new BashShell();
        shell.setHost(host);
        DeviceCallContext caller = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

        assertResult(shell.executeForResult("server list"), 0, List.of("server: no jobs"), "empty list");
        assertResult(shell.executeForResult("server queued"), 0, List.of("0"), "queued count");
        assertResult(shell.executeForResult("server scheduler"), 0,
                List.of("tick=42 budget=2 executed=1 eligible=0 deferred=1 queued=1 running=0 retained_finished=0"),
                "bounded scheduler diagnostics");
        ShellCommandResult auth = shell.executeForResult("auth status", caller);
        require(auth.exitCode() == 0 && auth.outputLines().size() == 5,
                "auth status exposes one bounded central-decision summary");
        require(auth.outputLines().get(0).startsWith("principal=player:"),
                "auth status exposes typed authority rather than display-name authority");
        require(auth.outputLines().contains("discover=allowed")
                        && auth.outputLines().contains("read=allowed")
                        && auth.outputLines().contains("mutate=allowed")
                        && auth.outputLines().contains("escrow_admin=denied"),
                "auth status reports central decisions without inventing grants");
        assertResult(shell.executeForResult("auth grant device.write", caller), 1,
                List.of("auth: usage: auth status"), "auth command is diagnostic-only");

        shell.execute("server submit echo hello world", caller);
        require(host.lastContext.equals(caller), "submit forwards authenticated caller context");
        require(host.lastCommand.equals("echo hello world"), "submit forwards complete command");
        require(shell.getLastExitCode() == 0, "submit succeeds");

        assertResult(shell.executeForResult("server status " + host.id, caller), 0,
                List.of(host.summary), "status");
        assertResult(shell.executeForResult("server list", caller), 0, List.of(host.summary), "list");
        shell.execute("server cancel " + host.id, caller);
        require(shell.getLastExitCode() == 0, "cancel succeeds");
        require(host.cancelled, "cancel reaches host");

        DeviceCallContext sameIdService = DeviceCallContext.service(caller.principalId(), "alice-service",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        assertResult(shell.executeForResult("server list", sameIdService), 0,
                List.of("server: no jobs"), "same-UUID service cannot list player jobs");
        assertResult(shell.executeForResult("server status " + host.id, sameIdService), 1,
                List.of("server: job not found"), "same-UUID service cannot inspect player job");

        assertResult(shell.executeForResult("server submit"), 1,
                List.of("server: usage: server submit <command> [arguments...]"), "missing command");
        assertResult(shell.executeForResult("server status invalid"), 1,
                List.of("server: job not found"), "unknown status");

        BashShell ordinaryShell = new BashShell();
        assertResult(ordinaryShell.executeForResult("server list"), 1,
                List.of("server: no local server rack available"), "non-rack host");

        System.out.println("Server shell command tests: OK");
    }

    private static void assertResult(ShellCommandResult actual, int exitCode,
                                     List<String> output, String message) {
        if (actual.exitCode() != exitCode || !actual.outputLines().equals(output)) {
            throw new AssertionError(message + ": expected exit=" + exitCode + " output=" + output
                    + ", actual exit=" + actual.exitCode() + " output=" + actual.outputLines());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeHost implements TerminalHost {
        private final String id = "00000000-0000-0000-0000-000000000001";
        private final String summary = id + " queued exit=-1 owner=alice command=echo hello world";
        private DeviceCallContext lastContext;
        private String lastCommand = "";
        private boolean cancelled;

        @Override
        public String serverSubmit(DeviceCallContext context, String command) {
            lastContext = context;
            lastCommand = command;
            return id;
        }

        @Override
        public List<String> serverJobs(DeviceCallContext context) {
            return lastContext != null && lastContext.principal().equals(context.principal())
                    ? List.of(summary) : List.of();
        }

        @Override
        public String serverJob(DeviceCallContext context, String requestedId) {
            return lastContext != null && lastContext.principal().equals(context.principal())
                    && id.equals(requestedId) ? summary : "";
        }

        @Override
        public boolean serverCancel(DeviceCallContext context, String requestedId) {
            require(context != null && context.principalName().equals("alice"),
                    "cancel forwards authenticated caller context");
            cancelled = id.equals(requestedId);
            return cancelled;
        }

        @Override public int serverQueuedJobs() { return lastContext == null ? 0 : 1; }
        @Override public String serverSchedulerDiagnostics() {
            return "tick=42 budget=2 executed=1 eligible=0 deferred=1 queued=1 running=0 retained_finished=0";
        }
        @Override public int getRedstoneInput(String side) { return 0; }
        @Override public int getRedstoneOutput(String side) { return 0; }
        @Override public boolean setRedstoneOutput(String side, int power) { return false; }
        @Override public List<String> redstoneSides() { return List.of(); }
        @Override public List<String> listPeripherals() { return List.of(); }
        @Override public String getLabel() { return "test-rack"; }
        @Override public void setLabel(String label) {}
    }
}
