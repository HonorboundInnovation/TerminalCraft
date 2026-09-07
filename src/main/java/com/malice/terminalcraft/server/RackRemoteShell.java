package com.malice.terminalcraft.server;

import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.device.*;
import com.malice.terminalcraft.shell.ShellCommandResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/** Opt-in, player-owned remote command access to a rack's shared shell. */
public final class RackRemoteShell {
    private UUID owner;
    private boolean enabled;
    private boolean executing;
    private final Set<UUID> allowed = new LinkedHashSet<>();
    private final Deque<String> audit = new ArrayDeque<>();

    public ShellCommandResult configure(DeviceCallContext caller, List<String> args, long tick) {
        if (caller == null || caller.principal().kind() != PrincipalIdentity.Kind.PLAYER
                || !DeviceAuthorization.allows(caller, DeviceAuthorization.Action.MUTATE))
            return result(1, "server remote: authenticated player with write permission required");
        String operation = args.isEmpty() ? "status" : args.get(0);
        if (executing) return result(1, "server remote: configure access at the local rack");
        if (owner == null && operation.equals("on") && args.size() == 1) owner = caller.principalId();
        if (!caller.principalId().equals(owner)) return result(1, "server remote: rack remote-shell owner required; enable locally with server remote on");
        if (operation.equals("status") && args.size() <= 1)
            return result(0, "remote=" + (enabled ? "on" : "off") + " owner=" + owner + " allowed=" + allowed);
        if (operation.equals("audit") && args.size() == 1)
            return new ShellCommandResult(0, audit.isEmpty() ? List.of("(no remote activity)") : List.copyOf(audit));
        if ((operation.equals("on") || operation.equals("off")) && args.size() == 1) {
            enabled = operation.equals("on");
        } else if ((operation.equals("allow") || operation.equals("deny")) && args.size() == 2) {
            UUID player;
            try { player = UUID.fromString(args.get(1)); }
            catch (IllegalArgumentException invalid) { return result(1, "server remote: player UUID required"); }
            if (operation.equals("allow")) {
                if (allowed.size() >= 32 && !allowed.contains(player)) return result(1, "server remote: 32-player access limit");
                allowed.add(player);
            } else allowed.remove(player);
        } else return result(1, "server remote: on|off|status|audit|allow <player-uuid>|deny <player-uuid>");
        record(tick, caller, operation, 0);
        return result(0, "server remote: " + operation + " saved");
    }

    public DeviceResult execute(ServerRackBlockEntity rack, DeviceCallContext caller, String command) {
        long tick = rack.getLevel() == null ? 0 : rack.getLevel().getGameTime();
        RackResourcePolicy policy = com.malice.terminalcraft.Config.rackResourcePolicy();
        if (!policy.remoteShellEnabled() || !enabled || caller == null || caller.principal().kind() != PrincipalIdentity.Kind.PLAYER
                || !DeviceAuthorization.allows(caller, DeviceAuthorization.Action.MUTATE)
                || !(caller.principalId().equals(owner) || allowed.contains(caller.principalId()))) {
            record(tick, caller, "denied", 1);
            rack.setChanged();
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED, "remote shell is disabled or caller is not allowed", false);
        }
        if (rack.isRemoved() || rack.getLevel() == null || rack.getLevel().isClientSide || !rack.hasServerModule())
            return DeviceResult.failure(DeviceErrorCode.OFFLINE, "server blade unavailable", true);
        if (executing) return DeviceResult.failure(DeviceErrorCode.BUSY, "rack is already executing a remote command", true);
        if (command.isBlank() || command.length() > DeviceValue.MAX_STRING_LENGTH)
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "command must contain 1 to 4096 characters", false);
        executing = true;
        int exitCode = 1;
        try {
            ShellCommandResult result = rack.getShell().executeRemoteForResult(command, caller);
            exitCode = result.exitCode();
            String output = String.join("\n", result.outputLines());
            boolean truncated = output.length() > policy.remoteOutputCharacters();
            if (truncated) output = output.substring(0, policy.remoteOutputCharacters());
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "exit", DeviceValue.of(exitCode), "output", DeviceValue.of(output), "truncated", DeviceValue.of(truncated))));
        } finally {
            executing = false;
            record(tick, caller, "exec", exitCode);
            rack.markShellChanged();
        }
    }

    private void record(long tick, DeviceCallContext caller, String operation, int exitCode) {
        // Do not persist command text, output, or secrets in the audit trail.
        audit.addLast(tick + " " + (caller == null ? "unbound" : caller.principal().authorityKey()) + " " + operation + " exit=" + exitCode);
        while (audit.size() > 64) audit.removeFirst();
    }

    private static ShellCommandResult result(int code, String line) { return new ShellCommandResult(code, List.of(line)); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putBoolean("Enabled", enabled);
        ListTag players = new ListTag();
        allowed.forEach(id -> players.add(StringTag.valueOf(id.toString())));
        tag.put("Allowed", players);
        ListTag entries = new ListTag();
        audit.forEach(line -> entries.add(StringTag.valueOf(line)));
        tag.put("Audit", entries);
        return tag;
    }

    public void load(CompoundTag tag) {
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        enabled = owner != null && tag.getBoolean("Enabled");
        allowed.clear();
        ListTag players = tag.getList("Allowed", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(32, players.size()); i++) {
            try { allowed.add(UUID.fromString(players.getString(i))); }
            catch (IllegalArgumentException ignored) { }
        }
        audit.clear();
        ListTag entries = tag.getList("Audit", Tag.TAG_STRING);
        for (int i = Math.max(0, entries.size() - 64); i < entries.size(); i++) {
            String line = entries.getString(i);
            if (line.length() <= 256) audit.addLast(line);
        }
    }
}
