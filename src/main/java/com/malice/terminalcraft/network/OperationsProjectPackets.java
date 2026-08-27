package com.malice.terminalcraft.network;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceRegistry;
import com.malice.terminalcraft.operations.OperationsDeploymentService;
import com.malice.terminalcraft.operations.OperationsProject;
import com.malice.terminalcraft.operations.OperationsProjectCodec;
import com.malice.terminalcraft.operations.OperationsProjectRuntime;
import com.malice.terminalcraft.operations.OperationsProjectSavedData;
import com.malice.terminalcraft.operations.OperationsProjectValidator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Bounded GUI transport for the server-authoritative Operations Project runtime. */
public final class OperationsProjectPackets {
    public static final int EDIT_PACKET_ID = 9;
    public static final int SAVE_PACKET_ID = 10;
    public static final int PREVIEW_PACKET_ID = 11;
    public static final int APPLY_PACKET_ID = 12;
    public static final int RESPONSE_PACKET_ID = 13;

    public static final int MAX_MESSAGE_LENGTH = 512;
    public static final int MAX_DETAIL_LENGTH = 512;
    public static final int MAX_VALIDATION_ISSUES = 512;
    public static final int MAX_STEP_OUTCOMES = OperationsProject.MAX_DEPLOYMENT_STEPS * 2;

    private static final CommandSubmissionGuard REQUEST_GUARD = new CommandSubmissionGuard();

    private OperationsProjectPackets() {}

    public enum Action { EDIT, SAVE, PREVIEW, APPLY }

    /** Client-facing response. The retained deployment plan itself never crosses this boundary. */
    public record Response(Action action, String status, String message,
                           Optional<OperationsProject> project, Optional<UUID> planId,
                           long projectRevision, List<OperationsProjectValidator.Issue> validation,
                           boolean validationTruncated,
                           List<OperationsDeploymentService.StepOutcome> outcomes,
                           boolean outcomesTruncated) {
        public Response {
            action = Objects.requireNonNull(action, "action");
            status = identifier(status);
            message = bounded(message, MAX_MESSAGE_LENGTH);
            project = Objects.requireNonNull(project, "project");
            planId = Objects.requireNonNull(planId, "planId");
            if (projectRevision < -1) throw new IllegalArgumentException("invalid project revision");
            if (project.isPresent() && action != Action.EDIT && action != Action.SAVE) {
                throw new IllegalArgumentException("project document is not valid for this response action");
            }
            if (planId.isPresent() && (action != Action.PREVIEW || !status.equals("ready"))) {
                throw new IllegalArgumentException("opaque plan ID is not valid for this response");
            }
            validation = List.copyOf(Objects.requireNonNull(validation, "validation"));
            outcomes = Objects.requireNonNull(outcomes, "outcomes").stream()
                    .map(outcome -> new OperationsDeploymentService.StepOutcome(outcome.stepId(),
                            bounded(outcome.label(), OperationsProject.MAX_NAME_LENGTH), outcome.phase(),
                            outcome.success(), bounded(outcome.detail(), MAX_DETAIL_LENGTH)))
                    .toList();
            if (validation.size() > MAX_VALIDATION_ISSUES) {
                validation = List.copyOf(validation.subList(0, MAX_VALIDATION_ISSUES));
                validationTruncated = true;
            }
            if (outcomes.size() > MAX_STEP_OUTCOMES) {
                outcomes = List.copyOf(outcomes.subList(0, MAX_STEP_OUTCOMES));
                outcomesTruncated = true;
            }
        }

        public boolean success() {
            return status.equals("found") || status.equals("stored") || status.equals("ready")
                    || status.equals("applied");
        }
    }

