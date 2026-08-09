package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.blockentity.WirelessDisplayLinkBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/** Shell configuration for wireless display-link endpoints adjacent to a computer. */
final class DisplayLinkShellCommandModule implements ShellCommandModule {
    @Override public void register(Registrar registrar) { registrar.register("displaylink", this::displayLink, "display-link", "dlink"); }

    private void displayLink(Context context, List<String> arguments) {
        if (!(context.worldHost() instanceof ShellComputer computer)) {
            fail(context, "displaylink: this terminal has no world host");
            return;
        }
        List<WirelessDisplayLinkBlockEntity> links = adjacent(computer);
        if (links.isEmpty()) { fail(context, "displaylink: no adjacent wireless display link"); return; }
        String operation = arguments.isEmpty() ? "status" : arguments.get(0).toLowerCase(java.util.Locale.ROOT);
        switch (operation) {
            case "status", "list" -> {
                for (WirelessDisplayLinkBlockEntity link : links) context.printLine(link.status());
                context.setExitCode(0);
            }
            case "source", "sink" -> {
                if (arguments.size() != 2) { fail(context, "displaylink: usage: displaylink source|sink <channel>"); return; }
                for (WirelessDisplayLinkBlockEntity link : links) {
                    if ("source".equals(operation)) link.configureSource(arguments.get(1));
                    else link.configureSink(arguments.get(1));
                }
                context.printLine("displaylink: configured " + operation + " channel " + arguments.get(1));
                context.setExitCode(0);
            }
            case "pair" -> {
                String channel = arguments.size() > 1 ? arguments.get(1) : null;
                if (channel == null || channel.isBlank()) {
                    links.get(0).armPair(context.callerContext() == null
                            ? java.util.UUID.nameUUIDFromBytes("terminalcraft:displaylink".getBytes())
                            : context.callerContext().principalId());
                } else {
                    links.get(0).armPair(context.callerContext() == null
                            ? java.util.UUID.nameUUIDFromBytes("terminalcraft:displaylink".getBytes())
                            : context.callerContext().principalId(), channel);
                }
                context.printLine("displaylink: source armed; sneak-right-click the receiver link");
                context.setExitCode(0);
            }
            default -> fail(context, "displaylink: usage: status | source <channel> | sink <channel> | pair [channel]");
        }
    }

    private static List<WirelessDisplayLinkBlockEntity> adjacent(ShellComputer computer) {
        List<WirelessDisplayLinkBlockEntity> result = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            BlockEntity entity = computer.getLevel().getBlockEntity(computer.getBlockPos().relative(direction));
            if (entity instanceof WirelessDisplayLinkBlockEntity link) result.add(link);
        }
        return List.copyOf(result);
    }

    private static void fail(Context context, String message) { context.printLine(message); context.setExitCode(1); }
}
