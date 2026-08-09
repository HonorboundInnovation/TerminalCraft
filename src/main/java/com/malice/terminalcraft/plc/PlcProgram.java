package com.malice.terminalcraft.plc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Bounded, deterministic PLC program compiler and scan-cycle runtime.
 *
 * <p>The language is intentionally small and ladder-like. A program is made of declarations and
 * rungs, for example:</p>
 *
 * <pre>
 * SCAN 2
 * IN START REDSTONE NORTH
 * IN STOP REDSTONE SOUTH
 * OUT MOTOR REDSTONE EAST
 * TIMER DELAY 20 = START
 * RUNG MOTOR = START AND DELAY.DONE AND NOT STOP OR MOTOR
 * </pre>
 *
 * <p>Bindings use {@code REDSTONE side}, {@code BUNDLED side channel}, or {@code SENSOR channel}
 * for a sensor array adjacent to the PLC. Expressions are boolean
 * and support {@code AND}, {@code OR}, {@code NOT}, parentheses, {@code ON}, and {@code OFF}.
 * Timers count game ticks, counters count rising edges, and latches are reset-dominant.</p>
 */
public final class PlcProgram {
    public static final int MAX_SOURCE_CHARS = 16 * 1024;
    public static final int MAX_LINES = 128;
    public static final int MAX_LINE_CHARS = 192;
    public static final int MAX_BINDINGS = 32;
    public static final int MAX_RULES = 64;
    public static final int MAX_TIMERS = 16;
    public static final int MAX_COUNTERS = 16;
    public static final int MAX_LATCHES = 16;
    public static final int MIN_SCAN_INTERVAL = 1;
    public static final int MAX_SCAN_INTERVAL = 20;
    public static final int MAX_TIMER_TICKS = 20 * 60 * 60;
    public static final int MAX_COUNTER_PRESET = 1_000_000;

    private PlcProgram() {}

    public enum BindingKind { REDSTONE, BUNDLED, SENSOR }

    public record Binding(String name, BindingKind kind, String side, int channel) {
        public Binding {
            name = normalizeName(name);
            side = side == null ? "" : side.toLowerCase(Locale.ROOT);
            channel = kind == BindingKind.BUNDLED ? channel : -1;
        }
    }

    public record Rule(String name, Expression expression) {
        public Rule {
            name = normalizeName(name);
        }
    }

    public record Timer(String name, int presetTicks, Expression expression) {
        public Timer {
            name = normalizeName(name);
        }
    }

    public record Counter(String name, int preset, Expression expression) {
        public Counter {
            name = normalizeName(name);
        }
    }

    public record Latch(String name, Expression setExpression, Expression resetExpression) {
        public Latch {
            name = normalizeName(name);
        }
    }

    /** Bounded analog transfer or linear scaling operation. */
    public record AnalogTransfer(String target, String source,
                                 int sourceMin, int sourceMax, int targetMin, int targetMax) {
        public AnalogTransfer {
            target = normalizeName(target);
            source = normalizeName(source);
        }
    }

    /** Bounded discrete PID loop using 0..15 PLC signal values. */
    public record PidLoop(String name, String setpoint, String process, String output,
                          double kp, double ki, double kd) {
        public PidLoop {
            name = normalizeName(name);
            setpoint = setpoint == null ? "0" : setpoint.trim().toUpperCase(Locale.ROOT);
            process = normalizeName(process);
            output = normalizeName(output);
        }
    }

