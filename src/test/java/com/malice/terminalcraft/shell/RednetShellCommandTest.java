package com.malice.terminalcraft.shell;

import java.util.List;

/** Headless coverage for RedNet hostname discovery and directed-send shell commands. */
public final class RednetShellCommandTest {
    private RednetShellCommandTest() {}

    public static void main(String[] args) {
        FakeHost host = new FakeHost();
        BashShell shell = new BashShell();
        shell.setHost(host);

        assertResult(shell.executeForResult("modem listen 42"), 0,
                List.of("listening 42"), "listen alias");
        require(host.openChannels.contains(42), "listen must open the requested channel");
        assertResult(shell.executeForResult("modem unlisten 42"), 0,
                List.of("unlistened 42"), "unlisten alias");
        require(!host.openChannels.contains(42), "unlisten must close the requested channel");

        assertResult(shell.executeForResult("modem hostname"), 0, List.of("(unregistered)"), "initial hostname");
        assertResult(shell.executeForResult("modem hostname Factory-01"), 0,
                List.of("hostname factory-01"), "hostname registration");
        assertResult(shell.executeForResult("modem network"), 0,
                List.of("(automatic)"), "initial network assignment");
        assertResult(shell.executeForResult("modem network Factory-LAN"), 0,
                List.of("network factory-lan"), "logical network assignment");
        assertResult(shell.executeForResult("modem network invalid.name"), 1,
                List.of("modem: invalid network name"), "invalid logical network assignment");
        assertResult(shell.executeForResult("modem interfaces"), 0,
                List.of("address=rednet:test transport=wired dimension=minecraft:overworld position=1,2,3 ports=80,81"),
                "interface diagnostics");
        assertResult(shell.executeForResult("modem topology"), 0,
                List.of("topology transport=wired attachments=1",
                        "subnet=1 id=minecraft:overworld@2,2,3 nodes=3 modems=2 truncated=false",
                        "cache revision=4 entries=2 computations=7 hits=9",
                        "index revision=6 nodes=5 edges=8 refreshed=125 truncated=false"),
                "physical topology diagnostics");
        assertResult(shell.executeForResult("modem diagnostics"), 0,
                List.of("runtime subscriptions=3 hosts=2 services=1 local_pending=4",
                        "queues application=1/4 control=1/1 aggregate=5/640 tracked=2",
                        "traffic tick=120 messages=8 bytes=256 senders=2",
                        "deliveries retained=3 pending=0 attempting=0 accepted=1 acknowledged=1 rejected=0 timed_out=1",
                        "rejections malformed=1 rate_limited=2 application_full=3 control_full=4"),
                "aggregate packet diagnostics");
        assertResult(shell.executeForResult("modem topology extra"), 1,
                List.of("modem: usage: modem topology"), "topology argument validation");
        assertResult(shell.executeForResult("modem diagnostics extra"), 1,
                List.of("modem: usage: modem diagnostics"), "diagnostics argument validation");
        assertResult(shell.executeForResult("modem hosts"), 0,
                List.of("factory-01", "warehouse"), "sorted host listing");
        assertResult(shell.executeForResult("modem neighbors 1"), 0,
                List.of("address=rednet:warehouse transport=wired position=5,2,3 router_hops=1 ports=80,81"),
                "bounded neighbor diagnostics");
        assertResult(shell.executeForResult("modem neighbors 129"), 1,
                List.of("modem: max must be between 1 and 128"), "neighbor bound validation");
        assertResult(shell.executeForResult("modem route warehouse"), 0,
                List.of("destination=rednet:warehouse transport=wired router_hops=1",
                        "pass=1 ingress=2,2,3:west egress=4,2,3:east routers=3"),
                "route diagnostics");
        assertResult(shell.executeForResult("modem trace offline"), 1,
                List.of("modem: no route to offline"), "unreachable route diagnostics");
        assertResult(shell.executeForResult("modem ping warehouse"), 0,
                List.of("reachable target=rednet:warehouse transport=wired router_hops=1"), "ping reachability");
        assertResult(shell.executeForResult("modem ping offline"), 1,
                List.of("modem: no response from offline"), "unreachable ping");
        String deliveryId = "123e4567-e89b-12d3-a456-426614174000";
        assertResult(shell.executeForResult("modem probe warehouse 80 81 health check"), 0,
                List.of("id=" + deliveryId + " state=accepted attempts=1 retries=2 timeout=20"),
                "reliable probe admission");
        assertResult(shell.executeForResult("modem delivery " + deliveryId), 0,
                List.of("id=" + deliveryId + " state=acknowledged attempts=1 retries=2 timeout=20"),
                "reliable delivery status");
        assertResult(shell.executeForResult("modem delivery not-a-uuid"), 1,
                List.of("modem: invalid message id"), "delivery id validation");
        assertResult(shell.executeForResult("modem probe warehouse 70000 81 invalid"), 1,
                List.of("modem: port and reply channel must be between 0 and 65535"),
                "probe port validation");
        assertResult(shell.executeForResult("modem service add Status-API 80"), 0,
                List.of("service status-api 80"), "service registration");
        assertResult(shell.executeForResult("modem service list"), 0,
                List.of("status-api 80"), "local service listing");
        assertResult(shell.executeForResult("modem services"), 0,
                List.of(
                        "name=inventory address=rednet:inventory-host port=90 protocol=terminalcraft:rednet-service version=1 payload=text/plain",
                        "name=status-api address=rednet:status-host port=80 protocol=terminalcraft:status version=2 payload=application/json"),
                "reachable typed service listing");
        assertResult(shell.executeForResult("modem call inventory 81 count iron"), 0,
                List.of("accepted mode=service target=inventory reply=81 bytes=10"), "service call");
        require(host.service.equals("inventory") && host.replyPort == 81
                        && host.message.equals("count iron"),
                "service call must preserve service, reply port, and payload");
        assertResult(shell.executeForResult("modem sendto warehouse 80 81 status request"), 0,
                List.of("accepted mode=directed target=warehouse port=80 reply=81 bytes=14"), "directed send");
        require(host.destination.equals("warehouse") && host.port == 80 && host.replyPort == 81
                        && host.message.equals("status request"),
                "directed send must preserve destination, ports, and payload");
        host.openChannels.add(42);
        assertResult(shell.executeForResult("modem send 80 81 hello \u2603"), 0,
                List.of("accepted mode=broadcast target=* port=80 reply=81 bytes=9"),
                "broadcast send result uses UTF-8 byte length");
        assertResult(shell.executeForResult("modem sendto offline 80 hello"), 1,
                List.of("modem: named transmission failed (host offline, unreachable, or port closed)"),
                "unreachable host");
        assertResult(shell.executeForResult("modem hostname clear"), 0,
                List.of("hostname cleared"), "hostname removal");
        assertResult(shell.executeForResult("modem network clear"), 0,
                List.of("network automatic"), "logical network removal");

        System.out.println("RednetShellCommandTest: all tests passed");
    }

