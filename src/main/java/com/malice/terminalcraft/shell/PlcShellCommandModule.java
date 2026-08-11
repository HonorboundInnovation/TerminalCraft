package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.plc.PlcProgramTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkHooks;

import java.util.List;
import java.util.Locale;

/** Terminal command surface for the PLC's bounded editor, compiler, and run state. */
final class PlcShellCommandModule implements ShellCommandModule {
    @Override public void register(Registrar registrar) { registrar.register("plc", this::plc); }

    private void plc(Context context, List<String> arguments) {
        String requested = arguments.isEmpty() ? "status" : arguments.get(0).toLowerCase(Locale.ROOT);
        if (!(context.worldHost() instanceof ProgrammableLogicControllerBlockEntity controller)) {
            if ("template".equals(requested) || "templates".equals(requested)) {
                handleTemplate(context, null, arguments);
                return;
            }
            if ("remote".equals(requested)) {
                handleRemote(context, arguments);
                return;
            }
            fail(context, "plc: this terminal is not attached to a programmable logic controller");
            return;
        }
        String operation = requested;
        boolean templateLoad = "template".equals(operation) && arguments.size() > 1
                && "load".equalsIgnoreCase(arguments.get(1));
        if ((requiresControl(operation) || templateLoad) && context.callerContext() != null
                && !controller.canControl(context.callerContext().principalId())) {
            fail(context, "plc: operator is not authorized to control this PLC");
            return;
        }
        switch (operation) {
            case "help" -> {
                context.printLine("plc status | show | watch | start | stop | reset | clear");
                context.printLine("plc load <file>       Load a program from the shell VFS/editor");
                context.printLine("plc set <program>     Replace program; use \\n for line breaks");
                context.printLine("plc append <line>     Append one instruction to the current program");
                context.printLine("plc force input|analog|output <name> <value|clear>");
                context.printLine("plc alarm|acknowledge | plc page <0|1|2|3|next|previous>");
                context.printLine("plc slot save|load|clear <0-3>");
                context.printLine("plc remote open <x> <y> <z>");
                context.printLine("plc template categories | list [category] | show|load <name>");
                context.setExitCode(0);
            }
            case "template", "templates" -> handleTemplate(context, controller, arguments);
            case "status" -> {
                context.printLine(controller.statusLine()
                        + " page=" + controller.dashboardPage()
                        + " alarm=" + controller.alarmLatched()
                        + " forced_inputs=" + controller.forcedInputs().size()
                        + " forced_analog=" + controller.forcedAnalogInputs().size()
                        + " forced_outputs=" + controller.forcedOutputs().size());
                context.setExitCode(0);
            }
            case "watch" -> {
                context.printLine("inputs=" + controller.dashboardSignals());
                context.printLine("timers=" + controller.dashboardTimerElapsed());
                context.printLine("counters=" + controller.dashboardCounterValues());
                context.printLine("latches=" + controller.dashboardLatchValues());
                context.printLine("analog=" + controller.dashboardAnalogValues());
                context.printLine("pid=" + controller.dashboardPidOutputs());
                context.printLine("trend=" + controller.dashboardTrend().keySet());
                context.printLine("forced_inputs=" + controller.forcedInputs());
                context.printLine("forced_analog=" + controller.forcedAnalogInputs());
                context.printLine("forced_outputs=" + controller.forcedOutputs());
                context.setExitCode(0);
            }
            case "show" -> {
                String source = controller.programSource();
                if (source.isEmpty()) context.printLine("(empty program)");
                else for (String line : source.split("\\n", -1)) context.printLine(line);
                context.setExitCode(0);
            }
            case "start", "run" -> {
                if (!controller.compileError().isEmpty()) fail(context, "plc: " + controller.compileError());
                else { controller.start(); context.printLine("plc: running"); context.setExitCode(0); }
            }
            case "stop", "halt" -> { controller.stop(); context.printLine("plc: stopped"); context.setExitCode(0); }
            case "reset" -> { controller.resetController(); context.printLine("plc: runtime reset"); context.setExitCode(0); }
            case "clear" -> { controller.clearProgram(); context.printLine("plc: program cleared"); context.setExitCode(0); }
            case "force" -> handleForce(context, controller, arguments);
            case "alarm", "alarms" -> {
                if (controller.faultHistory().isEmpty()) context.printLine("plc: no recorded faults");
                else for (String fault : controller.faultHistory()) context.printLine(fault);
                context.printLine("active=" + controller.alarmLatched());
                context.setExitCode(0);
            }
            case "ack", "acknowledge" -> {
                controller.acknowledgeAlarm();
                context.printLine("plc: alarm acknowledged when no active fault remains");
                context.setExitCode(0);
            }
            case "page" -> {
                if (arguments.size() != 2) { fail(context, "plc: usage: plc page <0|1|2|3|next|previous>"); return; }
                int page = switch (arguments.get(1).toLowerCase(Locale.ROOT)) {
                    case "next" -> controller.dashboardPage() + 1;
                    case "previous", "prev" -> controller.dashboardPage() - 1;
                    default -> parsePage(arguments.get(1));
                };
                controller.setDashboardPage(page);
                context.printLine("plc: dashboard page " + controller.dashboardPage());
                context.setExitCode(0);
            }
            case "slot" -> {
                if (arguments.size() != 3) { fail(context, "plc: usage: plc slot save|load|clear <0-3>"); return; }
                int slot;
                try { slot = Integer.parseInt(arguments.get(2)); }
                catch (NumberFormatException invalid) { fail(context, "plc: slot must be 0..3"); return; }
                boolean success = switch (arguments.get(1).toLowerCase(Locale.ROOT)) {
                    case "save" -> controller.saveProgramSlot(slot);
                    case "load", "restore" -> controller.loadProgramSlot(slot);
                    case "clear", "remove" -> controller.clearProgramSlot(slot);
                    default -> false;
                };
                if (!success) fail(context, "plc: slot operation failed or slot is empty");
                else { context.printLine("plc: slot " + slot + " updated"); context.setExitCode(0); }
            }
            case "remote" -> handleRemote(context, arguments);
            case "load" -> {
                if (arguments.size() != 2) { fail(context, "plc: usage: plc load <file>"); return; }
                String source = context.readFile(arguments.get(1));
                if (source == null) { fail(context, "plc: file not found: " + arguments.get(1)); return; }
                if (!controller.loadProgram(source)) fail(context, "plc: " + controller.compileError());
                else { context.printLine("plc: program loaded"); context.setExitCode(0); }
            }
            case "set" -> {
                if (arguments.size() < 2) { fail(context, "plc: usage: plc set <program>"); return; }
                String source = decodeEscapes(String.join(" ", arguments.subList(1, arguments.size())));
                if (!controller.loadProgram(source)) fail(context, "plc: " + controller.compileError());
                else { context.printLine("plc: program compiled and loaded"); context.setExitCode(0); }
            }
            case "append" -> {
                if (arguments.size() < 2) { fail(context, "plc: usage: plc append <instruction>"); return; }
                String current = controller.programSource();
                String line = decodeEscapes(String.join(" ", arguments.subList(1, arguments.size())));
                String source = current.isEmpty() ? line : current + "\n" + line;
                if (!controller.loadProgram(source)) fail(context, "plc: " + controller.compileError());
                else { context.printLine("plc: instruction appended"); context.setExitCode(0); }
            }
            default -> fail(context, "plc: unknown operation (try plc help)");
        }
    }

