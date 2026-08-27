package com.malice.terminalcraft.network;

import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.PrincipalIdentity;
import com.malice.terminalcraft.operations.OperationsDeploymentService;
import com.malice.terminalcraft.operations.OperationsProject;
import com.malice.terminalcraft.operations.OperationsProjectCodec;
import com.malice.terminalcraft.operations.OperationsProjectValidator;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Headless round-trip and boundary checks for the Operations Project GUI transport. */
public final class OperationsProjectPacketTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final UUID STEP = UUID.fromString("00000000-0000-0000-0000-000000000904");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000905");

    private OperationsProjectPacketTest() {}

    public static void main(String[] args) {
        projectCodecAndSavePacketRoundTrip();
        previewResponseCarriesOnlyOpaquePlanIdentity();
        responseCollectionsAndTextRemainBounded();
        oversizedProjectNbtIsRejected();
        System.out.println("Operations project packet tests: OK");
    }

    private static void projectCodecAndSavePacketRoundTrip() {
        OperationsProject project = project(7);
        OperationsProject decoded = OperationsProjectCodec.decode(OperationsProjectCodec.encode(project));
        require(decoded.equals(project), "strict project codec preserves the complete typed document");

        OperationsProjectPackets.SaveRequest original = new OperationsProjectPackets.SaveRequest(
                7, OperationsProjectCodec.encode(project));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OperationsProjectPackets.SaveRequest.encode(original, buffer);
            OperationsProjectPackets.SaveRequest roundTrip = OperationsProjectPackets.SaveRequest.decode(buffer);
            require(roundTrip.expectedRevision() == 7, "save packet preserves optimistic revision");
            require(OperationsProjectCodec.decode(roundTrip.projectTag()).equals(project),
                    "save packet preserves the bounded project document");
        } finally {
            buffer.release();
        }
    }

    private static void previewResponseCarriesOnlyOpaquePlanIdentity() {
        OperationsProjectValidator.Issue issue = new OperationsProjectValidator.Issue(
                OperationsProjectValidator.Severity.WARNING, "discovery.truncated", "bounded warning",
                Optional.of(DEVICE), Optional.empty());
        OperationsProjectPackets.Response response = new OperationsProjectPackets.Response(
                OperationsProjectPackets.Action.PREVIEW, "ready", "preview ready", Optional.empty(),
                Optional.of(PLAN), 7, List.of(issue), false, List.of(), false);
        OperationsProjectPackets.Response decoded = roundTrip(response);
        require(decoded.planId().equals(Optional.of(PLAN)), "preview returns the opaque plan UUID");
        require(decoded.project().isEmpty(), "preview does not return a client-replayable plan document");
        require(decoded.projectRevision() == 7 && decoded.validation().equals(List.of(issue)),
                "preview returns revision and bounded validation details");
    }

    private static void responseCollectionsAndTextRemainBounded() {
        String oversized = "x".repeat(OperationsProjectPackets.MAX_DETAIL_LENGTH + 40);
        OperationsDeploymentService.StepOutcome outcome = new OperationsDeploymentService.StepOutcome(
                STEP, "Step", OperationsDeploymentService.Phase.APPLY, false, oversized);
        OperationsProjectPackets.Response response = new OperationsProjectPackets.Response(
                OperationsProjectPackets.Action.APPLY, "rolled_back", oversized, Optional.empty(),
                Optional.empty(), -1, List.of(), false, List.of(outcome), false);
        OperationsProjectPackets.Response decoded = roundTrip(response);
        require(decoded.message().length() == OperationsProjectPackets.MAX_MESSAGE_LENGTH,
                "response message is truncated to its wire budget");
        require(decoded.outcomes().get(0).detail().length() == OperationsProjectPackets.MAX_DETAIL_LENGTH,
                "device failure detail is truncated to its wire budget");
    }

    private static void oversizedProjectNbtIsRejected() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Oversized", new byte[OperationsProjectCodec.MAX_ENCODED_BYTES + 1]);
        require(!OperationsProjectCodec.isAdmissible(tag), "oversized project NBT is rejected");
        try {
            OperationsProjectCodec.decode(tag);
            throw new AssertionError("oversized project decode must fail");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static OperationsProjectPackets.Response roundTrip(OperationsProjectPackets.Response response) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OperationsProjectPackets.ResponsePacket.encode(
                    new OperationsProjectPackets.ResponsePacket(response), buffer);
            return OperationsProjectPackets.ResponsePacket.decode(buffer).response();
        } finally {
            buffer.release();
        }
    }

    private static OperationsProject project(long revision) {
        OperationsProject.DeviceBinding binding = new OperationsProject.DeviceBinding(DEVICE,
                "main-plc", true, "programmable_logic_controller", "terminalcraft", Set.of("plc"));
        OperationsProject.DeploymentStep step = new OperationsProject.DeploymentStep(STEP,
                "Load program", DEVICE, "program.set", List.of(DeviceValue.of("END\n")),
                new OperationsProject.Compensation("program.set", List.of(DeviceValue.of("END\n"))));
        return new OperationsProject(PROJECT, OperationsProject.CURRENT_SCHEMA_VERSION, revision,
                "Packet Test", PrincipalIdentity.player(OWNER, "Operator"),
                OperationsProject.Mode.EASY, OperationsProject.NetworkPlan.easyDefaults("packet-test"),
                List.of(binding), List.of(step), Set.of("terminalcraft"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
