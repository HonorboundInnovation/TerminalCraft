package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * World bridge for a terminal shell. Implemented by terminal/turtle block entities so
 * sandboxed shell commands can read/write redstone, move turtles, talk on modems,
 * drive monitors, and mount floppy media without pure shell logic depending on Minecraft types.
 */
public interface TerminalHost {
    /**
     * Composed shell-facing services. Override this method in migrated hosts; the default adapter
     * preserves compatibility with existing implementations of the legacy methods below.
     */
    default TerminalHostServices services() { return TerminalHostServices.legacy(this); }

    int getRedstoneInput(String side);
    int getRedstoneOutput(String side);
    boolean setRedstoneOutput(String side, int power);
    List<String> redstoneSides();
    List<String> listPeripherals();
    String getLabel();
    void setLabel(String label);

    /** Server-side caller-bound device access, or null when unavailable. */
    default DeviceAccess deviceAccess(DeviceCallContext context) { return null; }

    default boolean isTurtle() { return false; }
    default boolean turtleForward() { return false; }
    default boolean turtleBack() { return false; }
    default boolean turtleUp() { return false; }
    default boolean turtleDown() { return false; }
    default boolean turtleTurnLeft() { return false; }
    default boolean turtleTurnRight() { return false; }
    default boolean turtleDig(String side) { return false; }
    default boolean turtlePlace(String side) { return false; }
    default String turtleInspect(String side) { return "not a turtle"; }
    default String turtleFacing() { return "unknown"; }

    default boolean monitorWrite(String side, String text) { return false; }
    default boolean monitorClear(String side) { return false; }
    default boolean monitorSetLine(String side, int row, String text) { return false; }
    default boolean monitorSetTitle(String side, String title) { return false; }
    default boolean monitorSetPalette(String side, int foreground, int background) { return false; }
    default List<String> monitorLines(String side) { return List.of(); }

    default boolean modemOpen(int channel) { return false; }
    default boolean modemClose(int channel) { return false; }
    default boolean modemIsOpen(int channel) { return false; }
    default List<Integer> modemOpenChannels() { return List.of(); }
    default boolean modemTransmit(int channel, int replyChannel, String message) { return false; }
    default String modemHostname() { return ""; }
    default boolean modemSetHostname(String hostname) { return false; }
    default String modemNetworkName() { return ""; }
    default boolean modemSetNetworkName(String networkName) { return false; }
    default List<String> modemHosts(int maximum) { return List.of(); }
    default List<String> modemInterfaces() { return List.of(); }
    default List<String> modemTopologyDiagnostics() { return List.of(); }
    default List<String> modemPacketDiagnostics() { return List.of(); }
    default List<String> modemRoute(String destination) { return List.of(); }
    default List<String> modemPing(String destination) { return List.of(); }
    default List<String> modemNeighbors(int maximum) { return List.of(); }
    default boolean modemTransmitTo(String hostname, int port, int replyPort, String message) { return false; }
    default String modemProbe(String hostname, int port, int replyPort, String message) { return ""; }
    default String modemDelivery(String messageId) { return ""; }
    default boolean modemRegisterService(String service, int port) { return false; }
    default boolean modemUnregisterService(String service) { return false; }
    default List<String> modemLocalServices() { return List.of(); }
    default List<String> modemServices(int maximum) { return List.of(); }
    default boolean modemTransmitService(String service, int replyPort, String message) { return false; }
    default List<String> modemReceive(int max) { return List.of(); }
    default boolean hasModem() { return false; }

    /** Returns whether a bundled-control cable is attached on the named side (or any side). */
    default boolean hasBundledCable(String side) { return false; }
    /** Returns the effective 0..15 signal, or -1 when the side/channel is unavailable. */
    default int bundledSignal(String side, int channel) { return -1; }
    /** Returns this cable segment's computer-driven 0..15 source, or -1 when unavailable. */
    default int bundledOutput(String side, int channel) { return -1; }
    /** Sets this cable segment's computer-driven source. */
    default boolean setBundledOutput(String side, int channel, int strength) { return false; }

    /** Submits a command to a local server rack and returns its persistent job UUID. */
    default String serverSubmit(DeviceCallContext context, String command) { return ""; }
    /** Returns bounded, human-readable job summaries from a local server rack. */
    default List<String> serverJobs() { return List.of(); }
    /** Returns only jobs owned by the exact typed caller. */
    default List<String> serverJobs(DeviceCallContext context) { return serverJobs(); }
    /** Returns one job summary, or an empty string when it is unavailable. */
    default String serverJob(String id) { return ""; }
    /** Returns one caller-owned job summary, or an empty string when unavailable or foreign. */
    default String serverJob(DeviceCallContext context, String id) { return serverJob(id); }
    default boolean serverCancel(DeviceCallContext context, String id) { return false; }
    default int serverQueuedJobs() { return -1; }
    /** Returns bounded aggregate scheduler diagnostics, or an empty string when unavailable. */
    default String serverSchedulerDiagnostics() { return ""; }

    default boolean hasDiskMedia() { return false; }
    default String getDiskLabel() { return ""; }
    default boolean setDiskLabel(String label) { return false; }
    default CompoundTag readDiskMedia() { return null; }
    default boolean writeDiskMedia(CompoundTag vfsTag) { return false; }
}
