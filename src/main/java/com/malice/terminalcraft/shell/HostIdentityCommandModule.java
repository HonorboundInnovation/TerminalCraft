package com.malice.terminalcraft.shell;

import java.util.List;

/** Builtin identity/peripheral commands implemented independently from the shell runtime. */
final class HostIdentityCommandModule implements ShellCommandModule {
    @Override
    public void register(Registrar registrar) {
        registrar.register("peripheral", this::peripheral);
        registrar.register("label", this::label);
    }

    private void peripheral(Context context, List<String> arguments) {
        TerminalHostServices services = context.hostServices();
        if (services == null) {
            context.printLine("peripheral: no world host attached");
            context.setExitCode(1);
            return;
        }
        if (arguments.isEmpty() || "list".equals(arguments.get(0))) {
            List<String> found = services.identity().peripherals();
            if (found.isEmpty()) {
                context.printLine("(no peripherals)");
            } else {
                found.forEach(context::printLine);
            }
            context.setExitCode(0);
            return;
        }
        context.printLine("peripheral: usage: peripheral list");
        context.setExitCode(1);
    }

    private void label(Context context, List<String> arguments) {
        TerminalHostServices services = context.hostServices();
        if (services == null) {
            context.printLine("label: no world host attached");
            context.setExitCode(1);
            return;
        }
        if (arguments.isEmpty()) {
            context.printLine(services.identity().label());
            context.setExitCode(0);
            return;
        }
        services.identity().setLabel(String.join(" ", arguments));
        context.printLine(services.identity().label());
        context.setExitCode(0);
    }
}