    private static void handleRemote(Context context, List<String> arguments) {
        if (arguments.size() != 5 || !"open".equalsIgnoreCase(arguments.get(1))) {
            fail(context, "plc: usage: plc remote open <x> <y> <z>");
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(arguments.get(2));
            y = Integer.parseInt(arguments.get(3));
            z = Integer.parseInt(arguments.get(4));
        } catch (NumberFormatException invalid) {
            fail(context, "plc: remote coordinates must be integers");
            return;
        }
        com.malice.terminalcraft.shell.TerminalHost host = context.worldHost();
        if (host == null || !(host.getLevel() instanceof ServerLevel level)
                || context.callerContext() == null) {
            fail(context, "plc: remote programmer requires a server terminal session");
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(context.callerContext().principalId());
        BlockPos target = new BlockPos(x, y, z);
        if (player == null || !level.hasChunkAt(target)
                || !(level.getBlockEntity(target) instanceof ProgrammableLogicControllerBlockEntity plc)) {
            fail(context, "plc: no PLC is loaded at that position");
            return;
        }
        if (!plc.canControl(player)) {
            fail(context, "plc: operator is not authorized to control that PLC");
            return;
        }
        NetworkHooks.openScreen(player, new MenuProvider() {
            @Override public Component getDisplayName() { return Component.translatable("block.terminalcraft.programmable_logic_controller"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player ignored) {
                return new com.malice.terminalcraft.menu.PlcProgrammingMenu(id, inventory, plc, true);
            }
        }, buffer -> buffer.writeBlockPos(target));
        context.printLine("plc: remote programmer opened for " + target.toShortString());
        context.setExitCode(0);
    }

    private static void handleTemplate(Context context, ProgrammableLogicControllerBlockEntity controller,
                                       List<String> arguments) {
        String action = arguments.size() < 2 ? "list" : arguments.get(1).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (arguments.size() > 3) {
                fail(context, "plc: usage: plc template list [category]");
                return;
            }
            String category = arguments.size() == 3 ? arguments.get(2).toLowerCase(Locale.ROOT) : "";
            List<PlcProgramTemplates.Template> templates = PlcProgramTemplates.byCategory(category);
            if (!category.isEmpty() && templates.isEmpty()) {
                fail(context, "plc: unknown template category '" + category
                        + "' (try plc template categories)");
                return;
            }
            context.printLine(category.isEmpty() ? "PLC program templates:"
                    : "PLC program templates [" + category + "]:");
            for (PlcProgramTemplates.Template template : templates) {
                context.printLine("  " + template.id() + " — " + template.title());
                context.printLine("      " + template.description());
            }
            context.setExitCode(0);
            return;
        }
        if ("categories".equals(action) && arguments.size() == 2) {
            context.printLine("PLC template categories:");
            for (String category : PlcProgramTemplates.categories()) {
                context.printLine("  " + category + " (" + PlcProgramTemplates.byCategory(category).size() + ")");
            }
            context.setExitCode(0);
            return;
        }
        if (("show".equals(action) || "load".equals(action)) && arguments.size() == 3) {
            PlcProgramTemplates.Template template = PlcProgramTemplates.find(arguments.get(2)).orElse(null);
            if (template == null) {
                fail(context, "plc: unknown template '" + arguments.get(2) + "' (try plc template list)");
                return;
            }
            if ("show".equals(action)) {
                context.printLine("# " + template.title() + " — " + template.description());
                for (String line : template.source().split("\\n", -1)) context.printLine(line);
                context.setExitCode(0);
                return;
            }
            if (controller == null) {
                fail(context, "plc: template load requires an attached PLC; use plc remote open <x> <y> <z> first");
                return;
            }
            if (!controller.loadProgram(template.source())) {
                fail(context, "plc: template failed to compile: " + controller.compileError());
                return;
            }
            context.printLine("plc: template loaded: " + template.id());
            context.setExitCode(0);
            return;
        }
        fail(context, "plc: usage: plc template categories | list [category] | show <name> | load <name>");
    }

    private static String decodeEscapes(String value) {
        return value.replace("\\n", "\n").replace("\\r", "\r");
    }

    private static boolean requiresControl(String operation) {
        return switch (operation) {
            case "start", "run", "stop", "halt", "reset", "clear", "force", "ack", "acknowledge", "slot", "set", "load", "append" -> true;
            default -> false;
        };
    }

    private static void handleForce(Context context, ProgrammableLogicControllerBlockEntity controller,
                                    List<String> arguments) {
        if (arguments.size() != 4) {
            fail(context, "plc: usage: plc force input|analog|output <name> <on|off|0-15|clear>");
            return;
        }
        String kind = arguments.get(1).toLowerCase(Locale.ROOT);
        String name = arguments.get(2);
        String value = arguments.get(3).toLowerCase(Locale.ROOT);
        boolean success;
        if ("input".equals(kind)) {
            Boolean forced = "clear".equals(value) ? null : switch (value) {
                case "on", "true", "1" -> true;
                case "off", "false", "0" -> false;
                default -> null;
            };
            if (!"clear".equals(value) && forced == null) { fail(context, "plc: input force must be on, off, or clear"); return; }
            success = controller.forceInput(name, forced);
        } else if ("analog".equals(kind)) {
            Integer forced = "clear".equals(value) ? null : parseStrength(value);
            if (!"clear".equals(value) && forced == null) { fail(context, "plc: analog force must be 0..15 or clear"); return; }
            success = controller.forceAnalogInput(name, forced);
        } else if ("output".equals(kind)) {
            Integer forced = "clear".equals(value) ? null : parseStrength(value);
            if (!"clear".equals(value) && forced == null) { fail(context, "plc: output force must be 0..15 or clear"); return; }
            success = controller.forceOutput(name, forced);
        } else {
            fail(context, "plc: force target must be input or output");
            return;
        }
        if (!success) fail(context, "plc: unknown binding: " + name);
        else { context.printLine("plc: force updated"); context.setExitCode(0); }
    }

    private static Integer parseStrength(String value) {
        try {
            int strength = Integer.parseInt(value);
            return strength >= 0 && strength <= 15 ? strength : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static int parsePage(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static void fail(Context context, String message) {
        context.printLine(message);
        context.setExitCode(1);
    }
}