    private static void assertResult(ShellCommandResult actual, int exitCode, List<String> output, String message) {
        if (actual.exitCode() != exitCode || !actual.outputLines().equals(output)) {
            throw new AssertionError(message + ": expected exit=" + exitCode + " output=" + output
                    + ", actual exit=" + actual.exitCode() + " output=" + actual.outputLines());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeHost implements TerminalHost {
        private String hostname = "";
        private String networkName = "";
        private String destination = "";
        private int port;
        private int replyPort;
        private String message = "";
        private String service = "";
        private final java.util.Set<Integer> openChannels = new java.util.HashSet<>();

        @Override public boolean hasModem() { return true; }
        @Override public boolean modemOpen(int channel) { return openChannels.add(channel); }
        @Override public boolean modemClose(int channel) { return openChannels.remove(channel); }
        @Override public String modemHostname() { return hostname; }
        @Override public boolean modemSetHostname(String value) {
            hostname = value == null ? "" : value.trim().toLowerCase();
            return true;
        }
        @Override public String modemNetworkName() { return networkName; }
        @Override public boolean modemSetNetworkName(String value) {
            if (value == null || value.isBlank()) {
                networkName = "";
                return true;
            }
            java.util.Optional<String> normalized =
                    com.malice.terminalcraft.network.RednetNetworkName.normalize(value);
            if (normalized.isEmpty()) return false;
            networkName = normalized.get();
            return true;
        }
        @Override public List<String> modemHosts(int maximum) { return List.of("factory-01", "warehouse"); }
        @Override public List<String> modemInterfaces() {
            return List.of("address=rednet:test transport=wired dimension=minecraft:overworld position=1,2,3 ports=80,81");
        }
        @Override public List<String> modemTopologyDiagnostics() {
            return List.of("topology transport=wired attachments=1",
                    "subnet=1 id=minecraft:overworld@2,2,3 nodes=3 modems=2 truncated=false",
                    "cache revision=4 entries=2 computations=7 hits=9",
                    "index revision=6 nodes=5 edges=8 refreshed=125 truncated=false");
        }
        @Override public List<String> modemPacketDiagnostics() {
            return List.of("runtime subscriptions=3 hosts=2 services=1 local_pending=4",
                    "queues application=1/4 control=1/1 aggregate=5/640 tracked=2",
                    "traffic tick=120 messages=8 bytes=256 senders=2",
                    "deliveries retained=3 pending=0 attempting=0 accepted=1 acknowledged=1 rejected=0 timed_out=1",
                    "rejections malformed=1 rate_limited=2 application_full=3 control_full=4");
        }
        @Override public List<String> modemPing(String destination) {
            return "warehouse".equals(destination)
                    ? List.of("reachable target=rednet:warehouse transport=wired router_hops=1")
                    : List.of();
        }
        @Override public List<String> modemNeighbors(int maximum) {
            return maximum < 1 ? List.of() : List.of(
                    "address=rednet:warehouse transport=wired position=5,2,3 router_hops=1 ports=80,81");
        }
        @Override public List<String> modemRoute(String destination) {
            return "warehouse".equals(destination)
                    ? List.of("destination=rednet:warehouse transport=wired router_hops=1",
                            "pass=1 ingress=2,2,3:west egress=4,2,3:east routers=3")
                    : List.of();
        }
        @Override public boolean modemRegisterService(String name, int channel) {
            return "Status-API".equals(name) && channel == 80;
        }
        @Override public List<String> modemLocalServices() { return List.of("status-api 80"); }
        @Override public List<String> modemServices(int maximum) {
            return List.of(
                    "name=inventory address=rednet:inventory-host port=90 protocol=terminalcraft:rednet-service version=1 payload=text/plain",
                    "name=status-api address=rednet:status-host port=80 protocol=terminalcraft:status version=2 payload=application/json");
        }
        @Override public boolean modemTransmitService(String name, int reply, String payload) {
            if (!"inventory".equals(name)) return false;
            service = name;
            replyPort = reply;
            message = payload;
            return true;
        }
        @Override public boolean modemTransmit(int channel, int reply, String payload) {
            if (openChannels.isEmpty()) return false;
            port = channel;
            replyPort = reply;
            message = payload;
            return true;
        }
        @Override public String modemProbe(String host, int channel, int reply, String payload) {
            if (!"warehouse".equals(host) || channel != 80 || reply != 81) return "";
            return "id=123e4567-e89b-12d3-a456-426614174000 state=accepted attempts=1 retries=2 timeout=20";
        }
        @Override public String modemDelivery(String messageId) {
            return "123e4567-e89b-12d3-a456-426614174000".equals(messageId)
                    ? "id=" + messageId + " state=acknowledged attempts=1 retries=2 timeout=20" : "";
        }
        @Override public boolean modemTransmitTo(String host, int channel, int reply, String payload) {
            if (!"warehouse".equals(host)) return false;
            destination = host;
            port = channel;
            replyPort = reply;
            message = payload;
            return true;
        }
        @Override public int getRedstoneInput(String side) { return 0; }
        @Override public int getRedstoneOutput(String side) { return 0; }
        @Override public boolean setRedstoneOutput(String side, int power) { return false; }
        @Override public List<String> redstoneSides() { return List.of(); }
        @Override public List<String> listPeripherals() { return List.of("modem"); }
        @Override public String getLabel() { return "test"; }
        @Override public void setLabel(String label) {}
    }
}
