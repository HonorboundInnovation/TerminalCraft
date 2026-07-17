package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.device.DeviceShellCommand;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.IntegratedStorageShellCommand;
import com.malice.terminalcraft.device.StorageShellCommand;

import com.malice.terminalcraft.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bash-style shell runtime for TerminalCraft.
 * Supports builtins, variables, chaining (; && ||), redirection (> >> <),
 * pipes (|), control flow (if/for/while), scripts from VFS, and NBT persistence.
 */
public class BashShell implements ShellCommandModule.Context {
    private static final int MAX_SCRIPT_DEPTH = 8;
    private static final int MAX_LOOP_ITERATIONS = 256;
    private static final int MAX_CHAIN_STEPS = 128;

    private final VirtualFileSystem vfs = new VirtualFileSystem();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final Deque<String> commandHistory = new ArrayDeque<>();
    private final List<String> output = new ArrayList<>();
    private final ShellCommandRegistry commandRegistry = new ShellCommandRegistry();
    private List<String> activeResultOutput;
    private String cwd = "/home/player";
    private int lastExitCode = 0;
    private boolean booted = false;
    private int scriptDepth = 0;
    private TerminalHost host;
    private DeviceCallContext deviceCallContext = DeviceCallContext.readOnly("local-shell");
    private transient com.malice.terminalcraft.device.DeviceAccess cachedDeviceAccess;
    private transient DeviceCallContext cachedDeviceContext;
    private boolean diskMounted = false;
    private static final String DISK_MOUNT = "/disk";

    /** Interactive multi-line editor session (edit/nano). */
    private final ShellEditorState editor = new ShellEditorState();
    private static final int MAX_EDITOR_LINES = 512;
    private static final int MAX_EDITOR_LINE_LEN = 512;

    private final StringBuilder captureBuffer = new StringBuilder();
    private boolean capturing = false;
    private String pendingStdin = null;

    public BashShell() {
        env.put("HOME", "/home/player");
        env.put("USER", "player");
        env.put("SHELL", "/bin/bash");
        env.put("PWD", cwd);
        env.put("TERM", "terminalcraft");
        env.put("PATH", "/bin:/usr/bin");
        registerBuiltins();
        ensureBooted();
    }

    public synchronized void ensureBooted() {
        if (booted) {
            return;
        }
        booted = true;
        if (Config.showWelcomeBanner) {
            println("TerminalCraft bash 1.1.0 (Minecraft)");
            println("Type 'help' for available commands.");
            println("");
        }
        printPromptLine();
    }

    public synchronized List<String> getOutputLines() {
        return Collections.unmodifiableList(new ArrayList<>(output));
    }

    public synchronized String getCwd() {
        return cwd;
    }

    public synchronized int getLastExitCode() {
        return lastExitCode;
    }

    public synchronized List<String> getCommandHistory() {
        return List.copyOf(commandHistory);
    }

    public synchronized VirtualFileSystem getVfs() {
        return vfs;
    }

    public synchronized void setHost(TerminalHost host) {
        if (this.host != host) {
            cachedDeviceAccess = null;
            cachedDeviceContext = null;
        }
        this.host = host;
    }

    public synchronized TerminalHost getHost() {
        return host;
    }

    /** Installs an independently implemented command family without expanding BashShell. */
    public synchronized void installCommandModule(ShellCommandModule module) {
        commandRegistry.install(module, this);
    }

    @Override
    public synchronized TerminalHostServices hostServices() {
        return host == null ? null : host.services();
    }

    @Override
    public synchronized DeviceCallContext callerContext() {
        return deviceCallContext;
    }

    @Override
    public synchronized void printLine(String line) {
        println(line);
    }

    @Override
    public synchronized void setExitCode(int exitCode) {
        lastExitCode = exitCode;
    }

    /** Executes one interactive command with a server-authenticated device caller context. */
    public synchronized void execute(String rawLine, DeviceCallContext context) {
        DeviceCallContext previous = deviceCallContext;
        deviceCallContext = Objects.requireNonNull(context, "context");
        try {
            execute(rawLine);
        } finally {
            deviceCallContext = previous;
        }
    }

    /** Interactive entry point used by trusted local/test callers with read-only device access. */
    public synchronized void execute(String rawLine) {
        ensureBooted();
        String line = rawLine == null ? "" : rawLine;
        if (line.length() > Config.maxCommandLength) {
            line = line.substring(0, Config.maxCommandLength);
        }
        line = line.replace("\r", "");

        if (editor.active) {
            handleEditorInput(line);
            trimOutput();
            return;
        }

        if (!output.isEmpty() && output.get(output.size() - 1).endsWith("$ ")) {
            String display = line.contains("\n") ? line.split("\n", 2)[0] : line;
            output.set(output.size() - 1, prompt() + display);
        } else {
            println(prompt() + line.replace("\n", " "));
        }

        String trimmed = line.trim();
        if (!trimmed.isEmpty()) {
            commandHistory.addLast(trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed);
            while (commandHistory.size() > 50) {
                commandHistory.removeFirst();
            }
            runScriptText(trimmed);
        }
        printPromptLine();
        trimOutput();
    }

    /** True while the interactive text editor is open. */
    public synchronized boolean isEditorActive() {
        return editor.active;
    }

    /** Path currently being edited, or null. */
    public synchronized String getEditorPath() {
        return editor.path;
    }

    /** Whether the editor buffer has unsaved changes. */
    public synchronized boolean isEditorDirty() {
        return editor.dirty;
    }

    /** Complete editor buffer for the dedicated GUI. */
    public synchronized String getEditorText() {
        return String.join("\n", editor.lines);
    }