    public record Compiled(int scanIntervalTicks, List<Binding> inputs, List<Binding> outputs,
                           List<Rule> rules, List<Timer> timers, List<Counter> counters,
                           List<Latch> latches, String source,
                           Set<String> analogInputs, Set<String> analogOutputs,
                           List<AnalogTransfer> analogTransfers, List<PidLoop> pidLoops) {
        public Compiled {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            rules = List.copyOf(rules);
            timers = List.copyOf(timers);
            counters = List.copyOf(counters);
            latches = List.copyOf(latches);
            source = source == null ? "" : source;
            analogInputs = Set.copyOf(analogInputs);
            analogOutputs = Set.copyOf(analogOutputs);
            analogTransfers = List.copyOf(analogTransfers);
            pidLoops = List.copyOf(pidLoops);
        }

        public Compiled(int scanIntervalTicks, List<Binding> inputs, List<Binding> outputs,
                        List<Rule> rules, List<Timer> timers, List<Counter> counters,
                        List<Latch> latches, String source) {
            this(scanIntervalTicks, inputs, outputs, rules, timers, counters, latches, source,
                    Set.of(), Set.of(), List.of(), List.of());
        }
    }

    public record CompileResult(Compiled program, String error) {
        public boolean successful() { return program != null && error == null; }
    }

    public interface Io {
        /** Returns a 0..15 signal, or a negative value when the binding cannot be read. */
        int read(Binding binding);

        /** Writes a 0..15 signal and returns false when the binding cannot be driven. */
        boolean write(Binding binding, int strength);
    }

    public record ScanResult(boolean success, String fault, Map<String, Boolean> signals,
                             long scanCount) {
        public ScanResult {
            signals = Map.copyOf(signals);
            fault = fault == null ? "" : fault;
        }
    }

    /** Mutable scan-cycle state. A controller owns one instance and resets it on program reload. */
    public static final class Controller {
        private Compiled program = new Compiled(2, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "");
        private final Map<String, Boolean> signals = new LinkedHashMap<>();
        private final Map<String, Integer> timerElapsed = new LinkedHashMap<>();
        private final Map<String, Integer> counterValue = new LinkedHashMap<>();
        private final Map<String, Boolean> counterPrevious = new LinkedHashMap<>();
        private final Map<String, Boolean> latchValue = new LinkedHashMap<>();
        private final Map<String, Integer> analogValues = new LinkedHashMap<>();
        private final Map<String, Integer> pidOutputs = new LinkedHashMap<>();
        private final Map<String, Double> pidIntegral = new LinkedHashMap<>();
        private final Map<String, Double> pidPreviousError = new LinkedHashMap<>();
        private boolean running;
        private String fault = "";
        private long scanCount;

        public Compiled program() { return program; }
        public boolean running() { return running; }
        public String fault() { return fault; }
        public long scanCount() { return scanCount; }
        public Map<String, Boolean> signals() { return Map.copyOf(signals); }
        public Map<String, Integer> timerElapsed() { return Map.copyOf(timerElapsed); }
        public Map<String, Integer> counterValues() { return Map.copyOf(counterValue); }
        public Map<String, Boolean> latchValues() { return Map.copyOf(latchValue); }
        public Map<String, Integer> analogValues() { return Map.copyOf(analogValues); }
        public Map<String, Integer> pidOutputs() { return Map.copyOf(pidOutputs); }

        public void load(Compiled next) {
            program = next == null
                    ? new Compiled(2, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "")
                    : next;
            resetState();
        }

        public void start() {
            if (fault.isEmpty() && (!program.inputs().isEmpty() || !program.outputs().isEmpty()
                    || !program.rules().isEmpty() || !program.timers().isEmpty()
                    || !program.counters().isEmpty() || !program.latches().isEmpty()
                    || !program.analogTransfers().isEmpty() || !program.pidLoops().isEmpty())) {
                running = true;
            }
        }

        public void stop() {
            running = false;
        }

