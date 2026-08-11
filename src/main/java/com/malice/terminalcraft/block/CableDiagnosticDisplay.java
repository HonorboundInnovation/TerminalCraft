package com.malice.terminalcraft.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/** Consistent, scan-friendly multi-line wrench output for every cable family. */
public final class CableDiagnosticDisplay {
    private CableDiagnosticDisplay() {}

    public static void show(Player player, List<String> lines) {
        if (player == null || lines == null || lines.isEmpty()) return;
        player.displayClientMessage(Component.literal("▣ " + lines.get(0))
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        for (int index = 1; index < lines.size(); index++) {
            player.displayClientMessage(Component.literal("  " + lines.get(index))
                    .withStyle(index == 1 ? ChatFormatting.WHITE : ChatFormatting.GRAY), false);
        }
    }

    public static String laneLabel(int lane) {
        return "L" + (SurfaceCableSupport.requireLane(lane) + 1);
    }
}
