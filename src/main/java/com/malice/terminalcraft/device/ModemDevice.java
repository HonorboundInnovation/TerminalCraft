package com.malice.terminalcraft.device;

import java.util.List;

/** Bounded rednet modem surface exposed independently from Minecraft types. */
public interface ModemDevice {
    int maxOpenChannels();
    int maxReceiveBatch();
    String label();
    void setLabel(String label);
    boolean wireless();
    int range();
    List<Integer> openChannels();
    int pendingCount();
    boolean open(int channel);
    boolean close(int channel);
    void closeAll();
    boolean transmit(int channel, int replyChannel, String message);
    List<String> receive(int limit);
}