        public void resetState() {
            running = false;
            fault = "";
            scanCount = 0;
            signals.clear();
            timerElapsed.clear();
            counterValue.clear();
            counterPrevious.clear();
            latchValue.clear();
            analogValues.clear();
            pidOutputs.clear();
            pidIntegral.clear();
            pidPreviousError.clear();
            for (Timer timer : program.timers()) timerElapsed.put(timer.name(), 0);
            for (Counter counter : program.counters()) {
                counterValue.put(counter.name(), 0);
                counterPrevious.put(counter.name(), false);
            }
            for (Latch latch : program.latches()) latchValue.put(latch.name(), false);
            for (PidLoop pid : program.pidLoops()) {
                pidIntegral.put(pid.name(), 0.0);
                pidPreviousError.put(pid.name(), 0.0);
                pidOutputs.put(pid.name(), 0);
            }
        }

        public ScanResult scan(Io io) {
            if (!running) return new ScanResult(true, "", signals, scanCount);
            if (io == null) return fail(null, "I/O bridge unavailable");

            for (Binding input : program.inputs()) {
                int value = io.read(input);
                if (value < 0) return fail(io, "input unavailable: " + input.name());
                value = clampSignal(value);
                analogValues.put(input.name(), value);
                signals.put(input.name(), value > 0);
            }

            for (Timer timer : program.timers()) {
                boolean active = timer.expression().evaluate(signals);
                int elapsed = active
                        ? Math.min(timer.presetTicks(), timerElapsed.getOrDefault(timer.name(), 0)
                                + program.scanIntervalTicks())
                        : 0;
                timerElapsed.put(timer.name(), elapsed);
                signals.put(timer.name() + ".ACTIVE", active);
                signals.put(timer.name() + ".DONE", active && elapsed >= timer.presetTicks());
            }

            for (Counter counter : program.counters()) {
                boolean active = counter.expression().evaluate(signals);
                boolean previous = counterPrevious.getOrDefault(counter.name(), false);
                int value = counterValue.getOrDefault(counter.name(), 0);
                if (active && !previous) value = Math.min(counter.preset(), value + 1);
                counterValue.put(counter.name(), value);
                counterPrevious.put(counter.name(), active);
                signals.put(counter.name() + ".ACTIVE", active);
                signals.put(counter.name() + ".DONE", value >= counter.preset());
            }

            for (Latch latch : program.latches()) {
                boolean value = latchValue.getOrDefault(latch.name(), false);
                if (latch.setExpression().evaluate(signals)) value = true;
                if (latch.resetExpression().evaluate(signals)) value = false;
                latchValue.put(latch.name(), value);
                signals.put(latch.name(), value);
            }

            Map<String, Boolean> outputs = new LinkedHashMap<>();
            for (Rule rule : program.rules()) {
                boolean value = rule.expression().evaluate(signals);
                signals.put(rule.name(), value);
                outputs.put(rule.name(), value);
            }
            for (AnalogTransfer transfer : program.analogTransfers()) {
                int source = numericValue(transfer.source());
                int scaled = transfer.sourceMax() == transfer.sourceMin() ? transfer.targetMin()
                        : transfer.targetMin() + (source - transfer.sourceMin())
                        * (transfer.targetMax() - transfer.targetMin())
                        / (transfer.sourceMax() - transfer.sourceMin());
                analogValues.put(transfer.target(), clampSignal(scaled));
                signals.put(transfer.target(), scaled > 0);
            }
            for (PidLoop pid : program.pidLoops()) {
                double setpoint = numericValue(pid.setpoint());
                double process = numericValue(pid.process());
                double error = setpoint - process;
                double integral = Math.max(-256.0, Math.min(256.0,
                        pidIntegral.getOrDefault(pid.name(), 0.0) + error * program.scanIntervalTicks()));
                double derivative = (error - pidPreviousError.getOrDefault(pid.name(), 0.0))
                        / Math.max(1, program.scanIntervalTicks());
                int output = clampSignal((int) Math.round(pid.kp() * error + pid.ki() * integral + pid.kd() * derivative));
                pidIntegral.put(pid.name(), integral);
                pidPreviousError.put(pid.name(), error);
                pidOutputs.put(pid.name(), output);
                analogValues.put(pid.output(), output);
                signals.put(pid.output(), output > 0);
            }
            for (Binding output : program.outputs()) {
                int strength = program.analogOutputs().contains(output.name())
                        ? analogValues.getOrDefault(output.name(), 0)
                        : (outputs.getOrDefault(output.name(), signals.getOrDefault(output.name(), false)) ? 15 : 0);
                if (!io.write(output, strength)) {
                    return fail(io, "output unavailable: " + output.name());
                }
            }
            scanCount++;
            return new ScanResult(true, "", signals, scanCount);
        }

