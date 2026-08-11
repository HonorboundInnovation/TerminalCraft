package com.malice.terminalcraft.shell;

import java.util.List;

/** Headless coverage for command and virtual-filesystem path completion. */
public final class BashShellCompletionTest {
    private BashShellCompletionTest() {}

    public static void main(String[] args) {
        BashShell shell = new BashShell();

        BashShell.CompletionResult commands = shell.complete("ec hi", 2);
        require(commands.candidates().contains("echo"), "builtin command completion");
        require("echo".equals(commands.commonPrefix()), "command common prefix");
        require("echo hi".equals(commands.apply("echo")), "command replacement preserves suffix");
        require(shell.complete("cont", 4).candidates().contains("control"),
                "graphical Control Center command completion");
        require(shell.complete("sca", 3).candidates().contains("scada"),
                "SCADA command completion");
        require(shell.complete("hm", 2).candidates().contains("hmi"),
                "advanced HMI command completion");

        ShellCommandResult generalHelp = shell.executeForResult("help");
        require(generalHelp.exitCode() == 0 && generalHelp.outputLines().stream()
                        .anyMatch(line -> line.contains("control / devmgr / setup")),
                "general terminal help lists Control Center commands");
        require(generalHelp.outputLines().stream()
                        .anyMatch(line -> line.contains("help control")),
                "general terminal help points to the focused key reference");
        ShellCommandResult controlHelp = shell.executeForResult("help control");
        require(controlHelp.exitCode() == 0
                        && controlHelp.outputLines().stream().anyMatch(line -> line.contains("F2 or N"))
                        && controlHelp.outputLines().stream().anyMatch(line -> line.contains("Enter")),
                "focused Control Center help documents DNS and activation keys");
        ShellCommandResult inlineHelp = shell.executeForResult("setup --help");
        require(inlineHelp.exitCode() == 0 && !shell.isControlCenterActive(),
                "setup alias exposes help without opening the full-screen program");
        ShellCommandResult hmiHelp = shell.executeForResult("help hmi");
        require(hmiHelp.exitCode() == 0
                        && hmiHelp.outputLines().stream().anyMatch(line -> line.contains("Shift+Arrow"))
                        && hmiHelp.outputLines().stream().anyMatch(line -> line.contains("Delete twice")),
                "focused advanced HMI help documents designer controls");

        BashShell.CompletionResult paths = shell.complete("cat /home/player/READ", 17);
        require(paths.candidates().equals(List.of("/home/player/README.txt")),
                "absolute path completion");

        BashShell.CompletionResult afterPipe = shell.complete("echo ok | he", 12);
        require(afterPipe.candidates().contains("head"), "command completion after pipe");

        List<String> usage = ShellUsageFormatter.format(
                "device: usage: device list | dns resolve <name|uuid> | events subscribe <source-uuid|*> | info <name|uuid>");
        require(usage.size() == 5, "long usage is expanded into a header and syntax lines");
        require("device: usage:".equals(usage.get(0)), "usage header is separated");
        require(usage.get(2).contains("device dns resolve <name|uuid>"),
                "nested alternatives remain intact");
        require(ShellUsageFormatter.format("auth: usage: auth status").equals(
                        List.of("auth: usage: auth status")),
                "short usage remains compatible");
        List<String> nestedUsage = ShellUsageFormatter.format(
                "modem: usage: modem dns [list|resolve <name|uuid>|self]");
        require(nestedUsage.equals(List.of("modem: usage:",
                        "  modem dns list", "  modem dns resolve <name|uuid>", "  modem dns self")),
                "bracketed alternatives are expanded without splitting value choices");

        ShellCommandResult templateList = shell.executeForResult("plc template list");
        require(templateList.exitCode() == 0 && templateList.outputLines().stream()
                        .anyMatch(line -> line.contains("motor-start-stop")),
                "templates can be listed from an ordinary terminal");
        ShellCommandResult templateShow = shell.executeForResult("plc template show motor-start-stop");
        require(templateShow.exitCode() == 0 && templateShow.outputLines().stream()
                        .anyMatch(line -> line.contains("IN START REDSTONE NORTH")),
                "templates can be inspected from an ordinary terminal");
        ShellCommandResult createTemplates = shell.executeForResult("plc template list create");
        require(createTemplates.exitCode() == 0 && createTemplates.outputLines().stream()
                        .anyMatch(line -> line.contains("create-clutch-safety")),
                "Create templates can be listed by category from an ordinary terminal");
        require(createTemplates.outputLines().stream().noneMatch(line -> line.contains("motor-start-stop")),
                "category listing excludes unrelated templates");
        ShellCommandResult templateCategories = shell.executeForResult("plc template categories");
        require(templateCategories.exitCode() == 0 && templateCategories.outputLines().stream()
                        .anyMatch(line -> line.contains("create (14)")),
                "template categories report the Create library size");
        require(templateCategories.outputLines().stream()
                        .anyMatch(line -> line.contains("securitycraft (10)")),
                "template categories report the SecurityCraft library size");
        ShellCommandResult templateLoad = shell.executeForResult("plc template load motor-start-stop");
        require(templateLoad.exitCode() == 1 && templateLoad.outputLines().stream()
                        .anyMatch(line -> line.contains("plc remote open")),
                "template load explains how to reach a PLC from an ordinary terminal");

        System.out.println("Bash shell completion tests: OK");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
