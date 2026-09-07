package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceAuthorization;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-global bounded project store with owner checks and optimistic revisions. */
public final class OperationsProjectSavedData extends SavedData {
    public static final String FILE_ID = "terminalcraft_operations_projects";
    public static final String ADMIN_PERMISSION = "operations.admin";
    public static final int MAX_PROJECTS = 64;
    public static final int MAX_ENUMERATION = 64;

    private final Map<UUID, OperationsProject> projects = new LinkedHashMap<>();

    public enum Status { STORED, DELETED, NOT_FOUND, CONFLICT, PERMISSION_DENIED, CAPACITY_EXCEEDED }

    public record Result(Status status, String message, Optional<OperationsProject> project) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            message = Objects.requireNonNull(message, "message");
            project = Objects.requireNonNull(project, "project");
        }

        public boolean success() { return status == Status.STORED || status == Status.DELETED; }
    }

    public static OperationsProjectSavedData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(
                OperationsProjectSavedData::load, OperationsProjectSavedData::new, FILE_ID);
    }

    public synchronized Result store(DeviceCallContext context, OperationsProject project,
                                     long expectedRevision) {
        Objects.requireNonNull(project, "project");
        if (!authorized(context, project.owner())) return result(Status.PERMISSION_DENIED, "project owner required");
        OperationsProject existing = projects.get(project.projectId());
        if (existing == null) {
            if (expectedRevision != 0 || project.revision() != 0) {
                return result(Status.CONFLICT, "new projects must start at revision zero");
            }
            if (projects.size() >= MAX_PROJECTS) {
                return result(Status.CAPACITY_EXCEEDED, "operations project capacity reached");
            }
        } else {
            if (!existing.owner().equals(project.owner())) {
                return result(Status.PERMISSION_DENIED, "project ownership cannot be changed");
            }
            if (expectedRevision != existing.revision() || project.revision() != expectedRevision) {
                return new Result(Status.CONFLICT, "project revision changed", Optional.of(existing));
            }
        }
        OperationsProject stored = project.withRevision(expectedRevision + 1);
        projects.put(stored.projectId(), stored);
        setDirty();
        return new Result(Status.STORED, "project stored", Optional.of(stored));
    }

    public synchronized Result delete(DeviceCallContext context, UUID projectId, long expectedRevision) {
        OperationsProject existing = projects.get(Objects.requireNonNull(projectId, "projectId"));
        if (existing == null) return result(Status.NOT_FOUND, "project not found");
        if (!authorized(context, existing.owner())) return result(Status.PERMISSION_DENIED, "project owner required");
        if (expectedRevision != existing.revision()) {
            return new Result(Status.CONFLICT, "project revision changed", Optional.of(existing));
        }
        projects.remove(projectId);
        setDirty();
        return new Result(Status.DELETED, "project deleted", Optional.of(existing));
    }

    public synchronized Optional<OperationsProject> project(DeviceCallContext context, UUID projectId) {
        OperationsProject project = projects.get(Objects.requireNonNull(projectId, "projectId"));
        return project != null && authorized(context, project.owner()) ? Optional.of(project) : Optional.empty();
    }

    public synchronized List<OperationsProject> projects(DeviceCallContext context, int limit) {
        int bounded = Math.max(0, Math.min(limit, MAX_ENUMERATION));
        return projects.values().stream().filter(project -> authorized(context, project.owner()))
                .sorted(Comparator.comparing(OperationsProject::name)
                        .thenComparing(project -> project.projectId().toString()))
                .limit(bounded).toList();
    }

    private static boolean authorized(DeviceCallContext context,
                                      com.malice.terminalcraft.device.PrincipalIdentity owner) {
        return DeviceAuthorization.owns(context, owner)
                || DeviceAuthorization.allows(context, ADMIN_PERMISSION);
    }

    private static Result result(Status status, String message) {
        return new Result(status, message, Optional.empty());
    }

    @Override
    public synchronized CompoundTag save(CompoundTag root) {
        PersistedDataVersions.stampCurrent(root);
        ListTag saved = new ListTag();
        projects.values().stream().sorted(Comparator.comparing(project -> project.projectId().toString()))
                .forEach(project -> saved.add(OperationsProjectNbt.save(project)));
        root.put("Projects", saved);
        return root;
    }

    static OperationsProjectSavedData load(CompoundTag root) {
        OperationsProjectSavedData data = new OperationsProjectSavedData();
        ListTag saved = root.getList("Projects", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size() && data.projects.size() < MAX_PROJECTS; index++) {
            try {
                OperationsProject project = OperationsProjectNbt.load(saved.getCompound(index));
                data.projects.putIfAbsent(project.projectId(), project);
            } catch (RuntimeException ignored) {
                // One corrupt or future-version project must not prevent the remaining projects loading.
            }
        }
        return data;
    }
}