        private ScanResult fail(Io io, String message) {
            running = false;
            fault = message == null ? "PLC fault" : message;
            if (io != null) {
                for (Binding output : program.outputs()) {
                    try { io.write(output, 0); } catch (RuntimeException ignored) { /* safe best effort */ }
                }
            }
            return new ScanResult(false, fault, signals, scanCount);
        }

        private int numericValue(String reference) {
            try { return clampSignal((int) Math.round(Double.parseDouble(reference))); }
            catch (NumberFormatException ignored) {
                return analogValues.getOrDefault(reference, signals.getOrDefault(reference, false) ? 15 : 0);
            }
        }

        private static int clampSignal(int value) { return Math.max(0, Math.min(15, value)); }
    }

    public static CompileResult compile(String source) {
        String input = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        if (input.length() > MAX_SOURCE_CHARS) return error("program exceeds " + MAX_SOURCE_CHARS + " characters");
        String[] rawLines = input.split("\n", -1);
        if (rawLines.length > MAX_LINES
                && !(rawLines.length == MAX_LINES + 1 && rawLines[rawLines.length - 1].isEmpty())) {
            return error("program exceeds " + MAX_LINES + " lines");
        }

        int scan = 2;
        List<Binding> inputs = new ArrayList<>();
        List<Binding> outputs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<Timer> timers = new ArrayList<>();
        List<Counter> counters = new ArrayList<>();
        List<Latch> latches = new ArrayList<>();
        Set<String> analogInputs = new HashSet<>();
        Set<String> analogOutputs = new HashSet<>();
        List<AnalogTransfer> analogTransfers = new ArrayList<>();
        List<PidLoop> pidLoops = new ArrayList<>();
        Set<String> bindingNames = new HashSet<>();
        Set<String> ruleNames = new HashSet<>();
        Set<String> timerNames = new HashSet<>();
        Set<String> counterNames = new HashSet<>();
        Set<String> latchNames = new HashSet<>();
        List<String> canonical = new ArrayList<>();

        for (int index = 0; index < rawLines.length; index++) {
            String line = stripComment(rawLines[index]).trim();
            if (line.isEmpty()) continue;
            if (line.length() > MAX_LINE_CHARS) return errorAt(index + 1, "line exceeds " + MAX_LINE_CHARS + " characters");
            canonical.add(line);
            List<String> tokens = splitWords(line);
            String op = tokens.get(0).toUpperCase(Locale.ROOT);
            try {
                switch (op) {
                    case "SCAN" -> {
                        require(tokens, 2, index, "SCAN <ticks>");
                        scan = integer(tokens.get(1), MIN_SCAN_INTERVAL, MAX_SCAN_INTERVAL, index, "scan interval");
                    }
                    case "IN", "INPUT" -> {
                        Binding binding = parseBinding(tokens, index,
                                "IN <name> REDSTONE <side> | IN <name> BUNDLED <side> <channel> | IN <name> SENSOR <channel>");
                        if (!bindingNames.add(binding.name())) throw lineError(index, "duplicate binding: " + binding.name());
                        inputs.add(binding);
                    }
                    case "AIN", "ANALOG_IN" -> {
                        Binding binding = parseBinding(tokens, index,
                                "AIN <name> REDSTONE <side> | AIN <name> BUNDLED <side> <channel> | AIN <name> SENSOR <channel>");
                        if (!bindingNames.add(binding.name())) throw lineError(index, "duplicate binding: " + binding.name());
                        inputs.add(binding);
                        analogInputs.add(binding.name());
                    }
                    case "OUT", "OUTPUT" -> {
                        Binding binding = parseBinding(tokens, index, "OUT <name> REDSTONE <side> | OUT <name> BUNDLED <side> <channel>");
                        if (binding.kind() == BindingKind.SENSOR) throw lineError(index, "sensor bindings are input-only");
                        if (!bindingNames.add(binding.name())) throw lineError(index, "duplicate binding: " + binding.name());
                        outputs.add(binding);
                    }
                    case "AOUT", "ANALOG_OUT" -> {
                        Binding binding = parseBinding(tokens, index,
                                "AOUT <name> REDSTONE <side> | AOUT <name> BUNDLED <side> <channel>");
                        if (binding.kind() == BindingKind.SENSOR) throw lineError(index, "sensor bindings are input-only");
                        if (!bindingNames.add(binding.name())) throw lineError(index, "duplicate binding: " + binding.name());
                        outputs.add(binding);
                        analogOutputs.add(binding.name());
                    }
                    case "RUNG", "LOGIC" -> {
                        require(tokens, 4, index, "RUNG <output> = <expression>");
                        if (!"=".equals(tokens.get(2))) throw lineError(index, "missing '=' in rung");
                        String name = normalizeName(tokens.get(1));
                        if (!ruleNames.add(name)) throw lineError(index, "duplicate rung: " + name);
                        rules.add(new Rule(name, parseExpression(join(tokens, 3), index)));
                        if (rules.size() > MAX_RULES) throw lineError(index, "too many rungs");
                    }
                    case "MOVE" -> {
                        require(tokens, 4, index, "MOVE <target> = <source>");
                        if (!"=".equals(tokens.get(2))) throw lineError(index, "missing '=' in MOVE");
                        analogTransfers.add(new AnalogTransfer(tokens.get(1), tokens.get(3), 0, 15, 0, 15));
                    }
                    case "SCALE" -> {
                        require(tokens, 8, index,
                                "SCALE <target> = <source> <in_min> <in_max> <out_min> <out_max>");
                        if (!"=".equals(tokens.get(2))) throw lineError(index, "missing '=' in SCALE");
                        int inputMin = integer(tokens.get(4), 0, 15, index, "input minimum");
                        int inputMax = integer(tokens.get(5), 0, 15, index, "input maximum");
                        if (inputMin == inputMax) throw lineError(index, "scale input range must not be zero");
                        analogTransfers.add(new AnalogTransfer(tokens.get(1), tokens.get(3), inputMin, inputMax,
                                integer(tokens.get(6), 0, 15, index, "output minimum"),
                                integer(tokens.get(7), 0, 15, index, "output maximum")));
                    }
                    case "PID" -> pidLoops.add(parsePid(tokens, index));
                    case "TIMER" -> {
                        require(tokens, 5, index, "TIMER <name> <ticks> = <expression>");
                        if (!"=".equals(tokens.get(3))) throw lineError(index, "missing '=' in timer");
                        String name = normalizeName(tokens.get(1));
                        if (!timerNames.add(name) || timerNames.size() > MAX_TIMERS) throw lineError(index, "too many or duplicate timers");
                        int preset = integer(tokens.get(2), 1, MAX_TIMER_TICKS, index, "timer preset");
                        timers.add(new Timer(name, preset, parseExpression(join(tokens, 4), index)));
                    }
                    case "COUNTER" -> {
                        require(tokens, 5, index, "COUNTER <name> <count> = <expression>");
                        if (!"=".equals(tokens.get(3))) throw lineError(index, "missing '=' in counter");
                        String name = normalizeName(tokens.get(1));
                        if (!counterNames.add(name) || counterNames.size() > MAX_COUNTERS) throw lineError(index, "too many or duplicate counters");
                        int preset = integer(tokens.get(2), 1, MAX_COUNTER_PRESET, index, "counter preset");
                        counters.add(new Counter(name, preset, parseExpression(join(tokens, 4), index)));
                    }
                    case "LATCH" -> {
                        require(tokens, 6, index, "LATCH <name> SET <expression> RESET <expression>");
                        String name = normalizeName(tokens.get(1));
                        int reset = indexOfIgnoreCase(tokens, "RESET", 3);
                        if (!"SET".equalsIgnoreCase(tokens.get(2)) || reset < 4 || reset == tokens.size() - 1) {
                            throw lineError(index, "latch requires SET <expression> RESET <expression>");
                        }
                        if (!latchNames.add(name) || latchNames.size() > MAX_LATCHES) throw lineError(index, "too many or duplicate latches");
                        latches.add(new Latch(name, parseExpression(join(tokens, 3, reset), index),
                                parseExpression(join(tokens, reset + 1), index)));
                    }
                    default -> throw lineError(index, "unknown instruction: " + tokens.get(0));
                }
            } catch (IllegalArgumentException ex) {
                return errorAt(index + 1, ex.getMessage());
            }
            if (inputs.size() + outputs.size() > MAX_BINDINGS) return errorAt(index + 1, "too many I/O bindings");
        }

        Set<String> known = new HashSet<>();
        inputs.forEach(b -> known.add(b.name()));
        outputs.forEach(b -> known.add(b.name()));
        rules.forEach(r -> known.add(r.name()));
        latches.forEach(l -> known.add(l.name()));
        timers.forEach(t -> { known.add(t.name() + ".ACTIVE"); known.add(t.name() + ".DONE"); });
        counters.forEach(c -> { known.add(c.name() + ".ACTIVE"); known.add(c.name() + ".DONE"); });
        analogTransfers.forEach(transfer -> known.add(transfer.target()));
        try {
            for (Rule rule : rules) validateReferences(rule.expression(), known, "rung " + rule.name());
            for (Timer timer : timers) validateReferences(timer.expression(), known, "timer " + timer.name());
            for (Counter counter : counters) validateReferences(counter.expression(), known, "counter " + counter.name());
            for (Latch latch : latches) {
                validateReferences(latch.setExpression(), known, "latch " + latch.name());
                validateReferences(latch.resetExpression(), known, "latch " + latch.name());
            }
            for (AnalogTransfer transfer : analogTransfers) {
                if (!analogOutputs.contains(transfer.target())) {
                    throw new IllegalArgumentException("analog transfer target must be an AOUT: " + transfer.target());
                }
                if (!known.contains(transfer.source())) {
                    throw new IllegalArgumentException("analog transfer references unknown signal: " + transfer.source());
                }
            }
            for (PidLoop pid : pidLoops) {
                if (!analogOutputs.contains(pid.output())) {
                    throw new IllegalArgumentException("PID output must be an AOUT: " + pid.output());
                }
                if (!known.contains(pid.process())) {
                    throw new IllegalArgumentException("PID process references unknown signal: " + pid.process());
                }
                try { Double.parseDouble(pid.setpoint()); }
                catch (NumberFormatException ignored) {
                    if (!known.contains(pid.setpoint())) {
                        throw new IllegalArgumentException("PID setpoint references unknown signal: " + pid.setpoint());
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
        if (rules.isEmpty() && !outputs.isEmpty() && analogTransfers.isEmpty() && pidLoops.isEmpty()) {
            return error("program declares outputs but no rungs or analog operation");
        }
        String canonicalSource = String.join("\n", canonical);
        return new CompileResult(new Compiled(scan, inputs, outputs, rules, timers, counters, latches,
                canonicalSource, analogInputs, analogOutputs, analogTransfers, pidLoops), null);
    }

    private static PidLoop parsePid(List<String> tokens, int line) {
        require(tokens, 14, line,
                "PID <name> SETPOINT <value|signal> PROCESS <signal> OUTPUT <AOUT> KP <number> KI <number> KD <number>");
        if (!"SETPOINT".equalsIgnoreCase(tokens.get(2)) || !"PROCESS".equalsIgnoreCase(tokens.get(4))
                || !"OUTPUT".equalsIgnoreCase(tokens.get(6)) || !"KP".equalsIgnoreCase(tokens.get(8))
                || !"KI".equalsIgnoreCase(tokens.get(10)) || !"KD".equalsIgnoreCase(tokens.get(12))) {
            throw lineError(line, "PID requires SETPOINT, PROCESS, OUTPUT, KP, KI, and KD");
        }
        return new PidLoop(tokens.get(1), tokens.get(3), tokens.get(5), tokens.get(7),
                decimal(tokens.get(9), line, "KP"), decimal(tokens.get(11), line, "KI"),
                decimal(tokens.get(13), line, "KD"));
    }

    private static double decimal(String value, int line, String field) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < -64.0 || parsed > 64.0) {
                throw lineError(line, field + " must be between -64 and 64");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw lineError(line, field + " must be a number");
        }
    }

    private static Binding parseBinding(List<String> tokens, int line, String usage) {
        require(tokens, 4, line, usage);
        String name = normalizeName(tokens.get(1));
        if (!name.matches("[A-Z_][A-Z0-9_.]*")) throw lineError(line, "invalid signal name: " + tokens.get(1));
        BindingKind kind;
        try { kind = BindingKind.valueOf(tokens.get(2).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw lineError(line, "binding kind must be REDSTONE, BUNDLED, or SENSOR"); }
        int channel = -1;
        if (kind == BindingKind.BUNDLED) {
            require(tokens, 5, line, usage);
            channel = integer(tokens.get(4), 0, 15, line, "bundled channel");
        } else if (tokens.size() != 4) {
            throw lineError(line, usage);
        }
        if (tokens.get(3).isBlank()) throw lineError(line, "side must not be blank");
        return new Binding(name, kind, tokens.get(3), channel);
    }

    private static void validateReferences(Expression expression, Set<String> known, String owner) {
        for (String reference : expression.references()) {
            if (!known.contains(reference) && !"ON".equals(reference) && !"OFF".equals(reference)) {
                throw new IllegalArgumentException(owner + " references unknown signal: " + reference);
            }
        }
    }

    public interface Expression {
        boolean evaluate(Map<String, Boolean> signals);
        Set<String> references();
    }

    private record Literal(boolean value) implements Expression {
        @Override public boolean evaluate(Map<String, Boolean> signals) { return value; }
        @Override public Set<String> references() { return Set.of(); }
    }
    private record Signal(String name) implements Expression {
        @Override public boolean evaluate(Map<String, Boolean> signals) { return signals.getOrDefault(name, false); }
        @Override public Set<String> references() { return Set.of(name); }
    }
    private record Not(Expression value) implements Expression {
        @Override public boolean evaluate(Map<String, Boolean> signals) { return !value.evaluate(signals); }
        @Override public Set<String> references() { return value.references(); }
    }
    private record Binary(Expression left, Expression right, boolean and) implements Expression {
        @Override public boolean evaluate(Map<String, Boolean> signals) {
            return and ? left.evaluate(signals) && right.evaluate(signals)
                    : left.evaluate(signals) || right.evaluate(signals);
        }
        @Override public Set<String> references() {
            Set<String> refs = new HashSet<>(left.references());
            refs.addAll(right.references());
            return Set.copyOf(refs);
        }
    }

    private static Expression parseExpression(String source, int line) {
        try {
            ExpressionParser parser = new ExpressionParser(source);
            Expression result = parser.parseOr();
            if (!parser.atEnd()) throw lineError(line, "unexpected token in expression: " + parser.peek());
            return result;
        } catch (IllegalArgumentException ex) {
            throw ex;
        }
    }

    private static final class ExpressionParser {
        private final List<String> tokens;
        private int index;
        ExpressionParser(String source) { tokens = expressionTokens(source); }
        boolean atEnd() { return index >= tokens.size(); }
        String peek() { return atEnd() ? "<end>" : tokens.get(index); }
        Expression parseOr() {
            Expression value = parseAnd();
            while (!atEnd() && ("OR".equals(peek()) || "||".equals(peek()))) { index++; value = new Binary(value, parseAnd(), false); }
            return value;
        }
        Expression parseAnd() {
            Expression value = parseUnary();
            while (!atEnd() && ("AND".equals(peek()) || "&&".equals(peek()))) { index++; value = new Binary(value, parseUnary(), true); }
            return value;
        }
        Expression parseUnary() {
            if (!atEnd() && ("NOT".equals(peek()) || "!".equals(peek()))) { index++; return new Not(parseUnary()); }
            if (!atEnd() && "(".equals(peek())) {
                index++;
                Expression value = parseOr();
                if (atEnd() || !")".equals(peek())) throw new IllegalArgumentException("missing ')' in expression");
                index++;
                return value;
            }
            if (atEnd()) throw new IllegalArgumentException("empty expression");
            String token = tokens.get(index++);
            if ("ON".equals(token) || "TRUE".equals(token)) return new Literal(true);
            if ("OFF".equals(token) || "FALSE".equals(token)) return new Literal(false);
            if (!token.matches("[A-Z_][A-Z0-9_.]*")) throw new IllegalArgumentException("invalid expression token: " + token);
            return new Signal(token);
        }
    }

    private static List<String> expressionTokens(String source) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { flush(current, result); continue; }
            if (c == '(' || c == ')' || c == '!') { flush(current, result); result.add(String.valueOf(c)); continue; }
            if (c == '&' || c == '|') {
                flush(current, result);
                if (i + 1 < source.length() && source.charAt(i + 1) == c) i++;
                result.add(c == '&' ? "&&" : "||");
                continue;
            }
            current.append(Character.toUpperCase(c));
        }
        flush(current, result);
        return result;
    }

    private static List<String> splitWords(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) { flush(current, result); }
            else if (c == '=') { flush(current, result); result.add("="); }
            else { current.append(c); }
        }
        flush(current, result);
        return result;
    }

    private static void flush(StringBuilder current, List<String> result) {
        if (current.length() > 0) { result.add(current.toString()); current.setLength(0); }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int slash = line.indexOf("//");
        int cut = hash < 0 ? slash : slash < 0 ? hash : Math.min(hash, slash);
        return cut < 0 ? line : line.substring(0, cut);
    }

    private static String join(List<String> values, int from) { return join(values, from, values.size()); }
    private static String join(List<String> values, int from, int to) {
        return String.join(" ", values.subList(from, to));
    }

    private static int indexOfIgnoreCase(List<String> values, String needle, int from) {
        for (int i = from; i < values.size(); i++) if (needle.equalsIgnoreCase(values.get(i))) return i;
        return -1;
    }

    private static void require(List<String> tokens, int count, int line, String usage) {
        if (tokens.size() < count) throw lineError(line, "usage: " + usage);
    }

    private static int integer(String value, int min, int max, int line, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw lineError(line, field + " must be " + min + ".." + max);
            return parsed;
        } catch (NumberFormatException ex) {
            throw lineError(line, field + " must be an integer");
        }
    }

    private static IllegalArgumentException lineError(int zeroBasedLine, String message) {
        return new IllegalArgumentException("line " + (zeroBasedLine + 1) + ": " + message);
    }

    private static CompileResult error(String message) { return new CompileResult(null, message); }
    private static CompileResult errorAt(int line, String message) { return new CompileResult(null, "line " + line + ": " + message); }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
