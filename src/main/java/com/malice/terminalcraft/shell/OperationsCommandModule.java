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
                    "server: usage: server list|queued|scheduler|submit <command...>|status <uuid>|cancel <uuid>");
        }
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
