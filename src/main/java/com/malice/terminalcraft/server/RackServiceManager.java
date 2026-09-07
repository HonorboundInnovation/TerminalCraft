package com.malice.terminalcraft.server;

import com.malice.terminalcraft.device.DeviceAuthorization;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.shell.ShellCommandResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/** Named, fixed-delay automation services sharing the rack's normal scheduler budget. */
public final class RackServiceManager {
    private static final int MAX_SERVICES = 32;
    private final Map<String, Service> services = new LinkedHashMap<>();
    private final java.util.function.Supplier<RackResourcePolicy> policy;
    public RackServiceManager() { this(() -> RackResourcePolicy.DEFAULT); }
    public RackServiceManager(java.util.function.Supplier<RackResourcePolicy> policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    private static final class Service {
        final String name;
        final DeviceCallContext owner;
        final String command;
        final long interval;
        boolean enabled;
        UUID job;
        long nextRun;
        long runs;
        int exit = -1;
        String state = "stopped";
        String error = "";
        Service(String name, DeviceCallContext owner, String command, long interval) {
            this.name = name; this.owner = owner; this.command = command; this.interval = interval;
        }
        String status() {
            return name + " " + state + " enabled=" + enabled + " interval=" + interval + " runs=" + runs
                    + " exit=" + exit + " next=" + nextRun + (job == null ? "" : " job=" + job)
                    + (error.isEmpty() ? "" : " error=" + error);
        }
    }

    public ShellCommandResult command(DeviceCallContext caller, List<String> args, ServerJobScheduler scheduler, long tick) {
        if (caller == null) return result(1, "service: authenticated caller required");
        String op = args.isEmpty() ? "list" : args.get(0);
        if (op.equals("list") && args.size() <= 1) {
            var lines = services.values().stream().filter(s -> owns(caller, s)).map(Service::status).toList();
            return new ShellCommandResult(0, lines.isEmpty() ? List.of("service: no services") : lines);
        }
        if (args.size() < 2) return usage();
        String name = args.get(1);
        Service service = services.get(name);
        if (op.equals("status") && args.size() == 2)
            return service != null && owns(caller, service) ? result(0, service.status()) : result(1, "service: not found");
        if (!DeviceAuthorization.allows(caller, DeviceAuthorization.Action.MUTATE))
            return result(1, "service: write permission required");
        if (op.equals("define") && args.size() >= 4) {
            if (!name.matches("[a-z][a-z0-9_-]{0,31}")) return result(1, "service: name must be 1–32 lowercase letters, digits, _ or -, starting with a letter");
            if (service != null) return result(1, "service: name already exists; remove your stopped service to redefine it");
            RackResourcePolicy limits = policy.get();
            if (services.size() >= limits.services() || services.values().stream().filter(s -> owns(caller, s)).count() >= limits.servicesPerOwner())
                return result(1, "service: definition policy limit reached; see server policy");
            long interval;
            try { interval = Long.parseLong(args.get(2)); }
            catch (NumberFormatException invalid) { return result(1, "service: interval must be game ticks"); }
            String command = String.join(" ", args.subList(3, args.size()));
            if (interval < 1 || interval > ServerJobScheduler.MAX_DELAY_TICKS || command.isBlank()
                    || command.length() > ServerJobScheduler.MAX_COMMAND_LENGTH)
                return result(1, "service: interval must be 1–12096000 ticks; command must be 1–1024 characters");
            services.put(name, new Service(name, caller, command, interval));
            return result(0, "service: " + name + " defined (stopped); use service start " + name);
        }
        if (args.size() != 2) return usage();
        if (service == null || !owns(caller, service)) return result(1, "service: not found");
        switch (op) {
            case "start", "restart" -> {
                if (service.enabled && op.equals("start")) return result(0, service.status());
                cancel(service, scheduler, tick);
                service.enabled = true;
                service.state = "waiting";
                service.error = "";
                service.nextRun = tick;
            }
            case "stop" -> {
                cancel(service, scheduler, tick);
                service.enabled = false;
                service.state = "stopped";
            }
            case "remove" -> {
                if (service.enabled || service.job != null) return result(1, "service: stop it before removing it");
                services.remove(name);
                return result(0, "service: " + name + " removed");
            }
            default -> { return usage(); }
        }
        return result(0, service.status());
    }

    private static boolean owns(DeviceCallContext caller, Service service) {
        return DeviceAuthorization.owns(caller, service.owner.principal());
    }

    private static void cancel(Service service, ServerJobScheduler scheduler, long tick) {
        if (service.job != null) scheduler.cancel(service.job, service.owner, tick);
        service.job = null;
    }

    /** Polls at most 32 services; each service owns at most one queued/running job. */
    public boolean tick(ServerJobScheduler scheduler, long tick) {
        boolean changed = false;
        for (Service service : services.values()) {
            if (!service.enabled) continue;
            if (service.job != null) {
                ServerJobScheduler.Job job = scheduler.get(service.job);
                if (job != null && (job.state() == ServerJobScheduler.State.QUEUED || job.state() == ServerJobScheduler.State.RUNNING)) continue;
                changed = true;
                service.job = null;
                service.runs++;
                service.exit = job == null ? 1 : job.exitCode();
                if (job == null || job.state() != ServerJobScheduler.State.COMPLETED) {
                    service.enabled = false;
                    service.state = "failed";
                    service.error = job == null ? "active job missing; restart explicitly" : job.lastError();
                    continue;
                }
                service.state = "waiting";
                service.nextRun = tick + Math.max(service.interval, policy.get().minimumServiceInterval());
            }
            if (tick < service.nextRun) continue;
            changed = true;
            try {
                service.job = scheduler.submit(service.owner, service.command, tick).id();
                service.state = "queued";
                service.error = "";
            } catch (IllegalStateException full) {
                service.state = "blocked";
                service.error = full.getMessage();
                service.nextRun = tick + 20;
            }
        }
        return changed;
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        for (Service service : services.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", service.name);
            ServerJobScheduler.saveContext(tag, service.owner);
            tag.putString("Command", service.command);
            tag.putLong("Interval", service.interval);
            tag.putBoolean("Enabled", service.enabled);
            if (service.job != null) tag.putUUID("Job", service.job);
            tag.putLong("NextRun", service.nextRun);
            tag.putLong("Runs", service.runs);
            tag.putInt("Exit", service.exit);
            tag.putString("State", service.state);
            tag.putString("Error", service.error);
            entries.add(tag);
        }
        root.put("Services", entries);
        return root;
    }

    public void load(CompoundTag root) {
        services.clear();
        ListTag entries = root.getList("Services", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_SERVICES, entries.size()); i++) {
            CompoundTag tag = entries.getCompound(i);
            String name = tag.getString("Name"), command = tag.getString("Command");
            long interval = tag.getLong("Interval");
            if (!name.matches("[a-z][a-z0-9_-]{0,31}") || command.isBlank() || command.length() > ServerJobScheduler.MAX_COMMAND_LENGTH
                    || interval < 1 || interval > ServerJobScheduler.MAX_DELAY_TICKS) continue;
            try {
                Service service = new Service(name, ServerJobScheduler.loadContext(tag), command, interval);
                service.enabled = tag.getBoolean("Enabled");
                service.job = tag.hasUUID("Job") ? tag.getUUID("Job") : null;
                service.nextRun = Math.max(0, tag.getLong("NextRun"));
                service.runs = Math.max(0, tag.getLong("Runs"));
                service.exit = tag.getInt("Exit");
                service.state = service.enabled ? (service.job == null ? "waiting" : "queued")
                        : tag.getString("State").equals("failed") ? "failed" : "stopped";
                String error = tag.getString("Error");
                service.error = error.substring(0, Math.min(error.length(), ServerJobScheduler.MAX_ERROR_LENGTH));
                services.put(name, service);
            } catch (IllegalArgumentException invalid) { /* Invalid definitions never acquire fallback authority. */ }
        }
    }

    private static ShellCommandResult result(int code, String line) { return new ShellCommandResult(code, List.of(line)); }
    private static ShellCommandResult usage() {
        return result(1, "service: list|define <name> <interval-ticks> '<command>'|start|stop|restart|status|remove <name>");
    }
}
