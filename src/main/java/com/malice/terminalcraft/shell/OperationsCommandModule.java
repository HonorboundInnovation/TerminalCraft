package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAuthorization;
import com.malice.terminalcraft.device.DeviceCallContext;

import java.util.List;
import java.util.Locale;

/** Operational commands for local jobs, scheduler admission, and caller authorization. */
final class OperationsCommandModule implements ShellCommandModule {
    @Override
    public void register(Registrar registrar) {
        registrar.register("server", this::server, "jobs");
        registrar.register("auth", this::auth, "authorization");
        registrar.register("rsh", this::remoteShell);
        registrar.register("service", this::service);
    }

    private void server(Context context, List<String> arguments) {
        TerminalHostServices services = context.hostServices();
        if (services == null || services.serverJobs().queuedJobs() < 0) {
            fail(context, "server: no local server rack available");
            return;
        }
        TerminalHostServices.ServerJobs jobs = services.serverJobs();
        String operation = arguments.isEmpty() ? "list" : arguments.get(0).toLowerCase(Locale.ROOT);
        switch (operation) {
            case "policy" -> {
                String policy = context.worldHost() == null ? "" : context.worldHost().serverResourcePolicy();
                if (policy.isEmpty()) fail(context, "server: resource policy unavailable");
                else { context.printLine(policy); context.setExitCode(0); }
            }
            case "remote" -> {
                if (context.worldHost() == null) { fail(context, "server remote: local rack required"); return; }
                var result = context.worldHost().serverRemoteConfigure(context.callerContext(), arguments.subList(1, arguments.size()));
                result.outputLines().forEach(context::printLine);
                context.setExitCode(result.exitCode());
            }
            case "list" -> {
                List<String> found = jobs.jobs(context.callerContext());
                if (found.isEmpty()) context.printLine("server: no jobs");
                else found.forEach(context::printLine);
                context.setExitCode(0);
            }
            case "queued" -> {
                context.printLine(Integer.toString(jobs.queuedJobs()));
                context.setExitCode(0);
            }
            case "scheduler" -> {
                String diagnostics = jobs.schedulerDiagnostics();
                if (diagnostics.isEmpty()) fail(context, "server: scheduler diagnostics unavailable");
                else { context.printLine(diagnostics); context.setExitCode(0); }
            }
            case "submit" -> {
                if (arguments.size() < 2) {
                    fail(context, "server: usage: server submit <command> [arguments...]");
                    return;
                }
                String id = jobs.submit(context.callerContext(), String.join(" ", arguments.subList(1, arguments.size())));
                if (id.isEmpty()) fail(context, "server: job rejected (empty, too large, or queue full)");
                else { context.printLine(id); context.setExitCode(0); }
            }
            case "after" -> {
                if (arguments.size() < 3) { fail(context, "server: usage: server after <ticks> <command...>"); return; }
                long delay;
                try { delay = Long.parseLong(arguments.get(1)); }
                catch (NumberFormatException invalid) { fail(context, "server: delay must be a nonnegative integer number of game ticks"); return; }
                if (delay < 0) { fail(context, "server: delay must be a nonnegative integer number of game ticks"); return; }
                String id = jobs.schedule(context.callerContext(), String.join(" ", arguments.subList(2, arguments.size())), delay);
                if (id.isEmpty()) fail(context, "server: scheduled job rejected (delay, command, capacity, or unavailable rack)");
                else { context.printLine(id); context.setExitCode(0); }
            }
            case "status" -> {
                if (arguments.size() != 2) {
                    fail(context, "server: usage: server status <job-uuid>");
                    return;
                }
                String status = jobs.job(context.callerContext(), arguments.get(1));
                if (status.isEmpty()) fail(context, "server: job not found");
                else { context.printLine(status); context.setExitCode(0); }
            }
            case "cancel" -> {
                if (arguments.size() != 2 || !jobs.cancel(context.callerContext(), arguments.get(1))) {
                    fail(context, "server: unable to cancel job");
                } else context.setExitCode(0);
            }
            default -> fail(context,
                    "server: usage: server list|queued|scheduler|policy|submit <command...>|after <ticks> <command...>|status <uuid>|cancel <uuid>|remote on|off|status|audit|allow <player-uuid>|deny <player-uuid>");
        }
    }

