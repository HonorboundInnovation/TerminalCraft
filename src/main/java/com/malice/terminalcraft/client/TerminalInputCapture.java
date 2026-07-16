package com.malice.terminalcraft.client;

import com.malice.terminalcraft.TerminalCraftMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Extra keyboard capture while {@link TerminalScreen} is open.
 *
 * <p>The primary fix lives in {@link TerminalScreen#keyPressed}: it always returns
 * {@code true} so Minecraft never forwards key events to {@code KeyMapping} handlers.
 * This class covers the remaining Forge interaction-key path that some mods still
 * use for opening menus (attack/use/pick-style bindings).
 */
@Mod.EventBusSubscriber(modid = TerminalCraftMod.MODID, value = Dist.CLIENT)
public final class TerminalInputCapture {
    private TerminalInputCapture() {}

    private static boolean terminalOpen() {
        return Minecraft.getInstance().screen instanceof TerminalScreen;
    }

    /**
     * Block attack / use / pick-block key-mapping triggers while the terminal is open.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractionKeyMapping(InputEvent.InteractionKeyMappingTriggered event) {
        if (terminalOpen()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
}
