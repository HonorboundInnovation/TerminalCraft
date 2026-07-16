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
            context.printLine("modem hostname [name|clear]");
            context.printLine("modem network [name|clear]");
            context.printLine("modem interfaces");
            context.printLine("modem route <host>");
            context.printLine("modem ping <host>");
            context.printLine("modem probe <host> <port> <replyChannel> <message>");
            context.printLine("modem delivery <messageId>");
            context.printLine("modem neighbors [max]");
            context.printLine("modem hosts");
            context.printLine("modem service [list|add <name> <channel>|remove <name>]");
            context.printLine("modem services");
            context.printLine("modem call <service> [replyChannel] <message>");
            context.printLine("modem send <channel> [replyChannel] <message>");
            context.printLine("modem sendto <host> <channel> [replyChannel] <message>");
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
        if ("interfaces".equals(op) || "ifaces".equals(op)) {
            List<String> interfaces = modem.interfaces();
            if (interfaces.isEmpty()) context.printLine("(none)");
            else interfaces.forEach(context::printLine);
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
            if (args.size() < 4) {
                context.printLine("modem: usage: modem sendto <host> <channel> [replyChannel] <message>");
                context.setExitCode(1);
                return;
            }
            String destination = args.get(1);
            int channel;
            int reply = 0;
            int msgStart;
            try {
                channel = Integer.parseInt(args.get(2));
            } catch (NumberFormatException ex) {
                context.printLine("modem: channel must be an integer");
                context.setExitCode(1);
                return;
            }
            try {
                reply = Integer.parseInt(args.get(3));
                msgStart = 4;
                if (args.size() < 5) {
                    context.printLine("modem: message required");
                    context.setExitCode(1);
                    return;
                }
            } catch (NumberFormatException ex) {
                msgStart = 3;
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
            if (args.size() < 3) {
                context.printLine("modem: usage: modem send <channel> [replyChannel] <message>");
                context.setExitCode(1);
                return;
            }
            int channel;
            int reply = 0;
            int msgStart;
            try {
                channel = Integer.parseInt(args.get(1));
            } catch (NumberFormatException ex) {
                context.printLine("modem: channel must be an integer");
                context.setExitCode(1);
                return;
            }
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
        context.printLine("modem: usage: modem open|listen|close|unlisten|channels|hostname|network|interfaces|neighbors|route|ping|probe|delivery|hosts|service|services|call|send|sendto|recv ...");
        context.setExitCode(1);
    }

    /** Reports transport admission without implying recipient processing or acknowledgement. */
    private static String sendResult(String mode, String target, int port, int replyPort, String message) {
        String portField = port < 0 ? "" : " port=" + port;
        return "accepted mode=" + mode + " target=" + target + portField
                + " reply=" + replyPort + " bytes=" + message.getBytes(StandardCharsets.UTF_8).length;
    }
}