    public static void register(SimpleChannel channel) {
        Objects.requireNonNull(channel, "channel");
        channel.registerMessage(EDIT_PACKET_ID, EditRequest.class, EditRequest::encode,
                EditRequest::decode, EditRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(SAVE_PACKET_ID, SaveRequest.class, SaveRequest::encode,
                SaveRequest::decode, SaveRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(PREVIEW_PACKET_ID, PreviewRequest.class, PreviewRequest::encode,
                PreviewRequest::decode, PreviewRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(APPLY_PACKET_ID, ApplyRequest.class, ApplyRequest::encode,
                ApplyRequest::decode, ApplyRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(RESPONSE_PACKET_ID, ResponsePacket.class, ResponsePacket::encode,
                ResponsePacket::decode, ResponsePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendEdit(UUID projectId) {
        ModNetwork.CHANNEL.sendToServer(new EditRequest(Objects.requireNonNull(projectId, "projectId")));
    }

    public static void sendSave(OperationsProject project, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("expected revision must not be negative");
        ModNetwork.CHANNEL.sendToServer(new SaveRequest(expectedRevision,
                OperationsProjectCodec.encode(project)));
    }

    public static void sendPreview(UUID projectId, int discoveryLimit) {
        requireDiscoveryLimit(discoveryLimit);
        ModNetwork.CHANNEL.sendToServer(new PreviewRequest(
                Objects.requireNonNull(projectId, "projectId"), discoveryLimit));
    }

    public static void sendApply(UUID opaquePlanId) {
        ModNetwork.CHANNEL.sendToServer(new ApplyRequest(
                Objects.requireNonNull(opaquePlanId, "opaquePlanId")));
    }

    public record EditRequest(UUID projectId) {
        public EditRequest { projectId = Objects.requireNonNull(projectId, "projectId"); }
        static void encode(EditRequest packet, FriendlyByteBuf buffer) { buffer.writeUUID(packet.projectId); }
        static EditRequest decode(FriendlyByteBuf buffer) { return new EditRequest(buffer.readUUID()); }
        static void handle(EditRequest packet, Supplier<NetworkEvent.Context> supplier) {
            handleServer(supplier, Action.EDIT, (player, context) -> {
                OperationsProjectRuntime.EditResult result = OperationsProjectRuntime.edit(
                        player.getServer(), context, packet.projectId);
                Optional<OperationsProject> project = result.project();
                return response(Action.EDIT, result.status(), result.message(), project,
                        Optional.empty(), project.map(OperationsProject::revision).orElse(-1L),
                        List.of(), List.of());
            });
        }
    }

    public record SaveRequest(long expectedRevision, CompoundTag projectTag) {
        public SaveRequest {
            if (expectedRevision < 0) throw new IllegalArgumentException("expected revision must not be negative");
            projectTag = Objects.requireNonNull(projectTag, "projectTag").copy();
        }
        static void encode(SaveRequest packet, FriendlyByteBuf buffer) {
            buffer.writeVarLong(packet.expectedRevision);
            buffer.writeNbt(packet.projectTag);
        }
        static SaveRequest decode(FriendlyByteBuf buffer) {
            long expectedRevision = buffer.readVarLong();
            CompoundTag tag = buffer.readNbt();
            return new SaveRequest(expectedRevision, requireTag(tag));
        }
        static void handle(SaveRequest packet, Supplier<NetworkEvent.Context> supplier) {
            handleServer(supplier, Action.SAVE, (player, context) -> {
                try {
                    OperationsProject project = OperationsProjectCodec.decode(packet.projectTag);
                    OperationsProjectSavedData.Result result = OperationsProjectRuntime.save(
                            player.getServer(), context, project, packet.expectedRevision);
                    return response(Action.SAVE, result.status(), result.message(), result.project(),
                            Optional.empty(), result.project().map(OperationsProject::revision).orElse(-1L),
                            List.of(), List.of());
                } catch (RuntimeException invalid) {
                    return failure(Action.SAVE, "invalid", "project document is invalid or oversized");
                }
            });
        }
    }

    public record PreviewRequest(UUID projectId, int discoveryLimit) {
        public PreviewRequest {
            projectId = Objects.requireNonNull(projectId, "projectId");
            requireDiscoveryLimit(discoveryLimit);
        }
        static void encode(PreviewRequest packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.projectId);
            buffer.writeVarInt(packet.discoveryLimit);
        }
        static PreviewRequest decode(FriendlyByteBuf buffer) {
            return new PreviewRequest(buffer.readUUID(), buffer.readVarInt());
        }
        static void handle(PreviewRequest packet, Supplier<NetworkEvent.Context> supplier) {
            handleServer(supplier, Action.PREVIEW, (player, context) -> {
                OperationsProjectRuntime.PreviewResult result = OperationsProjectRuntime.preview(
                        player.getServer(), context, packet.projectId, packet.discoveryLimit);
                Optional<OperationsDeploymentService.Plan> plan = result.plan();
                Optional<UUID> opaqueId = result.status() == OperationsProjectRuntime.PreviewStatus.READY
                        ? plan.map(OperationsDeploymentService.Plan::planId) : Optional.empty();
                return response(Action.PREVIEW, result.status(), result.message(), Optional.empty(),
                        opaqueId, plan.map(OperationsDeploymentService.Plan::projectRevision).orElse(-1L),
                        plan.map(value -> value.validation().issues()).orElse(List.of()), List.of());
            });
        }
    }

    public record ApplyRequest(UUID planId) {
        public ApplyRequest { planId = Objects.requireNonNull(planId, "planId"); }
        static void encode(ApplyRequest packet, FriendlyByteBuf buffer) { buffer.writeUUID(packet.planId); }
        static ApplyRequest decode(FriendlyByteBuf buffer) { return new ApplyRequest(buffer.readUUID()); }
        static void handle(ApplyRequest packet, Supplier<NetworkEvent.Context> supplier) {
            handleServer(supplier, Action.APPLY, (player, context) -> {
                OperationsDeploymentService.Result result = OperationsProjectRuntime.apply(
                        player.getServer(), context, packet.planId);
                return response(Action.APPLY, result.status(), result.message(), Optional.empty(),
                        Optional.empty(), -1, result.validation().issues(), result.outcomes());
            });
        }
    }

    public record ResponsePacket(Response response) {
        public ResponsePacket { response = Objects.requireNonNull(response, "response"); }

        static void encode(ResponsePacket packet, FriendlyByteBuf buffer) {
            Response response = packet.response;
            buffer.writeEnum(response.action());
            buffer.writeUtf(response.status(), 64);
            buffer.writeUtf(response.message(), MAX_MESSAGE_LENGTH);
            buffer.writeBoolean(response.project().isPresent());
            if (response.project().isPresent()) buffer.writeNbt(OperationsProjectCodec.encode(response.project().get()));
            buffer.writeBoolean(response.planId().isPresent());
            response.planId().ifPresent(buffer::writeUUID);
            buffer.writeLong(response.projectRevision());
            buffer.writeBoolean(response.validationTruncated());
            buffer.writeVarInt(response.validation().size());
            response.validation().forEach(issue -> writeIssue(buffer, issue));
            buffer.writeBoolean(response.outcomesTruncated());
            buffer.writeVarInt(response.outcomes().size());
            response.outcomes().forEach(outcome -> writeOutcome(buffer, outcome));
        }

        static ResponsePacket decode(FriendlyByteBuf buffer) {
            Action action = buffer.readEnum(Action.class);
            String status = buffer.readUtf(64);
            String message = buffer.readUtf(MAX_MESSAGE_LENGTH);
            Optional<OperationsProject> project = buffer.readBoolean()
                    ? Optional.of(OperationsProjectCodec.decode(requireTag(buffer.readNbt()))) : Optional.empty();
            Optional<UUID> planId = buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty();
            long revision = buffer.readLong();
            boolean validationTruncated = buffer.readBoolean();
            int issueCount = boundedCount(buffer.readVarInt(), MAX_VALIDATION_ISSUES, "validation issues");
            List<OperationsProjectValidator.Issue> issues = new ArrayList<>(issueCount);
            for (int index = 0; index < issueCount; index++) issues.add(readIssue(buffer));
            boolean outcomesTruncated = buffer.readBoolean();
            int outcomeCount = boundedCount(buffer.readVarInt(), MAX_STEP_OUTCOMES, "step outcomes");
            List<OperationsDeploymentService.StepOutcome> outcomes = new ArrayList<>(outcomeCount);
            for (int index = 0; index < outcomeCount; index++) outcomes.add(readOutcome(buffer));
            return new ResponsePacket(new Response(action, status, message, project, planId, revision,
                    issues, validationTruncated, outcomes, outcomesTruncated));
        }

        static void handle(ResponsePacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.malice.terminalcraft.client.ClientOperationsPackets.apply(packet.response)));
            context.setPacketHandled(true);
        }
    }

    @FunctionalInterface
    private interface ServerAction {
        Response execute(ServerPlayer player, DeviceCallContext context);
    }

    private static void handleServer(Supplier<NetworkEvent.Context> supplier, Action action,
                                     ServerAction operation) {
        NetworkEvent.Context network = supplier.get();
        network.enqueueWork(() -> {
            ServerPlayer player = network.getSender();
            if (player == null || player.getServer() == null) return;
            CommandSubmissionGuard.Decision admitted = REQUEST_GUARD.evaluate(player,
                    player.level().getGameTime(), "", true, 0, Config.maxCommandsPerSecond);
            if (admitted != CommandSubmissionGuard.Decision.ACCEPT) {
                send(player, failure(action, admitted == CommandSubmissionGuard.Decision.RATE_LIMITED
                        ? "rate_limited" : "rejected", "operations request rejected"));
                return;
            }
            DeviceCallContext context = DeviceCallContext.player(player.getUUID(),
                    player.getGameProfile().getName(),
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
            try {
                send(player, operation.execute(player, context));
            } catch (RuntimeException exception) {
                send(player, failure(action, "rejected", "operations request could not be completed"));
            }
        });
        network.setPacketHandled(true);
    }

    private static void send(ServerPlayer player, Response response) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ResponsePacket(response));
    }

    private static Response response(Action action, Enum<?> status, String message,
                                     Optional<OperationsProject> project, Optional<UUID> planId,
                                     long revision, List<OperationsProjectValidator.Issue> validation,
                                     List<OperationsDeploymentService.StepOutcome> outcomes) {
        return new Response(action, status.name().toLowerCase(Locale.ROOT), message, project, planId,
                revision, validation, false, outcomes, false);
    }

    private static Response failure(Action action, String status, String message) {
        return new Response(action, status, message, Optional.empty(), Optional.empty(), -1,
                List.of(), false, List.of(), false);
    }

    private static void writeIssue(FriendlyByteBuf buffer, OperationsProjectValidator.Issue issue) {
        buffer.writeEnum(issue.severity());
        buffer.writeUtf(issue.code(), 64);
        buffer.writeUtf(bounded(issue.message(), 256), 256);
        buffer.writeBoolean(issue.deviceId().isPresent());
        issue.deviceId().ifPresent(buffer::writeUUID);
        buffer.writeBoolean(issue.stepId().isPresent());
        issue.stepId().ifPresent(buffer::writeUUID);
    }

    private static OperationsProjectValidator.Issue readIssue(FriendlyByteBuf buffer) {
        OperationsProjectValidator.Severity severity = buffer.readEnum(OperationsProjectValidator.Severity.class);
        String code = buffer.readUtf(64);
        String message = buffer.readUtf(256);
        Optional<UUID> device = buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty();
        Optional<UUID> step = buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty();
        return new OperationsProjectValidator.Issue(severity, code, message, device, step);
    }

    private static void writeOutcome(FriendlyByteBuf buffer, OperationsDeploymentService.StepOutcome outcome) {
        buffer.writeUUID(outcome.stepId());
        buffer.writeUtf(bounded(outcome.label(), OperationsProject.MAX_NAME_LENGTH),
                OperationsProject.MAX_NAME_LENGTH);
        buffer.writeEnum(outcome.phase());
        buffer.writeBoolean(outcome.success());
        buffer.writeUtf(bounded(outcome.detail(), MAX_DETAIL_LENGTH), MAX_DETAIL_LENGTH);
    }

    private static OperationsDeploymentService.StepOutcome readOutcome(FriendlyByteBuf buffer) {
        return new OperationsDeploymentService.StepOutcome(buffer.readUUID(),
                buffer.readUtf(OperationsProject.MAX_NAME_LENGTH),
                buffer.readEnum(OperationsDeploymentService.Phase.class), buffer.readBoolean(),
                buffer.readUtf(MAX_DETAIL_LENGTH));
    }

    private static CompoundTag requireTag(CompoundTag tag) {
        if (tag == null) throw new IllegalArgumentException("project tag is missing");
        return tag;
    }

    private static int boundedCount(int count, int maximum, String label) {
        if (count < 0 || count > maximum) throw new IllegalArgumentException(label + " exceed packet bounds");
        return count;
    }

    private static void requireDiscoveryLimit(int limit) {
        if (limit < 1 || limit > DeviceRegistry.MAX_ENUMERATION_RESULTS) {
            throw new IllegalArgumentException("discovery limit is outside bounds");
        }
    }

    private static String identifier(String value) {
        String result = Objects.requireNonNull(value, "status").trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("invalid status");
        return result;
    }

    private static String bounded(String value, int maximum) {
        String result = Objects.requireNonNullElse(value, "");
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }
}
