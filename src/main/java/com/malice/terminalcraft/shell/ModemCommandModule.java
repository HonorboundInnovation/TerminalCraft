package com.malice.terminalcraft.shell;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Bounded RedNet command and diagnostic surface. */
final class ModemCommandModule implements ShellCommandModule {
    @Override
    public void register(Registrar registrar) {
        registrar.register("modem", this::modem, "rednet");
    }

    private void modem(Context context, List<String> args) {
        TerminalHostServices hostServices = context.hostServices();
        TerminalHostServices.Modem modem = hostServices == null ? null : hostServices.modem();
        if (hostServices == null) {
            context.printLine("modem: no world host attached");
            context.setExitCode(1);
            return;
        }
        if (modem == null || !modem.available()) {
            context.printLine("modem: no adjacent modem");
            context.setExitCode(1);
            return;
        }
        if (args.isEmpty() || "help".equals(args.get(0))) {
            context.printLine("modem open|listen <channel>");
            context.printLine("modem close|unlisten <channel>");
            context.printLine("modem channels");
            context.printLine("modem auto [on|off]");
            context.printLine("modem status");
            context.printLine("modem hostname [name|clear]");
            context.printLine("modem network [name|clear]");
            context.printLine("modem interfaces");
            context.printLine("modem topology");
            context.printLine("modem diagnostics");
            context.printLine("modem route <host>");
            context.printLine("modem ping <host>");
            context.printLine("modem probe <host> <port> <replyChannel> <message>");
            context.printLine("modem delivery <messageId>");
            context.printLine("modem neighbors [max]");
            context.printLine("modem hosts");
            context.printLine("modem dns [list|resolve <name|uuid>|self]");
            context.printLine("modem service [list|add <name> <channel>|remove <name>]");
            context.printLine("modem sensor [list|add <name> <channel>|remove <name>|request <service> <list|snapshot|read> [channel] [replyChannel]]");
            context.printLine("modem scada [list|add <name> <channel>|remove <name>|request <service> <status|tags|read|history|alarms> <selector|-> <limit> <replyChannel>]");
            context.printLine("modem services");
            context.printLine("modem call <service> [replyChannel] <message>");
            context.printLine("modem send [channel] [replyChannel] <message>");
            context.printLine("modem sendto <host> [channel] [replyChannel] <message>");
            context.printLine("modem recv [max]");
            context.setExitCode(0);
            return;
        }
        String op = args.get(0).toLowerCase(Locale.ROOT);
        if ("open".equals(op) || "listen".equals(op)) {
            if (args.size() < 2) {
                context.printLine("modem: usage: modem " + op + " <channel>");
                context.setExitCode(1);
                return;
            }
            int ch;
            try {
                ch = Integer.parseInt(args.get(1));
            } catch (NumberFormatException ex) {
                context.printLine("modem: channel must be an integer");
                context.setExitCode(1);
                return;
            }
            if (!modem.open(ch)) {
                context.printLine("modem: failed to open channel " + ch);
                context.setExitCode(1);
                return;
            }
            context.printLine(("listen".equals(op) ? "listening " : "opened ") + ch);
            context.setExitCode(0);
            return;
        }
        if ("close".equals(op) || "unlisten".equals(op)) {
            if (args.size() < 2) {
                context.printLine("modem: usage: modem " + op + " <channel>");
                context.setExitCode(1);
                return;
            }
            int ch;
            try {
                ch = Integer.parseInt(args.get(1));
            } catch (NumberFormatException ex) {
                context.printLine("modem: channel must be an integer");
                context.setExitCode(1);
                return;
            }
            if (!modem.close(ch)) {
                context.printLine("modem: channel " + ch + " was not open");
                context.setExitCode(1);
                return;
            }
            context.printLine(("unlisten".equals(op) ? "unlistened " : "closed ") + ch);
            context.setExitCode(0);
            return;
        }
        if ("channels".equals(op) || "list".equals(op) || "openchannels".equals(op)) {
            List<Integer> chans = modem.openChannels();
            if (chans.isEmpty()) {
                context.printLine("(none)");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < chans.size(); i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(chans.get(i));
                }
                context.printLine(sb.toString());
            }
            context.setExitCode(0);
            return;
        }
        if ("auto".equals(op) || "automatic".equals(op) || "setup".equals(op)) {
            if (args.size() == 1) {
                context.printLine(modem.automaticSetup() ? "automatic setup on" : "automatic setup off");
                context.setExitCode(0);
                return;
            }
            if (args.size() != 2 || !("on".equalsIgnoreCase(args.get(1))
                    || "off".equalsIgnoreCase(args.get(1)))) {
                context.printLine("modem: usage: modem auto [on|off]");
                context.setExitCode(1);
                return;
            }
            boolean enabled = "on".equalsIgnoreCase(args.get(1));
            if (!modem.setAutomaticSetup(enabled)) {
                context.printLine("modem: automatic setup change failed");
                context.setExitCode(1);
                return;
            }
            context.printLine("automatic setup " + (enabled ? "on" : "off"));
            context.setExitCode(0);
            return;
        }
        if ("status".equals(op)) {
            if (args.size() != 1) {
                context.printLine("modem: usage: modem status");
                context.setExitCode(1);
                return;
            }
            List<String> status = modem.status();
            if (status.isEmpty()) context.printLine("(unavailable)");
            else status.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("hostname".equals(op) || "host".equals(op)) {
            if (args.size() == 1) {
                String name = modem.hostname();
                context.printLine(name.isEmpty() ? "(unregistered)" : name);
                context.setExitCode(0);
                return;
            }
            String requested = "clear".equalsIgnoreCase(args.get(1)) ? "" : args.get(1);
            if (!modem.setHostname(requested)) {
                context.printLine("modem: invalid or duplicate hostname");
                context.setExitCode(1);
                return;
            }
            context.printLine(requested.isEmpty() ? "hostname cleared" : "hostname " + modem.hostname());
            context.setExitCode(0);
            return;
        }
        if ("network".equals(op) || "net".equals(op)) {
            if (args.size() == 1) {
                String name = modem.networkName();
                context.printLine(name.isEmpty() ? "(automatic)" : name);
                context.setExitCode(0);
                return;
            }
            String requested = "clear".equalsIgnoreCase(args.get(1))
                    || "automatic".equalsIgnoreCase(args.get(1)) ? "" : args.get(1);
            if (!modem.setNetworkName(requested)) {
                context.printLine("modem: invalid network name");
                context.setExitCode(1);
                return;
            }
            context.printLine(requested.isEmpty() ? "network automatic" : "network " + modem.networkName());
            context.setExitCode(0);
            return;
        }
        if ("dns".equals(op) || "resolve".equals(op)) {
            boolean shorthandResolve = "resolve".equals(op);
            if (shorthandResolve) {
                if (args.size() != 2) {
                    context.printLine("modem: usage: modem resolve <name|uuid>");
                    context.setExitCode(1);
                    return;
                }
                String resolved = modem.resolve(args.get(1));
                if (resolved.isEmpty()) {
                    context.printLine("modem: name not found: " + args.get(1));
                    context.setExitCode(1);
                } else {
                    context.printLine(resolved);
                    context.setExitCode(0);
                }
                return;
            }
            String action = args.size() == 1 ? "list" : args.get(1).toLowerCase(Locale.ROOT);
            if ("list".equals(action)) {
                if (args.size() > 2) {
                    context.printLine("modem: usage: modem dns list");
                    context.setExitCode(1);
                    return;
                }
                List<String> records = modem.dns(128);
                if (records.isEmpty()) context.printLine("(none)");
                else records.forEach(context::printLine);
                context.setExitCode(0);
                return;
            }
            if ("self".equals(action)) {
                if (args.size() != 2) {
                    context.printLine("modem: usage: modem dns self");
                    context.setExitCode(1);
                    return;
                }
                String selector = modem.hostname();
                String resolved = selector.isEmpty() ? "" : modem.resolve(selector);
                if (resolved.isEmpty()) {
                    context.printLine("modem: current modem is not registered in DNS");
                    context.setExitCode(1);
                } else {
                    context.printLine(resolved);
                    context.setExitCode(0);
                }
                return;
            }
            if ("resolve".equals(action) && args.size() == 3) {
                String resolved = modem.resolve(args.get(2));
                if (resolved.isEmpty()) {
                    context.printLine("modem: name not found: " + args.get(2));
                    context.setExitCode(1);
                } else {
                    context.printLine(resolved);
                    context.setExitCode(0);
                }
                return;
            }
            context.printLine("modem: usage: modem dns [list|resolve <name|uuid>|self]");
            context.setExitCode(1);
            return;
        }
        if ("interfaces".equals(op) || "ifaces".equals(op)) {
            List<String> interfaces = modem.interfaces();
            if (interfaces.isEmpty()) context.printLine("(none)");
            else interfaces.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("topology".equals(op) || "topo".equals(op)) {
            if (args.size() != 1) {
                context.printLine("modem: usage: modem topology");
                context.setExitCode(1);
                return;
            }
            List<String> diagnostics = modem.topologyDiagnostics();
            if (diagnostics.isEmpty()) context.printLine("(none)");
            else diagnostics.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("diagnostics".equals(op) || "diag".equals(op) || "status".equals(op)) {
            if (args.size() != 1) {
                context.printLine("modem: usage: modem diagnostics");
                context.setExitCode(1);
                return;
            }
            List<String> diagnostics = modem.packetDiagnostics();
            if (diagnostics.isEmpty()) context.printLine("(none)");
            else diagnostics.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("neighbors".equals(op) || "neighbours".equals(op)) {
            if (args.size() > 2) {
                context.printLine("modem: usage: modem neighbors [max]");
                context.setExitCode(1);
                return;
            }
            int maximum = 32;
            if (args.size() == 2) {
                try {
                    maximum = Integer.parseInt(args.get(1));
                } catch (NumberFormatException ex) {
                    context.printLine("modem: max must be an integer");
                    context.setExitCode(1);
                    return;
                }
                if (maximum < 1 || maximum > 128) {
                    context.printLine("modem: max must be between 1 and 128");
                    context.setExitCode(1);
                    return;
                }
            }
            List<String> neighbors = modem.neighbors(maximum);
            if (neighbors.isEmpty()) context.printLine("(none)");
            else neighbors.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("ping".equals(op)) {
            if (args.size() != 2) {
                context.printLine("modem: usage: modem ping <host>");
                context.setExitCode(1);
                return;
            }
            List<String> result = modem.ping(args.get(1));
            if (result.isEmpty()) {
                context.printLine("modem: no response from " + args.get(1));
                context.setExitCode(1);
                return;
            }
            result.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("probe".equals(op)) {
            if (args.size() < 5) {
                context.printLine("modem: usage: modem probe <host> <port> <replyChannel> <message>");
                context.setExitCode(1);
                return;
            }
            int port;
            int reply;
            try {
                port = Integer.parseInt(args.get(2));
                reply = Integer.parseInt(args.get(3));
            } catch (NumberFormatException invalid) {
                context.printLine("modem: port and reply channel must be integers");
                context.setExitCode(1);
                return;
            }
            if (port < 0 || port > 65535 || reply < 0 || reply > 65535) {
                context.printLine("modem: port and reply channel must be between 0 and 65535");
                context.setExitCode(1);
                return;
            }
            String result = modem.probe(args.get(1), port, reply,
                    String.join(" ", args.subList(4, args.size())));
            if (result.isEmpty()) {
                context.printLine("modem: reliable delivery was not admitted");
                context.setExitCode(1);
                return;
            }
            context.printLine(result);
            context.setExitCode(0);
            return;
        }
        if ("delivery".equals(op)) {
            if (args.size() != 2) {
                context.printLine("modem: usage: modem delivery <messageId>");
                context.setExitCode(1);
                return;
            }
            try {
                UUID.fromString(args.get(1));
            } catch (IllegalArgumentException invalid) {
                context.printLine("modem: invalid message id");
                context.setExitCode(1);
                return;
            }
            String result = modem.delivery(args.get(1));
            if (result.isEmpty()) {
                context.printLine("modem: delivery not found");
                context.setExitCode(1);
                return;
            }
            context.printLine(result);
            context.setExitCode(0);
            return;
        }
        if ("route".equals(op) || "trace".equals(op)) {
            if (args.size() != 2) {
                context.printLine("modem: usage: modem route <host>");
                context.setExitCode(1);
                return;
            }
            List<String> route = modem.route(args.get(1));
            if (route.isEmpty()) {
                context.printLine("modem: no route to " + args.get(1));
                context.setExitCode(1);
                return;
            }
            route.forEach(context::printLine);
            context.setExitCode(0);
            return;
        }
        if ("hosts".equals(op)) {
            List<String> names = modem.hosts(128);
            if (names.isEmpty()) context.printLine("(none)");
            else for (String name : names) context.printLine(name);
            context.setExitCode(0);
            return;
        }
        if ("service".equals(op)) {
            String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "list";
            if ("list".equals(action)) {
                List<String> registrations = modem.localServices();
                if (registrations.isEmpty()) context.printLine("(none)");
                else for (String registration : registrations) context.printLine(registration);
                context.setExitCode(0);
                return;
            }
            if ("add".equals(action) || "register".equals(action)) {
                if (args.size() != 4) {
                    context.printLine("modem: usage: modem service add <name> <channel>");
                    context.setExitCode(1);
                    return;
                }
                int port;
                try {
                    port = Integer.parseInt(args.get(3));
                } catch (NumberFormatException ex) {
                    context.printLine("modem: channel must be an integer");
                    context.setExitCode(1);
                    return;
                }
                if (!modem.registerService(args.get(2), port)) {
                    context.printLine("modem: service registration failed (invalid, duplicate, limit reached, or channel closed)");
                    context.setExitCode(1);
                    return;
                }
                context.printLine("service " + args.get(2).toLowerCase(Locale.ROOT) + " " + Math.max(0, Math.min(65535, port)));
                context.setExitCode(0);
                return;
            }
            if ("remove".equals(action) || "unregister".equals(action)) {
                if (args.size() != 3) {
                    context.printLine("modem: usage: modem service remove <name>");
                    context.setExitCode(1);
                    return;
                }
                if (!modem.unregisterService(args.get(2))) {
                    context.printLine("modem: service is not registered by this modem");
                    context.setExitCode(1);
                    return;
                }
                context.printLine("service removed " + args.get(2).toLowerCase(Locale.ROOT));
                context.setExitCode(0);
                return;
            }
            context.printLine("modem: usage: modem service [list|add <name> <channel>|remove <name>]");
            context.setExitCode(1);
            return;
        }
        if ("sensor".equals(op)) {
            String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "list";
            if ("list".equals(action)) {
                List<String> registrations = modem.sensorServices();
                if (registrations.isEmpty()) context.printLine("(none)");
                else registrations.forEach(context::printLine);
                context.setExitCode(0);
                return;
            }
            if ("add".equals(action) || "register".equals(action)) {
                if (args.size() != 4) { fail(context, "modem: usage: modem sensor add <name> <channel>"); return; }
                int port;
                try { port = Integer.parseInt(args.get(3)); }
                catch (NumberFormatException invalid) { fail(context, "modem: channel must be an integer"); return; }
                if (!modem.registerSensorService(args.get(2), port)) {
                    fail(context, "modem: sensor service registration failed (adjacent array, channel, or name invalid)");
                    return;
                }
                context.printLine("sensor service " + args.get(2).toLowerCase(Locale.ROOT) + " " + port);
                context.setExitCode(0);
                return;
            }
            if ("remove".equals(action) || "unregister".equals(action)) {
                if (args.size() != 3 || !modem.unregisterSensorService(args.get(2))) {
                    fail(context, "modem: usage: modem sensor remove <name>"); return;
                }
                context.printLine("sensor service removed " + args.get(2).toLowerCase(Locale.ROOT));
                context.setExitCode(0);
                return;
            }
            if ("request".equals(action) || "read".equals(action) || "snapshot".equals(action)) {
                if (args.size() < 4) {
                    fail(context, "modem: usage: modem sensor request <service> <list|snapshot|read> [channel] [replyChannel]");
                    return;
                }
                String service = args.get(2);
                String operation = args.get(3).toLowerCase(Locale.ROOT);
                String channel = "";
                int reply = 0;
                int next = 4;
                if ("read".equals(operation)) {
                    if (args.size() <= next) { fail(context, "modem: sensor read requires a channel"); return; }
                    channel = args.get(next++);
                }
                if (args.size() > next) {
                    try { reply = Integer.parseInt(args.get(next++)); }
                    catch (NumberFormatException invalid) { fail(context, "modem: reply channel must be an integer"); return; }
                }
                if (args.size() != next || !modem.transmitSensorService(service, operation, channel, reply)) {
                    fail(context, "modem: sensor request failed (service offline, unreachable, or malformed)");
                    return;
                }
                context.printLine("sensor request sent service=" + service + " reply=" + reply);
                context.setExitCode(0);
                return;
            }
            fail(context, "modem: usage: modem sensor [list|add <name> <channel>|remove <name>|request <service> <list|snapshot|read> [channel] [replyChannel]]");
            return;
        }
        if ("scada".equals(op)) {
            String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "list";
            if ("list".equals(action)) {
                List<String> registrations = modem.scadaServices();
                if (registrations.isEmpty()) context.printLine("(none)");
                else registrations.forEach(context::printLine);
                context.setExitCode(0);
                return;
            }
            if ("add".equals(action) || "register".equals(action)) {
                if (args.size() != 4) { fail(context, "modem: usage: modem scada add <name> <channel>"); return; }
                int port;
                try { port = Integer.parseInt(args.get(3)); }
                catch (NumberFormatException invalid) { fail(context, "modem: channel must be an integer"); return; }
                if (!modem.registerScadaService(args.get(2), port)) {
                    fail(context, "modem: SCADA service registration failed (one adjacent server rack, open channel, and unique name required)");
                    return;
                }
                context.printLine("SCADA service " + args.get(2).toLowerCase(Locale.ROOT) + " " + port);
                context.setExitCode(0);
                return;
            }
            if ("remove".equals(action) || "unregister".equals(action)) {
                if (args.size() != 3 || !modem.unregisterScadaService(args.get(2))) {
                    fail(context, "modem: usage: modem scada remove <name>"); return;
                }
                context.printLine("SCADA service removed " + args.get(2).toLowerCase(Locale.ROOT));
                context.setExitCode(0);
                return;
            }
            if ("request".equals(action)) {
                if (args.size() != 7) {
                    fail(context, "modem: usage: modem scada request <service> <status|tags|read|history|alarms> <selector|-> <limit> <replyChannel>");
                    return;
                }
                int limit;
                int reply;
                try {
                    limit = Integer.parseInt(args.get(5));
                    reply = Integer.parseInt(args.get(6));
                } catch (NumberFormatException invalid) {
                    fail(context, "modem: limit and reply channel must be integers"); return;
                }
                String selector = "-".equals(args.get(4)) ? "" : args.get(4);
                if (!modem.transmitScadaService(args.get(2), args.get(3), selector, limit, reply)) {
                    fail(context, "modem: SCADA request failed (service offline, unreachable, or malformed)");
                    return;
                }
                context.printLine("SCADA request sent service=" + args.get(2) + " reply=" + reply);
                context.setExitCode(0);
                return;
            }
            fail(context, "modem: use modem scada list|add|remove|request");
            return;
        }
        if ("services".equals(op)) {
            List<String> services = modem.services(128);
            if (services.isEmpty()) context.printLine("(none)");
            else for (String service : services) context.printLine(service);
            context.setExitCode(0);
            return;
        }
        if ("call".equals(op)) {
            if (args.size() < 3) {
                context.printLine("modem: usage: modem call <service> [replyChannel] <message>");
                context.setExitCode(1);
                return;
            }
            String service = args.get(1);
            int reply = 0;
            int msgStart;
            try {
                reply = Integer.parseInt(args.get(2));
                msgStart = 3;
                if (args.size() < 4) {
                    context.printLine("modem: message required");
                    context.setExitCode(1);
                    return;
                }
            } catch (NumberFormatException ex) {
                msgStart = 2;
            }
            String message = String.join(" ", args.subList(msgStart, args.size()));
            if (!modem.transmitService(service, reply, message)) {
                context.printLine("modem: service call failed (service offline, unreachable, or channel closed)");
                context.setExitCode(1);
                return;
            }
            context.printLine(sendResult("service", service, -1, reply, message));
            context.setExitCode(0);
            return;
        }
        if ("sendto".equals(op)) {
            if (args.size() < 3) {
                context.printLine("modem: usage: modem sendto <host> [channel] [replyChannel] <message>");
                context.setExitCode(1);
                return;
            }
            String destination = args.get(1);
            int channel = modem.defaultChannel();
            int reply = 0;
            int msgStart = 2;
            if (args.size() >= 4) {
                try {
                    channel = Integer.parseInt(args.get(2));
                    msgStart = 3;
                } catch (NumberFormatException ignored) {
                    // The omitted-channel form starts the message immediately after the host.
                }
                if (msgStart == 3 && args.size() >= 4) {
                    try {
                        reply = Integer.parseInt(args.get(3));
                        msgStart = 4;
                    } catch (NumberFormatException ignored) {
                        // The third argument is the first word of the message.
                    }
                }
            }
            if (args.size() <= msgStart) {
                context.printLine("modem: message required");
                context.setExitCode(1);
                return;
            }
            String message = String.join(" ", args.subList(msgStart, args.size()));
            if (!modem.transmitTo(destination, channel, reply, message)) {
                context.printLine("modem: named transmission failed (host offline, unreachable, or port closed)");
                context.setExitCode(1);
                return;
            }
            context.printLine(sendResult("directed", destination, channel, reply, message));
            context.setExitCode(0);
            return;
        }
        if ("send".equals(op) || "transmit".equals(op) || "tx".equals(op)) {
            if (args.size() < 2) {
                context.printLine("modem: usage: modem send [channel] [replyChannel] <message>");
                context.setExitCode(1);
                return;
            }
            int channel = modem.defaultChannel();
            int reply = 0;
            int msgStart = 1;
            if (args.size() >= 3) {
                try {
                    channel = Integer.parseInt(args.get(1));
                    msgStart = 2;
                } catch (NumberFormatException ignored) {
                    // The omitted-channel form starts the message after the command.
                }
                if (msgStart == 2 && args.size() >= 3) {
                    try {
                        reply = Integer.parseInt(args.get(2));
                        msgStart = 3;
                    } catch (NumberFormatException ignored) {
                        // The third argument is the first word of the message.
                    }
                }
            }
            if (args.size() <= msgStart) {
                context.printLine("modem: message required");
                context.setExitCode(1);
                return;
            }
            String message = String.join(" ", args.subList(msgStart, args.size()));
            if (!modem.transmit(channel, reply, message)) {
                context.printLine("modem: transmit failed (open a channel first)");
                context.setExitCode(1);
                return;
            }
            context.printLine(sendResult("broadcast", "*", channel, reply, message));
            context.setExitCode(0);
            return;
        }
        if ("recv".equals(op) || "receive".equals(op) || "rx".equals(op) || "read".equals(op)) {
            int max = 8;
            if (args.size() > 1) {
                try {
                    max = Integer.parseInt(args.get(1));
                } catch (NumberFormatException ex) {
                    context.printLine("modem: max must be an integer");
                    context.setExitCode(1);
                    return;
                }
            }
            List<String> msgs = modem.receive(max);
            if (msgs.isEmpty()) {
                context.printLine("(no messages)");
            } else {
                for (String m : msgs) {
                    context.printLine(m);
                }
            }
            context.setExitCode(0);
            return;
        }
        context.printLine("modem: usage: modem open|listen|close|unlisten|channels|hostname|network|dns|resolve|interfaces|topology|diagnostics|neighbors|route|ping|probe|delivery|hosts|service|sensor|scada|services|call|send|sendto|recv ...");
        context.setExitCode(1);
    }

    private static void fail(Context context, String message) {
        context.printLine(message);
        context.setExitCode(1);
    }

    /** Reports transport admission without implying recipient processing or acknowledgement. */
    private static String sendResult(String mode, String target, int port, int replyPort, String message) {
        String portField = port < 0 ? "" : " port=" + port;
        return "accepted mode=" + mode + " target=" + target + portField
                + " reply=" + replyPort + " bytes=" + message.getBytes(StandardCharsets.UTF_8).length;
    }
}
