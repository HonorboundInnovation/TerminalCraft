package com.malice.terminalcraft.device;

import java.util.List;

/** Bounded, scriptable text UI surface independent of Minecraft implementation types. */
public interface MonitorDevice {
    int maxLines();

    int maxLineLength();

    String title();

    void setTitle(String title);

    List<String> lines();

    void writeLine(String text);

    /** Replaces one zero-based screen row without scrolling the remaining rows. */
    void setLine(int row, String text);

    /** 24-bit RGB foreground color used by the world renderer. */
    int foregroundColor();

    /** 24-bit RGB background color used by the world renderer. */
    int backgroundColor();

    /** Changes the 24-bit RGB text palette atomically. */
    void setPalette(int foreground, int background);

    void clear();
}
