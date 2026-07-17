package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Objects;

/**
 * Composed world-facing services available to a shell.
 *
 * <p>This is the extension boundary for future host features. New command modules should depend on
 * one focused service instead of adding another method to {@link TerminalHost}. The legacy adapter
 * keeps existing hosts and saves compatible while implementations migrate incrementally.</p>
 */
public record TerminalHostServices(
        Identity identity,
        Redstone redstone,
        Turtle turtle,
        Monitor monitor,
        Modem modem,
        BundledWire bundledWire,
        ServerJobs serverJobs,
        Disk disk,
        Devices devices) {

    public TerminalHostServices {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(redstone, "redstone");
        Objects.requireNonNull(turtle, "turtle");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(modem, "modem");
        Objects.requireNonNull(bundledWire, "bundledWire");
        Objects.requireNonNull(serverJobs, "serverJobs");
        Objects.requireNonNull(disk, "disk");
        Objects.requireNonNull(devices, "devices");
    }

    /** Compatibility adapter for current TerminalHost implementations. */
    public static TerminalHostServices legacy(TerminalHost host) {
        Objects.requireNonNull(host, "host");
        return new TerminalHostServices(
                new Identity() {
                    @Override public List<String> peripherals() { return host.listPeripherals(); }
                    @Override public String label() { return host.getLabel(); }
                    @Override public void setLabel(String label) { host.setLabel(label); }
                },
                new Redstone() {
                    @Override public int input(String side) { return host.getRedstoneInput(side); }
                    @Override public int output(String side) { return host.getRedstoneOutput(side); }
                    @Override public boolean setOutput(String side, int power) { return host.setRedstoneOutput(side, power); }
                    @Override public List<String> sides() { return host.redstoneSides(); }
                },
                new Turtle() {
                    @Override public boolean available() { return host.isTurtle(); }
                    @Override public boolean forward() { return host.turtleForward(); }
                    @Override public boolean back() { return host.turtleBack(); }
                    @Override public boolean up() { return host.turtleUp(); }
                    @Override public boolean down() { return host.turtleDown(); }
                    @Override public boolean turnLeft() { return host.turtleTurnLeft(); }
                    @Override public boolean turnRight() { return host.turtleTurnRight(); }
                    @Override public boolean dig(String side) { return host.turtleDig(side); }
                    @Override public boolean place(String side) { return host.turtlePlace(side); }
                    @Override public String inspect(String side) { return host.turtleInspect(side); }
                    @Override public String facing() { return host.turtleFacing(); }
                },
                new Monitor() {
                    @Override public boolean write(String side, String text) { return host.monitorWrite(side, text); }
                    @Override public boolean clear(String side) { return host.monitorClear(side); }
                    @Override public boolean setLine(String side, int row, String text) { return host.monitorSetLine(side, row, text); }
                    @Override public boolean setTitle(String side, String title) { return host.monitorSetTitle(side, title); }
                    @Override public boolean setPalette(String side, int foreground, int background) { return host.monitorSetPalette(side, foreground, background); }
                    @Override public List<String> lines(String side) { return host.monitorLines(side); }
                },
                new Modem() {
                    @Override public boolean available() { return host.hasModem(); }
                    @Override public boolean open(int channel) { return host.modemOpen(channel); }
                    @Override public boolean close(int channel) { return host.modemClose(channel); }
                    @Override public boolean isOpen(int channel) { return host.modemIsOpen(channel); }
                    @Override public List<Integer> openChannels() { return host.modemOpenChannels(); }
                    @Override public boolean transmit(int channel, int replyChannel, String message) { return host.modemTransmit(channel, replyChannel, message); }
                    @Override public String hostname() { return host.modemHostname(); }
                    @Override public boolean setHostname(String hostname) { return host.modemSetHostname(hostname); }
                    @Override public String networkName() { return host.modemNetworkName(); }
                    @Override public boolean setNetworkName(String networkName) { return host.modemSetNetworkName(networkName); }
                    @Override public List<String> hosts(int maximum) { return host.modemHosts(maximum); }
                    @Override public List<String> interfaces() { return host.modemInterfaces(); }
                    @Override public List<String> topologyDiagnostics() { return host.modemTopologyDiagnostics(); }
                    @Override public List<String> packetDiagnostics() { return host.modemPacketDiagnostics(); }
                    @Override public List<String> route(String destination) { return host.modemRoute(destination); }
                    @Override public List<String> ping(String destination) { return host.modemPing(destination); }
                    @Override public List<String> neighbors(int maximum) { return host.modemNeighbors(maximum); }
                    @Override public boolean transmitTo(String hostname, int port, int replyPort, String message) { return host.modemTransmitTo(hostname, port, replyPort, message); }
                    @Override public String probe(String hostname, int port, int replyPort, String message) { return host.modemProbe(hostname, port, replyPort, message); }
                    @Override public String delivery(String messageId) { return host.modemDelivery(messageId); }
                    @Override public boolean registerService(String service, int port) { return host.modemRegisterService(service, port); }
                    @Override public boolean unregisterService(String service) { return host.modemUnregisterService(service); }
                    @Override public List<String> localServices() { return host.modemLocalServices(); }
                    @Override public List<String> services(int maximum) { return host.modemServices(maximum); }
                    @Override public boolean transmitService(String service, int replyPort, String message) { return host.modemTransmitService(service, replyPort, message); }
                    @Override public List<String> receive(int maximum) { return host.modemReceive(maximum); }
                },
                new BundledWire() {
                    @Override public boolean available(String side) { return host.hasBundledCable(side); }
                    @Override public int signal(String side, int channel) { return host.bundledSignal(side, channel); }
                    @Override public int output(String side, int channel) { return host.bundledOutput(side, channel); }
                    @Override public boolean setOutput(String side, int channel, int strength) { return host.setBundledOutput(side, channel, strength); }
                },
                new ServerJobs() {
                    @Override public String submit(DeviceCallContext context, String command) { return host.serverSubmit(context, command); }
                    @Override public List<String> jobs(DeviceCallContext context) { return host.serverJobs(context); }
                    @Override public String job(DeviceCallContext context, String id) { return host.serverJob(context, id); }
                    @Override public boolean cancel(DeviceCallContext context, String id) { return host.serverCancel(context, id); }
                    @Override public int queuedJobs() { return host.serverQueuedJobs(); }
                    @Override public String schedulerDiagnostics() { return host.serverSchedulerDiagnostics(); }
                },
                new Disk() {
                    @Override public boolean hasMedia() { return host.hasDiskMedia(); }
                    @Override public String label() { return host.getDiskLabel(); }
                    @Override public boolean setLabel(String label) { return host.setDiskLabel(label); }
                    @Override public CompoundTag read() { return host.readDiskMedia(); }
                    @Override public boolean write(CompoundTag vfsTag) { return host.writeDiskMedia(vfsTag); }
                },
                host::deviceAccess);
    }

    public interface Identity {
        List<String> peripherals();
        String label();
        void setLabel(String label);
    }

    public interface Redstone {
        int input(String side);
        int output(String side);
        boolean setOutput(String side, int power);
        List<String> sides();
    }

    public interface Turtle {
        boolean available();
        boolean forward();
        boolean back();
        boolean up();
        boolean down();
        boolean turnLeft();
        boolean turnRight();
        boolean dig(String side);
        boolean place(String side);
        String inspect(String side);
        String facing();
    }

    public interface Monitor {
        boolean write(String side, String text);
        boolean clear(String side);
        boolean setLine(String side, int row, String text);
        boolean setTitle(String side, String title);
        boolean setPalette(String side, int foreground, int background);
        List<String> lines(String side);
    }

    public interface Modem {
        boolean available();
        boolean open(int channel);
        boolean close(int channel);
        boolean isOpen(int channel);
        List<Integer> openChannels();
        boolean transmit(int channel, int replyChannel, String message);
        String hostname();
        boolean setHostname(String hostname);
        String networkName();
        boolean setNetworkName(String networkName);
        List<String> hosts(int maximum);
        List<String> interfaces();
        List<String> topologyDiagnostics();
        List<String> packetDiagnostics();
        List<String> route(String destination);
        List<String> ping(String destination);
        List<String> neighbors(int maximum);
        boolean transmitTo(String hostname, int port, int replyPort, String message);
        String probe(String hostname, int port, int replyPort, String message);
        String delivery(String messageId);
        boolean registerService(String service, int port);
        boolean unregisterService(String service);
        List<String> localServices();
        List<String> services(int maximum);
        boolean transmitService(String service, int replyPort, String message);
        List<String> receive(int maximum);
    }

    public interface BundledWire {
        boolean available(String side);
        int signal(String side, int channel);
        int output(String side, int channel);
        boolean setOutput(String side, int channel, int strength);
    }

    public interface ServerJobs {
        String submit(DeviceCallContext context, String command);
        List<String> jobs(DeviceCallContext context);
        String job(DeviceCallContext context, String id);
        boolean cancel(DeviceCallContext context, String id);
        int queuedJobs();
        String schedulerDiagnostics();
    }

    public interface Disk {
        boolean hasMedia();
        String label();
        boolean setLabel(String label);
        CompoundTag read();
        boolean write(CompoundTag vfsTag);
    }

    @FunctionalInterface
    public interface Devices {
        DeviceAccess access(DeviceCallContext context);
    }
}