    /**
     * Replaces and saves the active editor buffer from the dedicated GUI. The shell remains
     * authoritative over paths, VFS limits, mounted-media synchronization, and persistence.
     */
    public synchronized boolean saveEditorFromGui(String content, boolean closeAfterSave) {
        if (!editor.active) {
            return false;
        }
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() > 64 * 1024) {
            println("edit: file too large to save (64KiB max)");
            lastExitCode = 1;
            return false;
        }
        String[] incoming = normalized.split("\n", -1);
        if (incoming.length > MAX_EDITOR_LINES) {
            println("edit: too many lines (" + MAX_EDITOR_LINES + " max)");
            lastExitCode = 1;
            return false;
        }
        for (String line : incoming) {
            if (line.length() > MAX_EDITOR_LINE_LEN) {
                println("edit: line too long (" + MAX_EDITOR_LINE_LEN + " characters max)");
                lastExitCode = 1;
                return false;
            }
        }
        editor.lines.clear();
        java.util.Collections.addAll(editor.lines, incoming);
        if (editor.lines.isEmpty()) editor.lines.add("");
        editor.cursor = Math.max(0, editor.lines.size() - 1);
        editor.dirty = true;
        boolean saved = saveEditor(null);
        if (saved && closeAfterSave) closeEditor(true);
        return saved;
    }

    /** Closes the dedicated GUI editor, optionally discarding unsaved client edits. */
    public synchronized boolean closeEditorFromGui() {
        if (!editor.active) return false;
        closeEditor(false);
        return true;
    }

    /** Status line for the GUI footer while editing. */
    public synchronized String getEditorStatusLine() {
        if (!editor.active) {
            return "";
        }
        String name = editor.path == null ? "(none)" : editor.path;
        return "EDIT " + name + (editor.dirty ? " *" : "")
                + "  L" + (editor.cursor + 1) + "/" + Math.max(1, editor.lines.size())
                + "  :w save  :q quit  :wq save+quit  :help";
    }

    /**
     * Run script text and return a structured snapshot of its exit status and emitted output.
     * Existing callers may continue to use {@link #runScriptText(String)} when only the exit code
     * is needed.
     */
    public synchronized ShellCommandResult executeForResult(String text) {
        List<String> previousCollector = activeResultOutput;
        List<String> emitted = new ArrayList<>();
        activeResultOutput = emitted;
        try {
            int exitCode = runScriptText(text);
            return new ShellCommandResult(exitCode, emitted);
        } finally {
            activeResultOutput = previousCollector;
        }
    }

    /** Runs a command for an authenticated caller and returns only its emitted output. */
    public synchronized ShellCommandResult executeForResult(String text, DeviceCallContext context) {
        DeviceCallContext previous = deviceCallContext;
        deviceCallContext = Objects.requireNonNull(context, "context");
        try {
            return executeForResult(text);
        } finally {
            deviceCallContext = previous;
        }
    }

    /** Run multi-line script text without mutating the prompt. */
    public synchronized int runScriptText(String text) {
        if (text == null || text.isBlank()) {
            lastExitCode = 0;
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String stripped = stripComment(raw);
            if (!stripped.isBlank()) {
                lines.add(stripped);
            }
        }
        return runLines(lines, 0, lines.size());
    }

    /**
     * Headless self-test for build verification and the `selftest` builtin.
     * @return number of failed assertions
     */
    public synchronized int runSelfTest() {
        int failures = 0;
        String home = cwd;
        try {
            runScriptText("mkdir -p /tmp/selftest && cd /tmp/selftest");
            failures += assertExit(0, "setup");

            runScriptText("echo hello > a.txt");
            failures += assertTrue("hello\n".equals(vfs.readFile("/tmp/selftest/a.txt")), "redirect write");

            runScriptText("echo world >> a.txt");
            failures += assertTrue("hello\nworld\n".equals(vfs.readFile("/tmp/selftest/a.txt")), "append redirect");

            runScriptText("cat < a.txt > b.txt");
            failures += assertTrue(
                    Objects.equals(vfs.readFile("/tmp/selftest/a.txt"), vfs.readFile("/tmp/selftest/b.txt")),
                    "stdin redirect");

            runScriptText("false || echo ok > out.txt");
            failures += assertTrue("ok\n".equals(vfs.readFile("/tmp/selftest/out.txt")), "or chain");

            runScriptText("true && echo yes > out.txt");
            failures += assertTrue("yes\n".equals(vfs.readFile("/tmp/selftest/out.txt")), "and chain");

            runScriptText("echo pipe-data > pipe2.txt");
            failures += assertTrue(
                    vfs.readFile("/tmp/selftest/pipe2.txt") != null
                            && vfs.readFile("/tmp/selftest/pipe2.txt").contains("pipe-data"),
                    "file write path");

            runScriptText("X=1; if [ \"$X\" = \"1\" ]; then echo pass > out.txt; else echo fail > out.txt; fi");
            failures += assertTrue("pass\n".equals(vfs.readFile("/tmp/selftest/out.txt")), "if then");

            runScriptText("rm -f loop.txt; for i in one two three; do echo $i >> loop.txt; done");
            String loop = vfs.readFile("/tmp/selftest/loop.txt");
            failures += assertTrue(loop != null && loop.contains("one") && loop.contains("three"), "for loop");

            runScriptText("write script.sh 'echo scripted > scripted.txt'");
            runScriptText("source script.sh");
            failures += assertTrue("scripted\n".equals(vfs.readFile("/tmp/selftest/scripted.txt")), "source script");

            runScriptText("echo alpha | cat > out.txt");
            failures += assertTrue("alpha\n".equals(vfs.readFile("/tmp/selftest/out.txt")), "pipe to cat");

            runScriptText("cd /tmp/selftest");
            failures += assertExit(0, "cd ok");

            // Disk mount helpers (hostless path still exercises VFS import/export)
            BashShell.VirtualFileSystem media = new BashShell.VirtualFileSystem();
            media.clearAll();
            media.mkdirs("/");
            media.mkdirs("/programs");
            media.writeFile("/programs/ping.sh", "#!/bin/bash\necho pong\n");
            vfs.unmountDisk(DISK_MOUNT);
            failures += assertTrue(vfs.mountDisk(DISK_MOUNT, media.save()), "vfs mountDisk");
            failures += assertTrue(vfs.isDirectory("/disk/programs"), "mounted dir");
            failures += assertTrue("#!/bin/bash\necho pong\n".equals(vfs.readFile("/disk/programs/ping.sh")), "mounted file");
            vfs.writeFile("/disk/programs/note.txt", "saved\n");
            CompoundTag exported = vfs.exportMount(DISK_MOUNT);
            failures += assertTrue(exported != null, "export mount");
            BashShell.VirtualFileSystem round = new BashShell.VirtualFileSystem();
            round.clearAll();
            round.load(exported);
            // export strips /disk prefix, so programs live at /programs on media
            failures += assertTrue(round.readFile("/programs/note.txt") != null
                    && round.readFile("/programs/note.txt").contains("saved"), "export note");
            vfs.unmountDisk(DISK_MOUNT);
            failures += assertTrue(!vfs.isDirectory("/disk"), "unmounted");

            // Interactive editor: create a .sh on a mounted floppy and persist it.
            BashShell.VirtualFileSystem media2 = new BashShell.VirtualFileSystem();
            media2.clearAll();
            media2.mkdirs("/");
            media2.mkdirs("/programs");
            failures += assertTrue(vfs.mountDisk(DISK_MOUNT, media2.save()), "editor mount");
            diskMounted = true;
            runScriptText("edit /disk/programs/player.sh");
            failures += assertTrue(editor.active, "editor opens");
            handleEditorInput("#!/bin/bash");
            handleEditorInput("echo hello-from-editor");
            handleEditorInput(":wq");
            failures += assertTrue(!editor.active, "editor closed");
            failures += assertTrue(vfs.readFile("/disk/programs/player.sh") != null
                    && vfs.readFile("/disk/programs/player.sh").contains("hello-from-editor"),
                    "editor wrote script");
            CompoundTag exported2 = vfs.exportMount(DISK_MOUNT);
            BashShell.VirtualFileSystem round2 = new BashShell.VirtualFileSystem();
            round2.clearAll();
            round2.load(exported2);
            failures += assertTrue(round2.readFile("/programs/player.sh") != null
                    && round2.readFile("/programs/player.sh").contains("hello-from-editor"),
                    "editor export to floppy");
            vfs.unmountDisk(DISK_MOUNT);
            diskMounted = false;
        } catch (RuntimeException ex) {
            println("selftest: exception: " + ex.getMessage());
            failures++;
        } finally {
            cwd = home;
            env.put("PWD", cwd);
        }
        lastExitCode = failures == 0 ? 0 : 1;
        return failures;
    }

    private int assertExit(int expected, String name) {
        if (lastExitCode != expected) {
            println("selftest FAIL: " + name + " exit=" + lastExitCode + " expected=" + expected);
            return 1;
        }
        return 0;
    }

    private int assertTrue(boolean cond, String name) {
        if (!cond) {
            println("selftest FAIL: " + name);
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Script / control-flow execution
    // ------------------------------------------------------------------

    private int runLines(List<String> lines, int start, int end) {
        int i = start;
        int steps = 0;
        while (i < end) {
            if (++steps > MAX_CHAIN_STEPS * 4) {
                println("bash: script too long / possible infinite loop");
                lastExitCode = 1;
                return lastExitCode;
            }
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                i++;
                continue;
            }

            String first = firstWord(line);
            if ("if".equals(first)) {
                i = execIf(lines, i, end);
                continue;
            }
            if ("for".equals(first)) {
                i = execFor(lines, i, end);
                continue;
            }
            if ("while".equals(first)) {
                i = execWhile(lines, i, end);
                continue;
            }
            if ("fi".equals(first) || "then".equals(first) || "else".equals(first)
                    || "do".equals(first) || "done".equals(first)) {
                println("bash: syntax error near unexpected token `" + first + "'");
                lastExitCode = 2;
                return lastExitCode;
            }

            runChainedLine(line);
            i++;
        }
        return lastExitCode;
    }

    private int execIf(List<String> lines, int start, int end) {
        String joined = joinRegion(lines, start, end, "fi");
        ParsedIf parsed = parseIf(joined);
        if (parsed == null) {
            println("bash: syntax error in if statement");
            lastExitCode = 2;
            return skipTo(lines, start, end, "fi") + 1;
        }
        runChainedLine(parsed.condition);
        boolean cond = lastExitCode == 0;
        if (cond) {
            runScriptText(parsed.thenBody);
        } else if (parsed.elseBody != null && !parsed.elseBody.isBlank()) {
            runScriptText(parsed.elseBody);
        } else {
            lastExitCode = 0;
        }
        return skipTo(lines, start, end, "fi") + 1;
    }

    private int execFor(List<String> lines, int start, int end) {
        String joined = joinRegion(lines, start, end, "done");
        ParsedFor parsed = parseFor(joined);
        if (parsed == null) {
            println("bash: syntax error in for statement");
            lastExitCode = 2;
            return skipTo(lines, start, end, "done") + 1;
        }
        int n = 0;
        for (String value : parsed.values) {
            if (++n > MAX_LOOP_ITERATIONS) {
                println("bash: for: iteration limit exceeded");
                lastExitCode = 1;
                break;
            }
            env.put(parsed.varName, value);
            runScriptText(parsed.body);
        }
        return skipTo(lines, start, end, "done") + 1;
    }

    private int execWhile(List<String> lines, int start, int end) {
        String joined = joinRegion(lines, start, end, "done");
        ParsedWhile parsed = parseWhile(joined);
        if (parsed == null) {
            println("bash: syntax error in while statement");
            lastExitCode = 2;
            return skipTo(lines, start, end, "done") + 1;
        }
        int n = 0;
        while (true) {
            if (++n > MAX_LOOP_ITERATIONS) {
                println("bash: while: iteration limit exceeded");
                lastExitCode = 1;
                break;
            }
            runChainedLine(parsed.condition);
            if (lastExitCode != 0) {
                lastExitCode = 0;
                break;
            }
            runScriptText(parsed.body);
        }
        return skipTo(lines, start, end, "done") + 1;
    }

    private String joinRegion(List<String> lines, int start, int end, String closer) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(lines.get(i).trim());
            if (closer.equals(firstWord(lines.get(i))) || containsKeyword(lines.get(i), closer)) {
                break;
            }
        }
        return sb.toString();
    }

    private int skipTo(List<String> lines, int start, int end, String closer) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            String fw = firstWord(lines.get(i));
            if ("if".equals(fw) || "for".equals(fw) || "while".equals(fw)) {
                depth++;
            }
            if (containsKeyword(lines.get(i), closer)) {
                depth--;
                if (depth <= 0) {
                    return i;
                }
            }
        }
        return Math.max(start, end - 1);
    }

    private static boolean containsKeyword(String line, String keyword) {
        return roughTokens(line).contains(keyword);
    }

    private static String firstWord(String line) {
        List<String> t = roughTokens(line);
        return t.isEmpty() ? "" : t.get(0);
    }

    private static List<String> roughTokens(String line) {
        String normalized = line.replace(";", " ; ").replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split(" "));
    }

    private static final class ParsedIf {
        final String condition;
        final String thenBody;
        final String elseBody;

        ParsedIf(String condition, String thenBody, String elseBody) {
            this.condition = condition;
            this.thenBody = thenBody;
            this.elseBody = elseBody;
        }
    }

    private static ParsedIf parseIf(String joined) {
        List<String> parts = splitTopLevel(joined, ";");
        if (parts.isEmpty()) {
            return null;
        }
        String first = parts.get(0).trim();
        if (!first.startsWith("if ") && !first.equals("if")) {
            return null;
        }
        String cond = first.startsWith("if ") ? first.substring(3).trim() : "";
        int idx = 1;
        while (idx < parts.size() && !"then".equals(parts.get(idx).trim())
                && !parts.get(idx).trim().startsWith("then ")) {
            cond = cond.isEmpty() ? parts.get(idx).trim() : cond + "; " + parts.get(idx).trim();
            idx++;
        }
        if (idx >= parts.size()) {
            return null;
        }
        String thenPart = parts.get(idx).trim();
        if (thenPart.equals("then")) {
            idx++;
        } else if (thenPart.startsWith("then ")) {
            parts = new ArrayList<>(parts);
            parts.set(idx, thenPart.substring(5).trim());
        } else {
            return null;
        }

        StringBuilder thenBody = new StringBuilder();
        StringBuilder elseBody = null;
        boolean inElse = false;
        for (; idx < parts.size(); idx++) {
            String p = parts.get(idx).trim();
            if (p.equals("fi")) {
                break;
            }
            if (p.equals("else")) {
                inElse = true;
                elseBody = new StringBuilder();
                continue;
            }
            if (p.startsWith("else ")) {
                inElse = true;
                elseBody = new StringBuilder(p.substring(5).trim());
                continue;
            }
            if (p.endsWith(" fi")) {
                p = p.substring(0, p.length() - 3).trim();
                if (!p.isEmpty()) {
                    if (inElse) {
                        if (elseBody.length() > 0) {
                            elseBody.append("; ");
                        }
                        elseBody.append(p);
                    } else {
                        if (thenBody.length() > 0) {
                            thenBody.append("; ");
                        }
                        thenBody.append(p);
                    }
                }
                break;
            }
            if (inElse) {
                if (elseBody.length() > 0) {
                    elseBody.append("; ");
                }
                elseBody.append(p);
            } else {
                if (thenBody.length() > 0) {
                    thenBody.append("; ");
                }
                thenBody.append(p);
            }
        }
        if (cond.isBlank()) {
            return null;
        }
        return new ParsedIf(cond, thenBody.toString(), elseBody == null ? null : elseBody.toString());
    }

    private static final class ParsedFor {
        final String varName;
        final List<String> values;
        final String body;

        ParsedFor(String varName, List<String> values, String body) {
            this.varName = varName;
            this.values = values;
            this.body = body;
        }
    }

    private static ParsedFor parseFor(String joined) {
        List<String> parts = splitTopLevel(joined, ";");
        if (parts.isEmpty()) {
            return null;
        }
        String head = parts.get(0).trim();
        if (!head.startsWith("for ")) {
            return null;
        }
        List<String> headTokens = tokenize(head);
        if (headTokens.size() < 4 || !"for".equals(headTokens.get(0)) || !"in".equals(headTokens.get(2))) {
            return null;
        }
        String varName = headTokens.get(1);
        List<String> values = new ArrayList<>(headTokens.subList(3, headTokens.size()));
        StringBuilder body = new StringBuilder();
        boolean inDo = false;
        for (int idx = 1; idx < parts.size(); idx++) {
            String p = parts.get(idx).trim();
            if (p.equals("do")) {
                inDo = true;
                continue;
            }
            if (p.startsWith("do ")) {
                inDo = true;
                body.append(p.substring(3).trim());
                continue;
            }
            if (p.equals("done") || p.endsWith(" done")) {
                if (p.endsWith(" done") && p.length() > 5) {
                    String before = p.substring(0, p.length() - 5).trim();
                    if (!before.isEmpty()) {
                        if (body.length() > 0) {
                            body.append("; ");
                        }
                        body.append(before);
                    }
                }
                break;
            }
            if (inDo) {
                if (body.length() > 0) {
                    body.append("; ");
                }
                body.append(p);
            }
        }
        if (!isValidName(varName)) {
            return null;
        }
        return new ParsedFor(varName, values, body.toString());
    }

    private static final class ParsedWhile {
        final String condition;
        final String body;

        ParsedWhile(String condition, String body) {
            this.condition = condition;
            this.body = body;
        }
    }

    private static ParsedWhile parseWhile(String joined) {
        List<String> parts = splitTopLevel(joined, ";");
        if (parts.isEmpty()) {
            return null;
        }
        String first = parts.get(0).trim();
        if (!first.startsWith("while ") && !first.equals("while")) {
            return null;
        }
        String cond = first.startsWith("while ") ? first.substring(6).trim() : "";
        int idx = 1;
        while (idx < parts.size() && !"do".equals(parts.get(idx).trim())
                && !parts.get(idx).trim().startsWith("do ")) {
            cond = cond.isEmpty() ? parts.get(idx).trim() : cond + "; " + parts.get(idx).trim();
            idx++;
        }
        if (idx >= parts.size()) {
            return null;
        }
        String doPart = parts.get(idx).trim();
        StringBuilder body = new StringBuilder();
        if (doPart.equals("do")) {
            idx++;
        } else if (doPart.startsWith("do ")) {
            body.append(doPart.substring(3).trim());
            idx++;
        } else {
            return null;
        }
        for (; idx < parts.size(); idx++) {
            String p = parts.get(idx).trim();
            if (p.equals("done")) {
                break;
            }
            if (p.endsWith(" done")) {
                String before = p.substring(0, p.length() - 5).trim();
                if (!before.isEmpty()) {
                    if (body.length() > 0) {
                        body.append("; ");
                    }
                    body.append(before);
                }
                break;
            }
            if (body.length() > 0) {
                body.append("; ");
            }
            body.append(p);
        }
        if (cond.isBlank()) {
            return null;
        }
        return new ParsedWhile(cond, body.toString());
    }

    // ------------------------------------------------------------------
    // Chaining / pipelines / redirection
    // ------------------------------------------------------------------

    private void runChainedLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            lastExitCode = 0;
            return;
        }

        // Split on top-level ';' but keep if/for/while blocks intact.
        List<String> sequential = splitStatementsPreservingBlocks(trimmed);
        int steps = 0;
        for (String seq : sequential) {
            if (++steps > MAX_CHAIN_STEPS) {
                println("bash: too many chained commands");
                lastExitCode = 1;
                return;
            }
            String unit = seq.trim();
            if (unit.isEmpty()) {
                continue;
            }
            String fw = firstWord(unit);
            if ("if".equals(fw)) {
                execIf(List.of(unit), 0, 1);
            } else if ("for".equals(fw)) {
                execFor(List.of(unit), 0, 1);
            } else if ("while".equals(fw)) {
                execWhile(List.of(unit), 0, 1);
            } else {
                runAndOrChain(unit);
            }
        }
    }

    /**
     * Split a line on top-level semicolons while treating if/fi, for/done, and
     * while/done regions as atomic units so control-flow one-liners survive.
     */
    private static List<String> splitStatementsPreservingBlocks(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        int blockDepth = 0;
        for (int i = 0; i < line.length(); ) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                cur.append(c);
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                cur.append(c);
                i++;
                continue;
            }
            if (!inSingle && !inDouble) {
                String open = matchOpenKeyword(line, i);
                if (open != null) {
                    blockDepth++;
                    cur.append(open);
                    i += open.length();
                    continue;
                }
                String close = matchCloseKeyword(line, i);
                if (close != null) {
                    cur.append(close);
                    i += close.length();
                    if (blockDepth > 0) {
                        blockDepth--;
                    }
                    continue;
                }
                if (c == ';' && blockDepth == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    i++;
                    continue;
                }
            }
            cur.append(c);
            i++;
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String matchOpenKeyword(String line, int i) {
        if (isKeywordAt(line, i, "if")) {
            return "if";
        }
        if (isKeywordAt(line, i, "for")) {
            return "for";
        }
        if (isKeywordAt(line, i, "while")) {
            return "while";
        }
        return null;
    }

    private static String matchCloseKeyword(String line, int i) {
        if (isKeywordAt(line, i, "fi")) {
            return "fi";
        }
        if (isKeywordAt(line, i, "done")) {
            return "done";
        }
        return null;
    }

    private static boolean isKeywordAt(String line, int i, String keyword) {
        if (i + keyword.length() > line.length()) {
            return false;
        }
        if (!line.regionMatches(i, keyword, 0, keyword.length())) {
            return false;
        }
        if (i > 0) {
            char prev = line.charAt(i - 1);
            if (Character.isLetterOrDigit(prev) || prev == '_') {
                return false;
            }
        }
        int j = i + keyword.length();
        if (j < line.length()) {
            char next = line.charAt(j);
            if (Character.isLetterOrDigit(next) || next == '_') {
                return false;
            }
        }
        return true;
    }

    private void runAndOrChain(String line) {
        if (line.isEmpty()) {
            lastExitCode = 0;
            return;
        }
        List<ChainPart> parts = splitAndOr(line);
        boolean runNext = true;
        for (ChainPart part : parts) {
            if (runNext) {
                runPipeline(part.command);
            }
            if ("&&".equals(part.nextOp)) {
                runNext = lastExitCode == 0;
            } else if ("||".equals(part.nextOp)) {
                runNext = lastExitCode != 0;
            } else {
                runNext = true;
            }
        }
    }

    private static final class ChainPart {
        final String command;
        final String nextOp;

        ChainPart(String command, String nextOp) {
            this.command = command;
            this.nextOp = nextOp;
        }
    }

    private static List<ChainPart> splitAndOr(String line) {
        List<ChainPart> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                cur.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                cur.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '&' && i + 1 < line.length() && line.charAt(i + 1) == '&') {
                out.add(new ChainPart(cur.toString().trim(), "&&"));
                cur.setLength(0);
                i++;
                continue;
            }
            if (!inSingle && !inDouble && c == '|' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                out.add(new ChainPart(cur.toString().trim(), "||"));
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(c);
        }
        out.add(new ChainPart(cur.toString().trim(), null));
        return out;
    }

    private void runPipeline(String line) {
        if (line == null || line.isBlank()) {
            lastExitCode = 0;
            return;
        }
        List<String> stages = splitTopLevel(line, "|");
        if (stages.size() == 1) {
            runSimpleCommand(stages.get(0), null, false);
            return;
        }
        String stdin = null;
        for (int i = 0; i < stages.size(); i++) {
            boolean last = i == stages.size() - 1;
            stdin = runSimpleCommand(stages.get(i).trim(), stdin, !last);
            if (stdin == null) {
                stdin = "";
            }
        }
    }

    /**
     * Execute one simple command with optional stdin and optional stdout capture.
     * @return captured stdout when captureStdout is true; otherwise null
     */
    private String runSimpleCommand(String line, String stdin, boolean captureStdout) {
        pendingStdin = stdin;
        capturing = captureStdout;
        captureBuffer.setLength(0);

        String expandedLine = expand(line == null ? "" : line.trim());
        if (expandedLine.isEmpty()) {
            lastExitCode = 0;
            capturing = false;
            pendingStdin = null;
            return captureStdout ? "" : null;
        }

        // NAME=value assignment
        int eq = expandedLine.indexOf('=');
        if (eq > 0 && !expandedLine.substring(0, eq).contains(" ")
                && !expandedLine.startsWith("export ")
                && !hasRedirectMeta(expandedLine)) {
            String name = expandedLine.substring(0, eq).trim();
            String value = expandedLine.substring(eq + 1).trim();
            if (isValidName(name)) {
                env.put(name, stripQuotes(value));
                lastExitCode = 0;
                capturing = false;
                pendingStdin = null;
                return captureStdout ? captureBuffer.toString() : null;
            }
        }

        if (expandedLine.startsWith("export ")) {
            String body = expandedLine.substring(7).trim();
            int e = body.indexOf('=');
            if (e > 0) {
                String name = body.substring(0, e).trim();
                String value = body.substring(e + 1).trim();
                if (isValidName(name)) {
                    env.put(name, stripQuotes(value));
                    lastExitCode = 0;
                    capturing = false;
                    pendingStdin = null;
                    return captureStdout ? captureBuffer.toString() : null;
                }
            }
            println("export: usage: export NAME=value");
            lastExitCode = 1;
            capturing = false;
            pendingStdin = null;
            return captureStdout ? captureBuffer.toString() : null;
        }

        Redirected redirected = extractRedirects(expandedLine);
        List<String> tokens = tokenize(redirected.command);
        if (tokens.isEmpty()) {
            lastExitCode = 0;
            capturing = false;
            pendingStdin = null;
            return captureStdout ? captureBuffer.toString() : null;
        }

        if (redirected.stdinFile != null) {
            String path = vfs.resolve(cwd, redirected.stdinFile);
            String content = vfs.readFile(path);
            if (content == null) {
                println("bash: " + redirected.stdinFile + ": No such file or directory");
                lastExitCode = 1;
                capturing = false;
                pendingStdin = null;
                return captureStdout ? captureBuffer.toString() : null;
            }
            pendingStdin = content;
        }

        boolean captureForRedirect = redirected.stdoutFile != null;
        if (captureForRedirect) {
            capturing = true;
        }

        dispatch(tokens.get(0), tokens.subList(1, tokens.size()));

        String captured = captureBuffer.toString();
        if (captureForRedirect) {
            // Ensure trailing newline for redirected text output when buffer has content
            if (!captured.isEmpty() && !captured.endsWith("\n")) {
                // println joins without final newline marker; add one for file fidelity
                // Actually println for multi-line already put newlines between parts only.
                // echo writes one line without trailing \n in captureBuffer unless we add it.
            }
            // Match typical shell: echo writes a newline
            // Our println into captureBuffer does not auto-append \n after last line.
            // Add final newline for redirected single-line commands.
            if (!captured.isEmpty() && !captured.endsWith("\n")) {
                captured = captured + "\n";
            }
            String path = vfs.resolve(cwd, redirected.stdoutFile);
            String toWrite = captured;
            if (redirected.append) {
                String existing = vfs.readFile(path);
                if (existing == null) {
                    existing = "";
                }
                toWrite = existing + captured;
            }
            if (!vfs.writeFile(path, toWrite)) {
                println("bash: cannot write to " + redirected.stdoutFile);
                lastExitCode = 1;
            }
        }

        capturing = false;
        pendingStdin = null;
        if (captureStdout) {
            if (!captured.isEmpty() && !captured.endsWith("\n")) {
                captured = captured + "\n";
            }
            return captured;
        }
        return null;
    }

    private static boolean hasRedirectMeta(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (!inSingle && !inDouble && (c == '>' || c == '<')) {
                return true;
            }
        }
        return false;
    }

    private static final class Redirected {
        final String command;
        final String stdoutFile;
        final String stdinFile;
        final boolean append;

        Redirected(String command, String stdoutFile, String stdinFile, boolean append) {
            this.command = command;
            this.stdoutFile = stdoutFile;
            this.stdinFile = stdinFile;
            this.append = append;
        }
    }

    private static Redirected extractRedirects(String line) {
        StringBuilder cmd = new StringBuilder();
        String stdout = null;
        String stdin = null;
        boolean append = false;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                cmd.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                cmd.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '>') {
                boolean app = false;
                if (i + 1 < line.length() && line.charAt(i + 1) == '>') {
                    app = true;
                    i++;
                }
                i = skipSpaces(line, i + 1);
                StringBuilder file = new StringBuilder();
                while (i < line.length()) {
                    char fc = line.charAt(i);
                    if (Character.isWhitespace(fc) || fc == '<' || fc == '>') {
                        break;
                    }
                    file.append(fc);
                    i++;
                }
                i--; // for-loop will increment
                stdout = stripQuotes(file.toString());
                append = app;
                continue;
            }
            if (!inSingle && !inDouble && c == '<') {
                i = skipSpaces(line, i + 1);
                StringBuilder file = new StringBuilder();
                while (i < line.length()) {
                    char fc = line.charAt(i);
                    if (Character.isWhitespace(fc) || fc == '<' || fc == '>') {
                        break;
                    }
                    file.append(fc);
                    i++;
                }
                i--;
                stdin = stripQuotes(file.toString());
                continue;
            }
            cmd.append(c);
        }
        return new Redirected(cmd.toString().trim(), stdout, stdin, append);
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------
    // Command dispatch
    // ------------------------------------------------------------------

    private void registerBuiltins() {
        commandRegistry.register("help", args -> cmdHelp());
        commandRegistry.register("echo", this::cmdEcho);
        commandRegistry.register("pwd", args -> {
            println(cwd);
            lastExitCode = 0;
        });
        commandRegistry.register("cd", this::cmdCd);
        commandRegistry.register("ls", this::cmdLs);
        commandRegistry.register("cat", this::cmdCat);
        commandRegistry.register("touch", this::cmdTouch);
        commandRegistry.register("mkdir", this::cmdMkdir);
        commandRegistry.register("rm", this::cmdRm);
        commandRegistry.register("clear", args -> {
            if (!capturing) {
                output.clear();
            }
            lastExitCode = 0;
        });
        commandRegistry.register("whoami", args -> {
            println(env.getOrDefault("USER", "player"));
            lastExitCode = 0;
        });
        commandRegistry.register("env", this::cmdEnv, "printenv");
        commandRegistry.register("history", args -> cmdHistory());
        commandRegistry.register("uname", args -> {
            println("TerminalCraft 1.1.0 Minecraft-Forge-1.20.1");
            lastExitCode = 0;
        });
        commandRegistry.register("date", args -> {
            println(java.time.LocalDateTime.now().toString());
            lastExitCode = 0;
        });
        commandRegistry.register("true", args -> lastExitCode = 0);
        commandRegistry.register("false", args -> lastExitCode = 1);
        commandRegistry.register("exit", args -> {
            println("logout");
            lastExitCode = 0;
        });
        commandRegistry.register("printf", this::cmdPrintf);
        commandRegistry.register("test", this::cmdTest, "[");
        commandRegistry.register("write", this::cmdWrite);
        commandRegistry.register("edit", this::cmdEdit, "nano");
        commandRegistry.register("source", this::cmdSource, ".");
        commandRegistry.register("bash", this::cmdBash, "sh");
        commandRegistry.register("head", this::cmdHead);
        commandRegistry.register("wc", this::cmdWc);
        commandRegistry.register("basename", this::cmdBasename);
        commandRegistry.register("dirname", this::cmdDirname);
        commandRegistry.register("sleep", args -> lastExitCode = 0);
        commandRegistry.register("selftest", args -> {
            int fail = runSelfTest();
            println(fail == 0 ? "selftest: OK" : "selftest: " + fail + " failure(s)");
            lastExitCode = fail == 0 ? 0 : 1;
        });
        commandRegistry.register("redstone", this::cmdRedstone, "rs");
        commandRegistry.register("wire", this::cmdWire, "bundled");
        commandRegistry.register("device", this::cmdDevice, "devices");
        commandRegistry.register("storage", this::cmdStorage, "inventory");
        commandRegistry.register("sophisticated",
                args -> cmdIntegratedStorage(IntegratedStorageShellCommand.Profile.SOPHISTICATED, args),
                "sstorage");
        commandRegistry.register("drawers",
                args -> cmdIntegratedStorage(IntegratedStorageShellCommand.Profile.STORAGE_DRAWERS, args),
                "drawer");
        commandRegistry.register("turtle", this::cmdTurtle, "tu");
        commandRegistry.register("monitor", this::cmdMonitor);
        commandRegistry.register("mount", this::cmdMount);
        commandRegistry.register("umount", this::cmdUmount, "unmount");
        commandRegistry.register("disk", this::cmdDisk);
        commandRegistry.install(new HostIdentityCommandModule(), this);
        commandRegistry.install(new OperationsCommandModule(), this);
        commandRegistry.install(new ModemCommandModule(), this);
    }

    private void dispatch(String cmd, List<String> args) {
        if (commandRegistry.dispatch(cmd, args)) {
            return;
        }
        if (!tryRunPathScript(cmd, args)) {
            println("bash: " + cmd + ": command not found");
            lastExitCode = 127;
        }
    }

    private boolean tryRunPathScript(String cmd, List<String> args) {
        List<String> candidates = new ArrayList<>();
        if (cmd.contains("/")) {
            candidates.add(vfs.resolve(cwd, cmd));
        } else {
            String path = env.getOrDefault("PATH", "/bin:/usr/bin");
            for (String dir : path.split(":")) {
                if (!dir.isEmpty()) {
                    candidates.add(vfs.resolve(dir, cmd));
                }
            }
            candidates.add(vfs.resolve(cwd, cmd));
        }
        for (String path : candidates) {
            String content = vfs.readFile(path);
            if (content != null) {
                return runScriptFile(path, content, args);
            }
        }
        return false;
    }

    private boolean runScriptFile(String path, String content, List<String> args) {
        if (scriptDepth >= MAX_SCRIPT_DEPTH) {
            println("bash: maximum script nesting depth exceeded");
            lastExitCode = 1;
            return true;
        }
        scriptDepth++;
        String old0 = env.get("0");
        Map<String, String> oldPos = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            oldPos.put(String.valueOf(i), env.get(String.valueOf(i)));
        }
        env.put("0", path);
        for (int i = 0; i < args.size() && i < 9; i++) {
            env.put(String.valueOf(i + 1), args.get(i));
        }
        try {
            String body = content;
            if (body.startsWith("#!")) {
                int nl = body.indexOf('\n');
                body = nl >= 0 ? body.substring(nl + 1) : "";
            }
            runScriptText(body);
        } finally {
            scriptDepth--;
            if (old0 == null) {
                env.remove("0");
            } else {
                env.put("0", old0);
            }
            for (int i = 1; i < 10; i++) {
                String k = String.valueOf(i);
                String v = oldPos.get(k);
                if (v == null) {
                    env.remove(k);
                } else {
                    env.put(k, v);
                }
            }
        }
        return true;
    }

    private void cmdHelp() {
        println("TerminalCraft bash builtins:");
        println("  help                 Show this help");
        println("  echo / printf        Print arguments");
        println("  pwd / cd / ls        Navigate filesystem");
        println("  cat / head / write   Read/write files");
        println("  edit / nano <file>   Interactive editor (save to VFS/floppy)");
        println("  touch / mkdir / rm   File operations");
        println("  env / history / clear");
        println("  test / [             Conditional tests");
        println("  source / . / bash    Run a script from VFS");
        println("  selftest             Run built-in shell tests");
        println("  redstone / rs        Read/write redstone (get|set|sides)");
        println("  wire / bundled       Read/write 16-channel bundled cable");
        println("  server / jobs        Submit/list/status/cancel server-rack jobs");
        println("  peripheral           List adjacent peripherals");
        println("  device               List/info/call unified devices");
        println("  storage / inventory  Query and mutate generic item storage");
        println("  sophisticated        Control adjacent Sophisticated Storage/backpacks");
        println("  drawers              Control adjacent Storage Drawers");
        println("  label [name]         Get/set computer label");
        println("  turtle / tu          Move/dig/place (forward|back|up|down|turn|inspect)");
        println("  monitor              Write/clear/read adjacent monitor");
        println("  modem / rednet       Open/close/send/receive channels");
        println("  mount / umount       Mount/unmount adjacent floppy at /disk");
        println("  disk                 Disk status / label [name] / sync");
        println("Operators:");
        println("  ; && || |  > >> <");
        println("  if/then/else/fi  for/do/done  while/do/done");
        println("  NAME=value   $NAME  ${NAME}  $?  $1..$9");
        lastExitCode = 0;
    }


    private void cmdMount(List<String> args) {
        if (host == null) {
            println("mount: no host / disk drive available");
            lastExitCode = 1;
            return;
        }
        if (diskMounted || vfs.isDirectory(DISK_MOUNT)) {
            println("mount: " + DISK_MOUNT + " already mounted (use umount first)");
            lastExitCode = 1;
            return;
        }
        if (!host.hasDiskMedia()) {
            println("mount: no floppy in adjacent disk drive");
            lastExitCode = 1;
            return;
        }
        CompoundTag media = host.readDiskMedia();
        if (media == null) {
            println("mount: failed to read floppy media");
            lastExitCode = 1;
            return;
        }
        if (!vfs.mountDisk(DISK_MOUNT, media)) {
            println("mount: could not import floppy filesystem");
            lastExitCode = 1;
            return;
        }
        diskMounted = true;
        String label = host.getDiskLabel();
        println("mounted '" + label + "' on " + DISK_MOUNT);
        lastExitCode = 0;
    }

    private void cmdUmount(List<String> args) {
        if (!diskMounted && !vfs.isDirectory(DISK_MOUNT)) {
            println("umount: " + DISK_MOUNT + " not mounted");
            lastExitCode = 1;
            return;
        }
        // Flush changes back to media when a host/drive is available
        if (host != null && host.hasDiskMedia()) {
            CompoundTag exported = vfs.exportMount(DISK_MOUNT);
            if (exported != null) {
                host.writeDiskMedia(exported);
            }
        }
        vfs.unmountDisk(DISK_MOUNT);
        diskMounted = false;
        // If cwd was under /disk, bounce home
        if (cwd.equals(DISK_MOUNT) || cwd.startsWith(DISK_MOUNT + "/")) {
            cwd = env.getOrDefault("HOME", "/home/player");
            env.put("PWD", cwd);
        }
        println("unmounted " + DISK_MOUNT);
        lastExitCode = 0;
    }

    private void cmdDisk(List<String> args) {
        if (args.isEmpty() || "status".equals(args.get(0)) || "info".equals(args.get(0))) {
            if (host == null) {
                println("disk: no host");
                lastExitCode = 1;
                return;
            }
            if (!host.hasDiskMedia()) {
                println("disk: no media (place a Disk Drive with a floppy adjacent)");
                lastExitCode = 1;
                return;
            }
            println("label: " + host.getDiskLabel());
            println("mounted: " + (diskMounted || vfs.isDirectory(DISK_MOUNT) ? DISK_MOUNT : "(not mounted)"));
            lastExitCode = 0;
            return;
        }
        String sub = args.get(0);
        if ("label".equals(sub)) {
            if (host == null || !host.hasDiskMedia()) {
                println("disk: no media");
                lastExitCode = 1;
                return;
            }
            if (args.size() == 1) {
                println(host.getDiskLabel());
                lastExitCode = 0;
                return;
            }
            String label = String.join(" ", args.subList(1, args.size()));
            if (!host.setDiskLabel(label)) {
                println("disk: failed to set label");
                lastExitCode = 1;
                return;
            }
            println("label set to " + host.getDiskLabel());
            lastExitCode = 0;
            return;
        }
        if ("sync".equals(sub)) {
            if (!diskMounted && !vfs.isDirectory(DISK_MOUNT)) {
                println("disk: nothing mounted");
                lastExitCode = 1;
                return;
            }
            if (host == null || !host.hasDiskMedia()) {
                println("disk: no media to sync");
                lastExitCode = 1;
                return;
            }
            CompoundTag exported = vfs.exportMount(DISK_MOUNT);
            if (exported == null || !host.writeDiskMedia(exported)) {
                println("disk: sync failed");
                lastExitCode = 1;
                return;
            }
            println("disk: synced " + DISK_MOUNT + " -> floppy");
            lastExitCode = 0;
            return;
        }
        println("disk: usage: disk [status|label [name]|sync]");
        lastExitCode = 1;
    }

    private void cmdEcho(List<String> args) {
        println(String.join(" ", args));
        lastExitCode = 0;
    }

    private void cmdPrintf(List<String> args) {
        if (args.isEmpty()) {
            lastExitCode = 0;
            return;
        }
        println(String.join(" ", args).replace("\\n", "\n").replace("\\t", "\t"));
        lastExitCode = 0;
    }

    private void cmdCd(List<String> args) {
        String target = args.isEmpty() ? env.getOrDefault("HOME", "/") : args.get(0);
        String resolved = vfs.resolve(cwd, target);
        if (!vfs.isDirectory(resolved)) {
            println("bash: cd: " + target + ": No such file or directory");
            lastExitCode = 1;
            return;
        }
        cwd = resolved;
        env.put("PWD", cwd);
        lastExitCode = 0;
    }

    private void cmdLs(List<String> args) {
        boolean longFmt = false;
        List<String> paths = new ArrayList<>();
        for (String a : args) {
            if ("-l".equals(a) || "-la".equals(a) || "-al".equals(a) || "-a".equals(a)) {
                longFmt = true;
            } else {
                paths.add(a);
            }
        }
        if (paths.isEmpty()) {
            paths.add(cwd);
        }
        for (String pathArg : paths) {
            String path = vfs.resolve(cwd, pathArg);
            List<String> names = vfs.list(path);
            if (names == null) {
                if (vfs.readFile(path) != null) {
                    println(pathArg);
                    lastExitCode = 0;
                    continue;
                }
                println("ls: cannot access '" + pathArg + "': No such file or directory");
                lastExitCode = 1;
                continue;
            }
            if (paths.size() > 1) {
                println(pathArg + ":");
            }
            if (longFmt) {
                for (String name : names) {
                    boolean dir = name.endsWith("/");
                    println((dir ? "d" : "-") + "rw-r--r-- 1 player player "
                            + String.format(Locale.ROOT, "%6d", dir ? 0 : estimateSize(path, name))
                            + " " + name);
                }
            } else if (!names.isEmpty()) {
                println(String.join("  ", names));
            }
            lastExitCode = 0;
        }
    }

    private int estimateSize(String parent, String name) {
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String full = parent.equals("/") ? "/" + clean : parent + "/" + clean;
        String content = vfs.readFile(full);
        return content == null ? 0 : content.length();
    }

    private void cmdCat(List<String> args) {
        if (args.isEmpty()) {
            if (pendingStdin != null) {
                // Preserve exact stdin including trailing newline semantics for pipes.
                String content = pendingStdin;
                if (content.endsWith("\n")) {
                    content = content.substring(0, content.length() - 1);
                }
                for (String line : content.split("\n", -1)) {
                    println(line);
                }
                lastExitCode = 0;
                return;
            }
            println("cat: missing file operand");
            lastExitCode = 1;
            return;
        }
        for (String arg : args) {
            String path = vfs.resolve(cwd, arg);
            String content = vfs.readFile(path);
            if (content == null) {
                println("cat: " + arg + ": No such file or directory");
                lastExitCode = 1;
                return;
            }
            String print = content;
            if (print.endsWith("\n")) {
                print = print.substring(0, print.length() - 1);
            }
            for (String line : print.split("\n", -1)) {
                println(line);
            }
        }
        lastExitCode = 0;
    }

    private void cmdHead(List<String> args) {
        int n = 10;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-n") && i + 1 < args.size()) {
                try {
                    n = Integer.parseInt(args.get(++i));
                } catch (NumberFormatException ex) {
                    println("head: invalid number");
                    lastExitCode = 1;
                    return;
                }
            } else if (a.startsWith("-") && a.length() > 1 && Character.isDigit(a.charAt(1))) {
                try {
                    n = Integer.parseInt(a.substring(1));
                } catch (NumberFormatException ex) {
                    println("head: invalid number");
                    lastExitCode = 1;
                    return;
                }
            } else {
                files.add(a);
            }
        }
        String content;
        if (files.isEmpty()) {
            content = pendingStdin == null ? "" : pendingStdin;
        } else {
            content = vfs.readFile(vfs.resolve(cwd, files.get(0)));
            if (content == null) {
                println("head: " + files.get(0) + ": No such file or directory");
                lastExitCode = 1;
                return;
            }
        }
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            // skip final empty from trailing newline as a blank print only if present mid-file
            if (i == lines.length - 1 && lines[i].isEmpty() && content.endsWith("\n")) {
                break;
            }
            println(lines[i]);
        }
        lastExitCode = 0;
    }

    private void cmdWc(List<String> args) {
        String content;
        String label = "";
        if (args.isEmpty()) {
            content = pendingStdin == null ? "" : pendingStdin;
        } else {
            label = " " + args.get(0);
            content = vfs.readFile(vfs.resolve(cwd, args.get(0)));
            if (content == null) {
                println("wc: " + args.get(0) + ": No such file or directory");
                lastExitCode = 1;
                return;
            }
        }
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        if (content.endsWith("\n") && lines > 0) {
            lines--; // wc counts newline-terminated lines more closely
            lines = Math.max(lines, content.isEmpty() ? 0 : 1);
            // simpler: count \n
            lines = 0;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    lines++;
                }
            }
        }
        int words = content.trim().isEmpty() ? 0 : content.trim().split("\\s+").length;
        int bytes = content.length();
        println(String.format(Locale.ROOT, "%d %d %d%s", lines, words, bytes, label));
        lastExitCode = 0;
    }

    private void cmdBasename(List<String> args) {
        if (args.isEmpty()) {
            lastExitCode = 1;
            return;
        }
        String p = args.get(0);
        int idx = p.lastIndexOf('/');
        println(idx >= 0 ? p.substring(idx + 1) : p);
        lastExitCode = 0;
    }

    private void cmdDirname(List<String> args) {
        if (args.isEmpty()) {
            lastExitCode = 1;
            return;
        }
        String p = args.get(0);
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            println(idx == 0 ? "/" : ".");
        } else {
            println(p.substring(0, idx));
        }
        lastExitCode = 0;
    }

    private void cmdEdit(List<String> args) {
        if (editor.active) {
            println("edit: already editing " + editor.path);
            lastExitCode = 1;
            return;
        }
        if (args.isEmpty()) {
            println("edit: usage: edit <file>");
            println("  Opens the graphical multiline script editor.");
            println("  Ctrl+S saves; Ctrl+Shift+S saves and closes.");
            println("  Use Save, Save & Close, or Discard buttons — no vi commands required.");
            println("  Tip: mount a floppy, then  edit /disk/programs/mine.sh");
            lastExitCode = 1;
            return;
        }
        String path = vfs.resolve(cwd, args.get(0));
        if (vfs.isDirectory(path)) {
            println("edit: " + args.get(0) + " is a directory");
            lastExitCode = 1;
            return;
        }
        openEditor(path);
        lastExitCode = 0;
    }

    private void openEditor(String path) {
        editor.active = true;
        editor.path = path;
        editor.lines.clear();
        editor.cursor = 0;
        editor.dirty = false;

        String existing = vfs.readFile(path);
        if (existing != null) {
            String normalized = existing.replace("\r\n", "\n").replace('\r', '\n');
            if (normalized.endsWith("\n") && normalized.length() > 0) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.isEmpty()) {
                editor.lines.add("");
            } else {
                for (String ln : normalized.split("\n", -1)) {
                    if (editor.lines.size() >= MAX_EDITOR_LINES) {
                        break;
                    }
                    if (ln.length() > MAX_EDITOR_LINE_LEN) {
                        ln = ln.substring(0, MAX_EDITOR_LINE_LEN);
                    }
                    editor.lines.add(ln);
                }
            }
        } else {
            // New file buffer
            editor.lines.add("");
            editor.dirty = true;
        }
        editor.cursor = Math.max(0, editor.lines.size() - 1);
        renderEditorBuffer(true);
    }

    private void handleEditorInput(String rawLine) {
        String line = rawLine == null ? "" : rawLine;
        if (line.length() > MAX_EDITOR_LINE_LEN) {
            line = line.substring(0, MAX_EDITOR_LINE_LEN);
        }

        // Colon-commands drive the editor (vim-lite / nano-ish hybrid).
        if (line.startsWith(":")) {
            runEditorCommand(line.trim());
            return;
        }

        if (editor.lines.size() >= MAX_EDITOR_LINES) {
            println("edit: line limit (" + MAX_EDITOR_LINES + ") reached — save with :w or quit with :q");
            printEditorPrompt();
            return;
        }

        // Append typed line to the buffer.
        if (editor.cursor >= editor.lines.size() - 1) {
            // Replace trailing empty seed line once the user starts typing,
            // then append subsequent lines.
            if (editor.lines.size() == 1 && editor.lines.get(0).isEmpty() && editor.cursor == 0) {
                editor.lines.set(0, line);
            } else {
                editor.lines.add(line);
                editor.cursor = editor.lines.size() - 1;
            }
        } else {
            editor.lines.add(editor.cursor + 1, line);
            editor.cursor++;
        }
        editor.dirty = true;

        // Echo the accepted line into scrollback so players can see what they typed.
        String num = String.format("%3d", editor.cursor + 1);
        println(num + "| " + line);
        printEditorPrompt();
    }

    private void runEditorCommand(String cmd) {
        String body = cmd.length() > 1 ? cmd.substring(1).trim() : "";
        if (body.isEmpty() || "help".equals(body) || "?".equals(body) || "h".equals(body)) {
            println("Editor commands:");
            println("  :w [path]   write buffer to file (default: current path)");
            println("  :q          quit editor (refuses if unsaved)");
            println("  :q!         quit without saving");
            println("  :wq / :x    write then quit");
            println("  :r          redisplay buffer");
            println("  :help       this help");
            println("Type normal text lines to append. Save scripts under /disk after mount.");
            printEditorPrompt();
            lastExitCode = 0;
            return;
        }

        if ("r".equals(body) || "redraw".equals(body) || "list".equals(body)) {
            renderEditorBuffer(false);
            lastExitCode = 0;
            return;
        }

        if ("q!".equals(body) || "quit!".equals(body)) {
            closeEditor(false);
            lastExitCode = 0;
            return;
        }

        if ("q".equals(body) || "quit".equals(body)) {
            if (editor.dirty) {
                println("edit: no write since last change (use :q! to force, or :wq to save)");
                printEditorPrompt();
                lastExitCode = 1;
                return;
            }
            closeEditor(false);
            lastExitCode = 0;
            return;
        }

        if ("wq".equals(body) || "x".equals(body) || body.startsWith("wq ") || body.startsWith("x ")) {
            String asPath = null;
            if (body.startsWith("wq ")) {
                asPath = body.substring(3).trim();
            } else if (body.startsWith("x ")) {
                asPath = body.substring(2).trim();
            }
            if (!saveEditor(asPath == null || asPath.isEmpty() ? null : asPath)) {
                printEditorPrompt();
                return;
            }
            closeEditor(true);
            lastExitCode = 0;
            return;
        }

        if ("w".equals(body) || body.startsWith("w ") || "write".equals(body) || body.startsWith("write ")) {
            String asPath = null;
            if (body.startsWith("w ")) {
                asPath = body.substring(2).trim();
            } else if (body.startsWith("write ")) {
                asPath = body.substring(6).trim();
            }
            saveEditor(asPath == null || asPath.isEmpty() ? null : asPath);
            printEditorPrompt();
            return;
        }

        println("edit: unknown command `" + cmd + "'  (try :help)");
        printEditorPrompt();
        lastExitCode = 1;
    }

    private boolean saveEditor(String maybePath) {
        String path = editor.path;
        if (maybePath != null && !maybePath.isBlank()) {
            path = vfs.resolve(cwd, maybePath);
        }
        if (path == null || path.isBlank() || "/".equals(path)) {
            println("edit: no file name");
            lastExitCode = 1;
            return false;
        }
        if (vfs.isDirectory(path)) {
            println("edit: " + path + " is a directory");
            lastExitCode = 1;
            return false;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < editor.lines.size(); i++) {
            sb.append(editor.lines.get(i));
            sb.append('\n');
        }
        String content = sb.toString();
        if (content.length() > 64 * 1024) {
            println("edit: file too large to save (64KiB max)");
            lastExitCode = 1;
            return false;
        }
        if (!vfs.writeFile(path, content)) {
            println("edit: cannot write '" + path + "'");
            lastExitCode = 1;
            return false;
        }

        editor.path = path;
        editor.dirty = false;

        // Auto-sync portable media so floppies keep player scripts after umount/eject.
        boolean synced = false;
        if (path.equals(DISK_MOUNT) || path.startsWith(DISK_MOUNT + "/")) {
            synced = syncMountedDiskSilent();
        }

        println("edit: wrote " + path + " (" + editor.lines.size() + " lines)"
                + (synced ? "  [disk synced]" : ""));
        lastExitCode = 0;
        return true;
    }

    /** Flush /disk mount back to the physical floppy NBT if present. */
    private boolean syncMountedDiskSilent() {
        if (!diskMounted && !vfs.isDirectory(DISK_MOUNT)) {
            return false;
        }
        if (host == null || !host.hasDiskMedia()) {
            return false;
        }
        CompoundTag exported = vfs.exportMount(DISK_MOUNT);
        return exported != null && host.writeDiskMedia(exported);
    }

    private void closeEditor(boolean afterSave) {
        String path = editor.path;
        editor.active = false;
        editor.path = null;
        editor.lines.clear();
        editor.cursor = 0;
        editor.dirty = false;
        if (afterSave) {
            println("edit: closed " + path);
        } else {
            println("edit: quit" + (path == null ? "" : " " + path));
        }
        printPromptLine();
    }

    private void renderEditorBuffer(boolean header) {
        if (header) {
            println("-- editor: " + editor.path + (editor.dirty ? " [new]" : "") + " --");
            println("Type text lines.  :w save  :wq save+quit  :q quit  :help");
            if (editor.path != null && (editor.path.equals(DISK_MOUNT) || editor.path.startsWith(DISK_MOUNT + "/"))) {
                println("Path is on mounted floppy; :w auto-syncs the disk.");
            }
        }
        int total = editor.lines.size();
        int start = 0;
        // Keep render bounded so huge files do not flood the CRT.
        if (total > 40) {
            start = Math.max(0, total - 40);
            println("... (" + start + " lines above)");
        }
        for (int i = start; i < total; i++) {
            String num = String.format("%3d", i + 1);
            println(num + "| " + editor.lines.get(i));
        }
        printEditorPrompt();
    }

    private void printEditorPrompt() {
        String name = editor.path == null ? "?" : editor.path;
        int slash = name.lastIndexOf('/');
        String shortName = slash >= 0 ? name.substring(slash + 1) : name;
        if (shortName.isEmpty()) {
            shortName = name;
        }
        String mark = editor.dirty ? "*" : "";
        println("[" + shortName + mark + "]> ");
    }

    private void cmdWrite(List<String> args) {
        if (args.size() < 2) {
            if (args.size() == 1 && pendingStdin != null) {
                String path = vfs.resolve(cwd, args.get(0));
                String data = pendingStdin.endsWith("\n") ? pendingStdin : pendingStdin + "\n";
                if (!vfs.writeFile(path, data)) {
                    println("write: cannot write '" + args.get(0) + "'");
                    lastExitCode = 1;
                    return;
                }
                lastExitCode = 0;
                return;
            }
            println("write: usage: write <file> <text...>");
            lastExitCode = 1;
            return;
        }
        String path = vfs.resolve(cwd, args.get(0));
        String text = String.join(" ", args.subList(1, args.size())).replace("\\n", "\n");
        if (!vfs.writeFile(path, text.endsWith("\n") ? text : text + "\n")) {
            println("write: cannot write '" + args.get(0) + "'");
            lastExitCode = 1;
            return;
        }
        lastExitCode = 0;
    }

    private void cmdTouch(List<String> args) {
        if (args.isEmpty()) {
            println("touch: missing file operand");
            lastExitCode = 1;
            return;
        }
        for (String arg : args) {
            if (!vfs.touch(vfs.resolve(cwd, arg))) {
                println("touch: cannot touch '" + arg + "'");
                lastExitCode = 1;
                return;
            }
        }
        lastExitCode = 0;
    }

    private void cmdMkdir(List<String> args) {
        boolean parents = false;
        List<String> dirs = new ArrayList<>();
        for (String a : args) {
            if ("-p".equals(a)) {
                parents = true;
            } else {
                dirs.add(a);
            }
        }
        if (dirs.isEmpty()) {
            println("mkdir: missing operand");
            lastExitCode = 1;
            return;
        }
        for (String arg : dirs) {
            String path = vfs.resolve(cwd, arg);
            boolean ok = parents ? vfs.mkdirs(path) : vfs.mkdir(path);
            if (!ok) {
                println("mkdir: cannot create directory '" + arg + "'");
                lastExitCode = 1;
                return;
            }
        }
        lastExitCode = 0;
    }

    private void cmdRm(List<String> args) {
        boolean recursive = false;
        boolean force = false;
        List<String> paths = new ArrayList<>();
        for (String a : args) {
            if ("-r".equals(a) || "-R".equals(a) || "-rf".equals(a) || "-fr".equals(a)) {
                recursive = true;
                if (a.contains("f")) {
                    force = true;
                }
            } else if ("-f".equals(a)) {
                force = true;
            } else {
                paths.add(a);
            }
        }
        if (paths.isEmpty()) {
            println("rm: missing operand");
            lastExitCode = 1;
            return;
        }
        for (String arg : paths) {
            String path = vfs.resolve(cwd, arg);
            boolean ok = recursive ? vfs.rmrf(path) : vfs.rm(path);
            if (!ok && !force) {
                println("rm: cannot remove '" + arg + "'");
                lastExitCode = 1;
                return;
            }
        }
        lastExitCode = 0;
    }

    private void cmdEnv(List<String> args) {
        if (!args.isEmpty()) {
            String key = args.get(0);
            println(env.getOrDefault(key, ""));
            lastExitCode = env.containsKey(key) ? 0 : 1;
            return;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            println(e.getKey() + "=" + e.getValue());
        }
        lastExitCode = 0;
    }

    private void cmdHistory() {
        int i = 1;
        for (String cmd : commandHistory) {
            println(String.format(Locale.ROOT, "%5d  %s", i++, cmd));
        }
        lastExitCode = 0;
    }

    private void cmdTest(List<String> args) {
        List<String> a = new ArrayList<>(args);
        if (!a.isEmpty() && "]".equals(a.get(a.size() - 1))) {
            a.remove(a.size() - 1);
        }
        boolean result;
        if (a.size() == 2 && "-n".equals(a.get(0))) {
            result = !a.get(1).isEmpty();
        } else if (a.size() == 2 && "-z".equals(a.get(0))) {
            result = a.get(1).isEmpty();
        } else if (a.size() == 2 && "-f".equals(a.get(0))) {
            result = vfs.readFile(vfs.resolve(cwd, a.get(1))) != null;
        } else if (a.size() == 2 && "-d".equals(a.get(0))) {
            result = vfs.isDirectory(vfs.resolve(cwd, a.get(1)));
        } else if (a.size() == 2 && "-e".equals(a.get(0))) {
            String p = vfs.resolve(cwd, a.get(1));
            result = vfs.isDirectory(p) || vfs.readFile(p) != null;
        } else if (a.size() == 3 && ("=".equals(a.get(1)) || "==".equals(a.get(1)))) {
            result = Objects.equals(a.get(0), a.get(2));
        } else if (a.size() == 3 && "!=".equals(a.get(1))) {
            result = !Objects.equals(a.get(0), a.get(2));
        } else if (a.size() == 1) {
            result = !a.get(0).isEmpty();
        } else {
            println("test: invalid arguments");
            lastExitCode = 2;
            return;
        }
        lastExitCode = result ? 0 : 1;
    }

    private void cmdWire(List<String> args) {
        if (host == null) {
            println("wire: no world host attached");
            lastExitCode = 1;
            return;
        }
        if (args.isEmpty() || "help".equalsIgnoreCase(args.get(0))) {
            println("wire get <side|any> <channel 0-15>");
            println("wire output <side|any> <channel 0-15>");
            println("wire set <side|any> <channel 0-15> <strength 0-15>");
            lastExitCode = 0;
            return;
        }
        String operation = args.get(0).toLowerCase(Locale.ROOT);
        int required = "set".equals(operation) ? 4 : 3;
        if (args.size() < required) {
            println("wire: usage: wire " + operation + " <side|any> <channel 0-15>"
                    + ("set".equals(operation) ? " <strength 0-15>" : ""));
            lastExitCode = 1;
            return;
        }
        if (!("get".equals(operation) || "input".equals(operation)
                || "output".equals(operation) || "set".equals(operation))) {
            println("wire: unknown operation '" + operation + "'");
            lastExitCode = 1;
            return;
        }
        String side = args.get(1);
        int channel;
        try {
            channel = Integer.parseInt(args.get(2));
        } catch (NumberFormatException invalid) {
            println("wire: channel must be an integer from 0 to 15");
            lastExitCode = 1;
            return;
        }
        if (channel < 0 || channel > 15) {
            println("wire: channel must be an integer from 0 to 15");
            lastExitCode = 1;
            return;
        }
        if (!host.hasBundledCable(side)) {
            println("wire: no bundled cable on side '" + side + "'");
            lastExitCode = 1;
            return;
        }
        if ("set".equals(operation)) {
            int strength;
            try {
                strength = Integer.parseInt(args.get(3));
            } catch (NumberFormatException invalid) {
                println("wire: strength must be an integer from 0 to 15");
                lastExitCode = 1;
                return;
            }
            if (strength < 0 || strength > 15 || !host.setBundledOutput(side, channel, strength)) {
                println("wire: strength must be an integer from 0 to 15");
                lastExitCode = 1;
                return;
            }
            lastExitCode = 0;
            return;
        }
        int value = "output".equals(operation)
                ? host.bundledOutput(side, channel) : host.bundledSignal(side, channel);
        if (value < 0) {
            println("wire: unable to read channel");
            lastExitCode = 1;
            return;
        }
        println(Integer.toString(value));
        lastExitCode = 0;
    }

    private void cmdRedstone(List<String> args) {
        if (host == null) {
            println("redstone: no world host attached");
            lastExitCode = 1;
            return;
        }
        if (args.isEmpty() || "sides".equals(args.get(0))) {
            println(String.join("  ", host.redstoneSides()));
            lastExitCode = 0;
            return;
        }
        String op = args.get(0);
        if ("get".equals(op) || "input".equals(op) || "in".equals(op)) {
            String side = args.size() > 1 ? args.get(1) : "all";
            int v = host.getRedstoneInput(side);
            if (v < 0) {
                println("redstone: invalid side '" + side + "'");
                lastExitCode = 1;
                return;
            }
            println(Integer.toString(v));
            lastExitCode = 0;
            return;
        }
        if ("output".equals(op) || "out".equals(op)) {
            String side = args.size() > 1 ? args.get(1) : "all";
            int v = host.getRedstoneOutput(side);
            if (v < 0) {
                println("redstone: invalid side '" + side + "'");
                lastExitCode = 1;
                return;
            }
            println(Integer.toString(v));
            lastExitCode = 0;
            return;
        }
        if ("set".equals(op)) {
            if (args.size() < 3) {
                println("redstone: usage: redstone set <side> <0-15>");
                lastExitCode = 1;
                return;
            }
            String side = args.get(1);
            int power;
            try {
                power = Integer.parseInt(args.get(2));
            } catch (NumberFormatException ex) {
                println("redstone: power must be an integer");
                lastExitCode = 1;
                return;
            }
            if (!host.setRedstoneOutput(side, power)) {
                println("redstone: invalid side '" + side + "'");
                lastExitCode = 1;
                return;
            }
            lastExitCode = 0;
            return;
        }
        // shorthand: redstone <side> [power]
        if (args.size() == 1) {
            int v = host.getRedstoneInput(args.get(0));
            if (v < 0) {
                println("redstone: usage: redstone get|set|output|sides ...");
                lastExitCode = 1;
                return;
            }
            println(Integer.toString(v));
            lastExitCode = 0;
            return;
        }
        if (args.size() == 2) {
            try {
                int power = Integer.parseInt(args.get(1));
                if (!host.setRedstoneOutput(args.get(0), power)) {
                    println("redstone: invalid side '" + args.get(0) + "'");
                    lastExitCode = 1;
                    return;
                }
                lastExitCode = 0;
                return;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        println("redstone: usage: redstone get|set|output|sides ...");
        lastExitCode = 1;
    }

    private com.malice.terminalcraft.device.DeviceAccess deviceAccess() {
        if (host == null) return null;
        if (cachedDeviceAccess == null || !deviceCallContext.equals(cachedDeviceContext)) {
            cachedDeviceAccess = host.deviceAccess(deviceCallContext);
            cachedDeviceContext = deviceCallContext;
        }
        return cachedDeviceAccess;
    }

    private void cmdDevice(List<String> args) {
        DeviceShellCommand.Outcome outcome = DeviceShellCommand.execute(deviceAccess(), args);
        for (String line : outcome.lines()) {
            println(line);
        }
        lastExitCode = outcome.exitCode();
    }

    private void cmdStorage(List<String> args) {
        StorageShellCommand.Outcome outcome = StorageShellCommand.execute(deviceAccess(), args);
        for (String line : outcome.lines()) {
            println(line);
        }
        lastExitCode = outcome.exitCode();
    }

    private void cmdIntegratedStorage(IntegratedStorageShellCommand.Profile profile,
                                      List<String> args) {
        IntegratedStorageShellCommand.Outcome outcome = IntegratedStorageShellCommand.execute(
                deviceAccess(), profile, args);
        for (String line : outcome.lines()) {
            println(line);
        }
        lastExitCode = outcome.exitCode();
    }

    private void cmdTurtle(List<String> args) {
        if (host == null) {
            println("turtle: no world host attached");
            lastExitCode = 1;
            return;
        }
        if (!host.isTurtle()) {
            println("turtle: this computer is not a turtle");
            lastExitCode = 1;
            return;
        }
        if (args.isEmpty() || "help".equals(args.get(0))) {
            println("turtle forward|back|up|down|left|right|turn left|turn right");
            println("turtle dig [side] | place [side] | inspect [side] | facing");
            lastExitCode = 0;
            return;
        }
        String op = args.get(0).toLowerCase(Locale.ROOT);
        boolean ok;
        switch (op) {
            case "forward", "fd", "f" -> ok = host.turtleForward();
            case "back", "bk", "b" -> ok = host.turtleBack();
            case "up", "u" -> ok = host.turtleUp();
            case "down", "d" -> ok = host.turtleDown();
            case "left", "l" -> ok = host.turtleTurnLeft();
            case "right", "r" -> ok = host.turtleTurnRight();
            case "turn" -> {
                if (args.size() < 2) {
                    println("turtle: usage: turtle turn left|right");
                    lastExitCode = 1;
                    return;
                }
                String dir = args.get(1).toLowerCase(Locale.ROOT);
                if ("left".equals(dir) || "l".equals(dir)) {
                    ok = host.turtleTurnLeft();
                } else if ("right".equals(dir) || "r".equals(dir)) {
                    ok = host.turtleTurnRight();
                } else {
                    println("turtle: turn direction must be left or right");
                    lastExitCode = 1;
                    return;
                }
            }
            case "dig" -> {
                String side = args.size() > 1 ? args.get(1) : "front";
                ok = host.turtleDig(side);
            }
            case "place" -> {
                String side = args.size() > 1 ? args.get(1) : "front";
                ok = host.turtlePlace(side);
            }
            case "inspect", "look" -> {
                String side = args.size() > 1 ? args.get(1) : "front";
                println(host.turtleInspect(side));
                lastExitCode = 0;
                return;
            }
            case "facing", "face" -> {
                println(host.turtleFacing());
                lastExitCode = 0;
                return;
            }
            default -> {
                println("turtle: unknown action '" + op + "'");
                lastExitCode = 1;
                return;
            }
        }
        if (!ok) {
            println("turtle: " + op + " failed");
            lastExitCode = 1;
        } else {
            lastExitCode = 0;
        }
    }

    private void cmdMonitor(List<String> args) {
        if (host == null) {
            println("monitor: no world host attached");
            lastExitCode = 1;
            return;
        }
        if (args.isEmpty() || "help".equals(args.get(0))) {
            println("monitor write [side] <text>");
            println("monitor clear [side]");
            println("monitor set [side] <row> <text>   # zero-based wall row");
            println("monitor title [side] <title>");
            println("monitor color [side] <foreground> <background>  # #RRGGBB or decimal");
            println("monitor read [side]");
            println("monitor size [side]          # detect connected wall geometry");
            println("monitor demo [side]          # adaptive test for any rectangular wall");
            lastExitCode = 0;
            return;
        }
        String op = args.get(0).toLowerCase(Locale.ROOT);
        if ("size".equals(op) || "dimensions".equals(op) || "geometry".equals(op)) {
            String side = args.size() > 1 ? args.get(1) : "any";
            if (args.size() > 2 || !isSideToken(side.toLowerCase(Locale.ROOT))) {
                println("monitor: usage: monitor size [side]");
                lastExitCode = 1;
                return;
            }
            int columns = host.monitorColumns(side);
            int rows = host.monitorRows(side);
            if (columns <= 0 || rows <= 0) {
                println("monitor: no monitor on side '" + side + "'");
                lastExitCode = 1;
                return;
            }
            println("columns=" + columns + " rows=" + rows + " tiles="
                    + (columns / 40) + "x" + (rows / 20));
            lastExitCode = 0;
            return;
        }
        if ("demo".equals(op) || "test".equals(op)) {
            String side = args.size() > 1 ? args.get(1) : "any";
            if (args.size() > 2 || !isSideToken(side.toLowerCase(Locale.ROOT))) {
                println("monitor: usage: monitor demo [side]");
                lastExitCode = 1;
                return;
            }
            renderAdaptiveMonitorDemo(side);
            return;
        }
        if ("clear".equals(op)) {
            String side = args.size() > 1 ? args.get(1) : "any";
            if (!host.monitorClear(side)) {
                println("monitor: no monitor on side '" + side + "'");
                lastExitCode = 1;
                return;
            }
            lastExitCode = 0;
            return;
        }
        if ("set".equals(op)) {
            int index = 1;
            String side = "any";
            if (index < args.size() && isSideToken(args.get(index).toLowerCase(Locale.ROOT))) side = args.get(index++);
            if (args.size() - index < 2) {
                println("monitor: usage: monitor set [side] <row> <text>"); lastExitCode = 1; return;
            }
            try {
                int row = Integer.parseInt(args.get(index++));
                if (!host.monitorSetLine(side, row, String.join(" ", args.subList(index, args.size())))) {
                    println("monitor: row outside screen or monitor not found"); lastExitCode = 1; return;
                }
                lastExitCode = 0; return;
            } catch (NumberFormatException exception) {
                println("monitor: row must be a zero-based integer"); lastExitCode = 1; return;
            }
        }
        if ("title".equals(op)) {
            int index = 1;
            String side = "any";
            if (index < args.size() && isSideToken(args.get(index).toLowerCase(Locale.ROOT))) side = args.get(index++);
            if (index >= args.size() || !host.monitorSetTitle(side, String.join(" ", args.subList(index, args.size())))) {
                println("monitor: usage: monitor title [side] <title>"); lastExitCode = 1; return;
            }
            lastExitCode = 0; return;
        }
        if ("color".equals(op) || "palette".equals(op)) {
            int index = 1;
            String side = "any";
            if (index < args.size() && isSideToken(args.get(index).toLowerCase(Locale.ROOT))) side = args.get(index++);
            if (args.size() - index != 2) {
                println("monitor: usage: monitor color [side] <foreground> <background>"); lastExitCode = 1; return;
            }
            try {
                int foreground = parseRgb(args.get(index));
                int background = parseRgb(args.get(index + 1));
                if (!host.monitorSetPalette(side, foreground, background)) {
                    println("monitor: no monitor found"); lastExitCode = 1; return;
                }
                lastExitCode = 0; return;
            } catch (IllegalArgumentException exception) {
                println("monitor: colors must be #RRGGBB, 0xRRGGBB, or decimal 0..16777215"); lastExitCode = 1; return;
            }
        }
        if ("read".equals(op) || "lines".equals(op) || "cat".equals(op)) {
            String side = args.size() > 1 ? args.get(1) : "any";
            List<String> lines = host.monitorLines(side);
            for (String line : lines) {
                println(line);
            }
            lastExitCode = 0;
            return;
        }
        if ("write".equals(op) || "print".equals(op) || "say".equals(op)) {
            if (args.size() < 2) {
                println("monitor: usage: monitor write [side] <text>");
                lastExitCode = 1;
                return;
            }
            String side = "any";
            int textStart = 1;
            String maybeSide = args.get(1).toLowerCase(Locale.ROOT);
            if (isSideToken(maybeSide) && args.size() >= 3) {
                side = maybeSide;
                textStart = 2;
            }
            String textArg = String.join(" ", args.subList(textStart, args.size()));
            if (!host.monitorWrite(side, textArg)) {
                println("monitor: no monitor found");
                lastExitCode = 1;
                return;
            }
            lastExitCode = 0;
            return;
        }
        String textArg = String.join(" ", args);
        if (!host.monitorWrite("any", textArg)) {
            println("monitor: no monitor found");
            lastExitCode = 1;
            return;
        }
        lastExitCode = 0;
    }

    /** Draws one labeled 40x20 test cell per physical tile using the detected wall geometry. */
    private void renderAdaptiveMonitorDemo(String side) {
        int columns = host.monitorColumns(side);
        int rows = host.monitorRows(side);
        if (columns <= 0 || rows <= 0 || columns % 40 != 0 || rows % 20 != 0) {
            println("monitor: no valid rectangular monitor wall on side '" + side + "'");
            lastExitCode = 1;
            return;
        }
        int tilesWide = columns / 40;
        int tilesHigh = rows / 20;
        if (!host.monitorClear(side)
                || !host.monitorSetTitle(side, "Monitor Wall " + tilesWide + "x" + tilesHigh)
                || !host.monitorSetPalette(side, 0x66ff99, 0x050a05)) {
            println("monitor: failed to initialize wall demo");
            lastExitCode = 1;
            return;
        }
        String symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int row = 0; row < rows; row++) {
            int tileRow = row / 20;
            int localRow = row % 20;
            StringBuilder line = new StringBuilder(columns);
            for (int tileColumn = 0; tileColumn < tilesWide; tileColumn++) {
                int tileIndex = tileRow * tilesWide + tileColumn;
                char symbol = symbols.charAt(tileIndex % symbols.length());
                String interior;
                if (localRow == 0 || localRow == 19) {
                    line.append('+').append("-".repeat(38)).append('+');
                    continue;
                } else if (localRow == 1) {
                    interior = " TILE " + (tileColumn + 1) + "," + (tileRow + 1)
                            + " OF " + tilesWide + "x" + tilesHigh + " [" + symbol + "]";
                } else if (localRow == 2) {
                    interior = " COL " + (tileColumn * 40) + "-" + (tileColumn * 40 + 39)
                            + " ROW " + (tileRow * 20) + "-" + (tileRow * 20 + 19);
                } else {
                    interior = String.valueOf(symbol).repeat(38);
                }
                if (interior.length() > 38) interior = interior.substring(0, 38);
                line.append('|').append(interior).append(" ".repeat(38 - interior.length())).append('|');
            }
            if (!host.monitorSetLine(side, row, line.toString())) {
                println("monitor: wall changed or rejected row " + row);
                lastExitCode = 1;
                return;
            }
        }
        println("Detected " + tilesWide + "x" + tilesHigh + " monitor wall ("
                + columns + "x" + rows + " characters).");
        println("Each physical tile should show one uniquely labeled bordered segment.");
        lastExitCode = 0;
    }

    private static int parseRgb(String value) {
        String raw = value.trim();
        int radix = 10;
        if (raw.startsWith("#")) { raw = raw.substring(1); radix = 16; }
        else if (raw.startsWith("0x") || raw.startsWith("0X")) { raw = raw.substring(2); radix = 16; }
        int color = Integer.parseInt(raw, radix);
        if (color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException("color outside RGB range");
        return color;
    }

    private static boolean isSideToken(String s) {
        return switch (s) {
            case "front", "back", "left", "right", "top", "bottom",
                 "north", "south", "east", "west", "up", "down", "any",
                 "forward", "behind" -> true;
            default -> false;
        };
    }


    private void cmdSource(List<String> args) {
        if (args.isEmpty()) {
            println("source: filename argument required");
            lastExitCode = 1;
            return;
        }
        String path = vfs.resolve(cwd, args.get(0));
        String content = vfs.readFile(path);
        if (content == null) {
            println("source: " + args.get(0) + ": No such file or directory");
            lastExitCode = 1;
            return;
        }
        List<String> rest = args.size() > 1 ? args.subList(1, args.size()) : List.of();
        runScriptFile(path, content, rest);
    }

    private void cmdBash(List<String> args) {
        if (args.isEmpty()) {
            println("bash: interactive subshell not available; pass a script file");
            lastExitCode = 1;
            return;
        }
        String path = vfs.resolve(cwd, args.get(0));
        String content = vfs.readFile(path);
        if (content == null) {
            println("bash: " + args.get(0) + ": No such file or directory");
            lastExitCode = 1;
            return;
        }
        List<String> rest = args.size() > 1 ? args.subList(1, args.size()) : List.of();
        runScriptFile(path, content, rest);
    }

    // ------------------------------------------------------------------
    // Expansion / tokenization
    // ------------------------------------------------------------------

    private String expand(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '$' && i + 1 < input.length()) {
                char n = input.charAt(i + 1);
                if (n == '{') {
                    int end = input.indexOf('}', i + 2);
                    if (end > i) {
                        String key = input.substring(i + 2, end);
                        out.append(env.getOrDefault(key, ""));
                        i = end;
                        continue;
                    }
                } else if (n == '?') {
                    out.append(lastExitCode);
                    i++;
                    continue;
                } else if (n == '$') {
                    out.append(System.identityHashCode(this) & 0xffff);
                    i++;
                    continue;
                } else if (Character.isLetter(n) || n == '_' || Character.isDigit(n)) {
                    int j = i + 1;
                    while (j < input.length()) {
                        char cj = input.charAt(j);
                        if (Character.isLetterOrDigit(cj) || cj == '_') {
                            j++;
                        } else {
                            break;
                        }
                    }
                    String key = input.substring(i + 1, j);
                    out.append(env.getOrDefault(key, ""));
                    i = j - 1;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static List<String> tokenize(String line) {
        return ShellSyntax.tokenize(line);
    }

    /** Split on a single-character separator at top level (not inside quotes). Does not split || when sep is |. */
    private static List<String> splitTopLevel(String line, String sep) {
        return ShellSyntax.splitTopLevel(line, sep.charAt(0));
    }

    private static String stripQuotes(String value) {
        return ShellSyntax.stripQuotes(value);
    }

    private static boolean isValidName(String name) {
        return ShellSyntax.isValidName(name);
    }

    private static String stripComment(String raw) {
        return ShellSyntax.stripComment(raw);
    }

    private String prompt() {
        String user = env.getOrDefault("USER", "player");
        String shortCwd = cwd;
        String home = env.getOrDefault("HOME", "/home/player");
        if (shortCwd.equals(home)) {
            shortCwd = "~";
        } else if (shortCwd.startsWith(home + "/")) {
            shortCwd = "~" + shortCwd.substring(home.length());
        }
        return user + "@terminal:" + shortCwd + "$ ";
    }

    private void printPromptLine() {
        println(prompt());
    }

    private void println(String line) {
        if (capturing) {
            String[] parts = line.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (captureBuffer.length() > 0) {
                    captureBuffer.append('\n');
                }
                captureBuffer.append(parts[i]);
            }
            return;
        }
        for (String part : line.split("\n", -1)) {
            output.add(part);
            if (activeResultOutput != null) {
                activeResultOutput.add(part);
            }
        }
        trimOutput();
    }

    private void trimOutput() {
        int max = Math.max(20, Config.maxHistoryLines);
        while (output.size() > max) {
            output.remove(0);
        }
    }

    public synchronized CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        PersistedDataVersions.stampCurrent(tag);
        tag.putString("Cwd", cwd);
        tag.putInt("Exit", lastExitCode);
        tag.putBoolean("Booted", booted);

        CompoundTag envTag = new CompoundTag();
        for (Map.Entry<String, String> e : env.entrySet()) {
            envTag.putString(e.getKey(), e.getValue());
        }
        tag.put("Env", envTag);

        ListTag outTag = new ListTag();
        for (String line : output) {
            outTag.add(StringTag.valueOf(line));
        }
        tag.put("Output", outTag);

        ListTag histTag = new ListTag();
        for (String h : commandHistory) {
            histTag.add(StringTag.valueOf(h));
        }
        tag.put("History", histTag);
        tag.put("Vfs", vfs.save());
        tag.putBoolean("DiskMounted", diskMounted);
        tag.putBoolean("EditorActive", editor.active);
        if (editor.path != null) {
            tag.putString("EditorPath", editor.path);
        }
        tag.putBoolean("EditorDirty", editor.dirty);
        tag.putInt("EditorCursor", editor.cursor);
        ListTag edLines = new ListTag();
        for (String ln : editor.lines) {
            edLines.add(StringTag.valueOf(ln.length() > MAX_EDITOR_LINE_LEN
                    ? ln.substring(0, MAX_EDITOR_LINE_LEN) : ln));
        }
        tag.put("EditorLines", edLines);
        return tag;
    }

    public synchronized void load(CompoundTag tag) {
        cwd = PersistedDataLimits.readString(tag, "Cwd", PersistedDataLimits.MAX_PATH_CHARS,
                "/home/player");
        if (cwd.isEmpty() || !cwd.startsWith("/")) {
            cwd = "/home/player";
        }
        lastExitCode = tag.getInt("Exit");
        booted = tag.getBoolean("Booted");

        env.clear();
        CompoundTag envTag = tag.contains("Env", Tag.TAG_COMPOUND)
                ? tag.getCompound("Env") : new CompoundTag();
        for (String key : PersistedDataLimits.boundedSortedKeys(envTag,
                PersistedDataLimits.MAX_ENV_ENTRIES)) {
            if (!envTag.contains(key, Tag.TAG_STRING)) continue;
            String boundedKey = PersistedDataLimits.truncate(key,
                    PersistedDataLimits.MAX_ENV_KEY_CHARS);
            if (boundedKey.isEmpty()) continue;
            env.put(boundedKey, PersistedDataLimits.readString(envTag, key,
                    PersistedDataLimits.MAX_ENV_VALUE_CHARS, ""));
        }
        if (!env.containsKey("HOME")) {
            env.put("HOME", "/home/player");
        }
        env.put("PWD", cwd);

        output.clear();
        ListTag outTag = tag.getList("Output", Tag.TAG_STRING);
        int outputLimit = Math.min(PersistedDataLimits.MAX_OUTPUT_LINES,
                Math.max(20, Config.maxHistoryLines));
        for (int i = Math.max(0, outTag.size() - outputLimit); i < outTag.size(); i++) {
            output.add(PersistedDataLimits.truncate(outTag.getString(i),
                    PersistedDataLimits.MAX_OUTPUT_LINE_CHARS));
        }

        commandHistory.clear();
        ListTag histTag = tag.getList("History", Tag.TAG_STRING);
        for (int i = Math.max(0, histTag.size() - PersistedDataLimits.MAX_HISTORY_ENTRIES);
             i < histTag.size(); i++) {
            commandHistory.addLast(PersistedDataLimits.truncate(histTag.getString(i),
                    PersistedDataLimits.MAX_HISTORY_LINE_CHARS));
        }

        if (tag.contains("Vfs", Tag.TAG_COMPOUND)) {
            vfs.load(tag.getCompound("Vfs"));
        }
        vfs.installMachinePrograms();
        diskMounted = tag.contains("DiskMounted") && tag.getBoolean("DiskMounted");
        if (!diskMounted && vfs.isDirectory(DISK_MOUNT)) {
            diskMounted = true;
        }

        editor.active = tag.contains("EditorActive") && tag.getBoolean("EditorActive");
        editor.path = tag.contains("EditorPath", Tag.TAG_STRING)
                ? PersistedDataLimits.readString(tag, "EditorPath",
                PersistedDataLimits.MAX_PATH_CHARS, null) : null;
        editor.dirty = tag.contains("EditorDirty") && tag.getBoolean("EditorDirty");
        editor.cursor = tag.contains("EditorCursor") ? tag.getInt("EditorCursor") : 0;
        editor.lines.clear();
        if (tag.contains("EditorLines", Tag.TAG_LIST)) {
            ListTag edLines = tag.getList("EditorLines", Tag.TAG_STRING);
            for (int i = 0; i < edLines.size() && i < MAX_EDITOR_LINES; i++) {
                String ln = edLines.getString(i);
                if (ln.length() > MAX_EDITOR_LINE_LEN) {
                    ln = ln.substring(0, MAX_EDITOR_LINE_LEN);
                }
                editor.lines.add(ln);
            }
        }
        if (editor.active && editor.lines.isEmpty()) {
            editor.lines.add("");
        }
        if (editor.cursor < 0 || editor.cursor >= Math.max(1, editor.lines.size())) {
            editor.cursor = Math.max(0, editor.lines.size() - 1);
        }
        if (!editor.active) {
            editor.path = null;
            editor.dirty = false;
            editor.lines.clear();
            editor.cursor = 0;
        }
        ensureBooted();
    }

    /** Simple in-memory hierarchical filesystem. */
    /**
     * Compatibility name retained for source users of BashShell.VirtualFileSystem.
     * The implementation now lives in the standalone filesystem service.
     */
    @Deprecated(forRemoval = false)
    public static final class VirtualFileSystem extends com.malice.terminalcraft.shell.VirtualFileSystem {
        public VirtualFileSystem() {
            super();
        }
    }
}