    private void remoteShell(Context context, List<String> arguments) {
        if (arguments.size() < 2 || context.worldHost() == null || context.callerContext() == null) {
            fail(context, "rsh: usage: rsh <rack-uuid> '<command...>' or rsh connect <rack-uuid>"); return;
        }
        java.util.UUID target;
        boolean connect = arguments.get(0).equals("connect");
        if (connect && arguments.size() != 2) { fail(context, "rsh: usage: rsh connect <rack-uuid>"); return; }
        try { target = java.util.UUID.fromString(arguments.get(connect ? 1 : 0)); }
        catch (IllegalArgumentException invalid) { fail(context, "rsh: use a rack UUID from device list remote_shell"); return; }
        if (connect) {
            if (!context.connectRemoteShell(target)) fail(context, "rsh: unable to connect");
            return;
        }
        callRemote(context, target, String.join(" ", arguments.subList(1, arguments.size())));
    }

    /** False means transport/admission failed; a remote command's nonzero exit still returns true. */
    static boolean callRemote(Context context, java.util.UUID target, String command) {
        var access = context.worldHost().deviceAccess(context.callerContext());
        if (access == null) { fail(context, "rsh: device registry unavailable"); return false; }
        if (command.isBlank() || command.length() > com.malice.terminalcraft.device.DeviceValue.MAX_STRING_LENGTH) {
            fail(context, "rsh: command must contain 1 to 4096 characters"); return false;
        }
        var response = access.call(target, "shell.exec", List.of(com.malice.terminalcraft.device.DeviceValue.of(command)));
        if (!response.isSuccess()) {
            fail(context, "rsh: " + response.error().orElseThrow().message()); return false;
        }
        if (!(response.value().orElse(null) instanceof com.malice.terminalcraft.device.DeviceValue.MapValue map)
                || !(map.values().get("output") instanceof com.malice.terminalcraft.device.DeviceValue.StringValue output)
                || !(map.values().get("exit") instanceof com.malice.terminalcraft.device.DeviceValue.NumberValue exit)) {
            fail(context, "rsh: invalid rack response"); return false;
        }
        if (!output.value().isEmpty()) output.value().lines().forEach(context::printLine);
        if (map.values().get("truncated") instanceof com.malice.terminalcraft.device.DeviceValue.BooleanValue truncated && truncated.value())
            context.printLine("rsh: output truncated; save large output to a rack file and read it in parts");
        context.setExitCode((int) exit.value());
        return true;
    }

    private void service(Context context, List<String> arguments) {
        if (context.worldHost() == null) { fail(context, "service: local rack required"); return; }
        var result = context.worldHost().serverServiceCommand(context.callerContext(), arguments);
        result.outputLines().forEach(context::printLine);
        context.setExitCode(result.exitCode());
    }

    private void auth(Context context, List<String> arguments) {
        DeviceCallContext caller = context.callerContext();
        if (caller == null) {
            fail(context, "auth: no authenticated caller");
            return;
        }
        String operation = arguments.isEmpty() ? "status" : arguments.get(0).toLowerCase(Locale.ROOT);
        if (!"status".equals(operation)) {
            fail(context, "auth: usage: auth status");
            return;
        }
        context.printLine("principal=" + caller.principal().authorityKey());
        context.printLine("discover=" + decision(caller, DeviceAuthorization.Action.DISCOVER));
        context.printLine("read=" + decision(caller, DeviceAuthorization.Action.READ));
        context.printLine("mutate=" + decision(caller, DeviceAuthorization.Action.MUTATE));
        context.printLine("escrow_admin=" + decision(caller, DeviceAuthorization.Action.ESCROW_ADMIN));
        context.setExitCode(0);
    }

    private static String decision(DeviceCallContext caller, DeviceAuthorization.Action action) {
        return DeviceAuthorization.allows(caller, action) ? "allowed" : "denied";
    }

    private static void fail(Context context, String message) {
        context.printLine(message);
        context.setExitCode(1);
    }
}
