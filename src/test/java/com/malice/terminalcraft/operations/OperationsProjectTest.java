package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.PrincipalIdentity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Headless schema, authorization, optimistic-revision, and NBT coverage. */
public final class OperationsProjectTest {
    private static final PrincipalIdentity OWNER = PrincipalIdentity.player(
            UUID.fromString("00000000-0000-0000-0000-000000000101"), "Builder");
    private static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private OperationsProjectTest() {}

    public static void main(String[] args) {
        OperationsProject draft = project(0);
        DeviceCallContext owner = new DeviceCallContext(OWNER,
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        DeviceCallContext intruder = DeviceCallContext.player(
                UUID.fromString("00000000-0000-0000-0000-000000000102"), "Intruder",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        OperationsProjectSavedData data = new OperationsProjectSavedData();

        OperationsProjectSavedData.Result created = data.store(owner, draft, 0);
        require(created.status() == OperationsProjectSavedData.Status.STORED
                        && created.project().orElseThrow().revision() == 1,
                "new projects receive server-owned revision one");
        OperationsProject stored = created.project().orElseThrow();
        require(data.store(intruder, stored, 1).status()
                        == OperationsProjectSavedData.Status.PERMISSION_DENIED,
                "project ownership is authoritative");
        require(data.store(owner, draft, 0).status() == OperationsProjectSavedData.Status.CONFLICT,
                "stale revisions are rejected");

        OperationsProjectSavedData.Result updated = data.store(owner, stored, 1);
        require(updated.project().orElseThrow().revision() == 2,
                "matching optimistic revision advances once");
        CompoundTag saved = data.save(new CompoundTag());
        OperationsProjectSavedData restored = OperationsProjectSavedData.load(saved);
        OperationsProject roundTrip = restored.project(owner, draft.projectId()).orElseThrow();
        require(roundTrip.equals(updated.project().orElseThrow()),
                "all typed project fields survive an NBT round trip");

        CompoundTag corrupt = saved.copy();
        ListTag projects = corrupt.getList("Projects", net.minecraft.nbt.Tag.TAG_COMPOUND);
        CompoundTag malformed = new CompoundTag();
        malformed.putString("Name", "missing identity");
        projects.add(0, malformed);
        corrupt.put("Projects", projects);
        OperationsProjectSavedData recovered = OperationsProjectSavedData.load(corrupt);
        require(recovered.project(owner, draft.projectId()).isPresent(),
                "one malformed project cannot prevent valid projects loading");

        require(recovered.delete(owner, draft.projectId(), 2).status()
                        == OperationsProjectSavedData.Status.DELETED,
                "owner can delete the exact current revision");
        System.out.println("Operations project tests: OK");
    }

    static OperationsProject project(long revision) {
        OperationsProject.DeviceBinding binding = new OperationsProject.DeviceBinding(DEVICE,
                "main-plc", true, "programmable_logic_controller", "terminalcraft",
                Set.of("plc", "remote_programming"));
        DeviceValue metadata = DeviceValue.map(Map.of(
                "template", DeviceValue.of("tank-control"),
                "channels", DeviceValue.list(List.of(DeviceValue.of(3), DeviceValue.of(7)))));
        OperationsProject.DeploymentStep step = new OperationsProject.DeploymentStep(
                UUID.fromString("00000000-0000-0000-0000-000000000301"), "Load PLC program", DEVICE,
                "program.set", List.of(DeviceValue.of("SCAN 1\nEND\n"), metadata),
                Optional.of(new OperationsProject.Compensation("program.set",
                        List.of(DeviceValue.of("END\n"), metadata))));
        return new OperationsProject(UUID.fromString("00000000-0000-0000-0000-000000000401"),
                OperationsProject.CURRENT_SCHEMA_VERSION, revision, "Factory Controls", OWNER,
                OperationsProject.Mode.EASY, OperationsProject.NetworkPlan.easyDefaults("factory-lan"),
                List.of(binding), List.of(step), Set.of("terminalcraft"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
