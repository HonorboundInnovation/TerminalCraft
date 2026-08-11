package com.malice.terminalcraft.client;

import net.minecraft.client.Minecraft;

/** Client-only entry point kept behind a distribution guard in {@code GuideBookItem}. */
public final class GuideBookClient {
    private GuideBookClient() {}

    public static void open() {
        Minecraft.getInstance().setScreen(new GuideBookScreen());
    }
}
