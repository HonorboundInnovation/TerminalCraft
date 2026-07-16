package com.malice.terminalcraft.network;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.TerminalCraftMod;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.PocketShellComputer;
import com.malice.terminalcraft.shell.ShellComputer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Networking for terminal command submission and shell state sync.
 */
public final class ModNetwork {
    private static final String PROTOCOL = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TerminalCraftMod.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    static final int RUN_COMMAND_PACKET_ID = 0;
    static final int SHELL_SYNC_PACKET_ID = 1;
    static final int EDITOR_ACTION_PACKET_ID = 2;
    static final int EDITOR_RESULT_PACKET_ID = 3;

    private static final int MAX_COMMAND_PACKET_LENGTH = 4096;
    private static final CommandSubmissionGuard COMMAND_GUARD = new CommandSubmissionGuard();
    private static boolean registered;

    private ModNetwork() {}

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                RUN_COMMAND_PACKET_ID,
                RunCommandPacket.class,
                RunCommandPacket::encode,
                RunCommandPacket::decode,
                RunCommandPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                SHELL_SYNC_PACKET_ID,
                ShellSyncPacket.class,
                ShellSyncPacket::encode,
                ShellSyncPacket::decode,
                ShellSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                EDITOR_ACTION_PACKET_ID,
                EditorActionPacket.class,
                EditorActionPacket::encode,
                EditorActionPacket::decode,
                EditorActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                EDITOR_RESULT_PACKET_ID,
                EditorResultPacket.class,
                EditorResultPacket::encode,
                EditorResultPacket::decode,
                EditorResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        registered = true;
    }

    public static void sendCommand(int containerId, String command) {
        CHANNEL.sendToServer(new RunCommandPacket(containerId, command == null ? "" : command));
    }

    public static void sendEditorSave(int containerId, String content, boolean closeAfterSave) {
        CHANNEL.sendToServer(new EditorActionPacket(containerId,
                closeAfterSave ? EditorAction.SAVE_AND_CLOSE : EditorAction.SAVE,
                content == null ? "" : content));
    }

    public static void sendEditorClose(int containerId) {
        CHANNEL.sendToServer(new EditorActionPacket(containerId, EditorAction.CLOSE, ""));
    }

    public static boolean syncShellTo(ServerPlayer player, BashShell shell) {
        CompoundTag snapshot = shell.save();
        if (!ShellSyncPolicy.isAdmissible(snapshot)) return false;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShellSyncPacket(player.containerMenu.containerId, snapshot));
        return true;
    }

    public static final class RunCommandPacket {
        private final int containerId;
        private final String command;

        public RunCommandPacket(int containerId, String command) {
            this.containerId = containerId;
            this.command = command;
        }

        public static void encode(RunCommandPacket pkt, FriendlyByteBuf buf) {
            buf.writeVarInt(pkt.containerId);
            buf.writeUtf(pkt.command, MAX_COMMAND_PACKET_LENGTH);
        }

        public static RunCommandPacket decode(FriendlyByteBuf buf) {
            return new RunCommandPacket(buf.readVarInt(), buf.readUtf(MAX_COMMAND_PACKET_LENGTH));
        }

        public static void handle(RunCommandPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) {
                    return;
                }
                if (!(player.containerMenu instanceof TerminalMenu menu)
                        || menu.containerId != pkt.containerId) {
                    return;
                }

                CommandSubmissionGuard.Decision decision = COMMAND_GUARD.evaluate(
                        player,
                        player.level().getGameTime(),
                        pkt.command,
                        menu.stillValid(player),
                        Config.maxCommandLength,
                        Config.maxCommandsPerSecond
                );
                if (decision != CommandSubmissionGuard.Decision.ACCEPT) {
                    return;
                }

                DeviceCallContext deviceContext = DeviceCallContext.player(
                        player.getUUID(), player.getGameProfile().getName(),
                        Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
                menu.submitCommand(pkt.command, deviceContext);
                ShellComputer computer = menu.getComputer();
                if (computer instanceof PocketShellComputer) {
                    // Pocket state lives on the item stack; push shell NBT to client GUI.
                    syncShellTo(player, computer.getShell());
                } else if (computer.getLevel() != null) {
                    computer.getLevel().sendBlockUpdated(
                            computer.getBlockPos(),
                            computer.getBlockState(),
                            computer.getBlockState(),
                            3
                    );
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    private enum EditorAction { SAVE, SAVE_AND_CLOSE, CLOSE }

    /** Server-authoritative mutation of the GUI editor session bound to the open menu. */
    public static final class EditorActionPacket {
        private static final int MAX_EDITOR_PACKET_LENGTH = 64 * 1024;
        private final int containerId;
        private final EditorAction action;
        private final String content;

        private EditorActionPacket(int containerId, EditorAction action, String content) {
            this.containerId = containerId;
            this.action = action;
            this.content = content;
        }

        public static void encode(EditorActionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            buffer.writeEnum(packet.action);
            buffer.writeUtf(packet.content, MAX_EDITOR_PACKET_LENGTH);
        }

        public static EditorActionPacket decode(FriendlyByteBuf buffer) {
            return new EditorActionPacket(buffer.readVarInt(), buffer.readEnum(EditorAction.class),
                    buffer.readUtf(MAX_EDITOR_PACKET_LENGTH));
        }

        public static void handle(EditorActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !(player.containerMenu instanceof TerminalMenu menu)
                        || menu.containerId != packet.containerId) return;
                BashShell shell = menu.getShell();
                CommandSubmissionGuard.Decision decision = COMMAND_GUARD.evaluateEditor(
                        player, player.level().getGameTime(), packet.content,
                        menu.stillValid(player), shell.isEditorActive(),
                        packet.action == EditorAction.CLOSE, MAX_EDITOR_PACKET_LENGTH,
                        Config.maxCommandsPerSecond);
                if (decision != CommandSubmissionGuard.Decision.ACCEPT) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new EditorResultPacket(menu.containerId, false, false,
                                    decision == CommandSubmissionGuard.Decision.RATE_LIMITED
                                            ? "Rate limited; retry shortly" : "Editor request rejected"));
                    return;
                }
                boolean success;
                if (packet.action == EditorAction.CLOSE) success = shell.closeEditorFromGui();
                else success = shell.saveEditorFromGui(packet.content, packet.action == EditorAction.SAVE_AND_CLOSE);
                boolean closed = success && packet.action != EditorAction.SAVE;
                String resultMessage = success
                        ? (packet.action == EditorAction.CLOSE ? "Changes discarded" : "Saved")
                        : (packet.action == EditorAction.CLOSE ? "Could not close editor" : "Save failed");
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new EditorResultPacket(menu.containerId, success, closed, resultMessage));
                if (!success) return;
                menu.getComputer().markShellChanged();
                if (menu.getComputer() instanceof PocketShellComputer) {
                    syncShellTo(player, shell);
                } else if (menu.getComputer().getLevel() != null) {
                    menu.getComputer().getLevel().sendBlockUpdated(menu.getComputer().getBlockPos(),
                            menu.getComputer().getBlockState(), menu.getComputer().getBlockState(), 3);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public static final class EditorResultPacket {
        private final int containerId;
        private final boolean success;
        private final boolean closed;
        private final String message;

        private EditorResultPacket(int containerId, boolean success, boolean closed, String message) {
            this.containerId = containerId;
            this.success = success;
            this.closed = closed;
            this.message = message;
        }

        public static void encode(EditorResultPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            buffer.writeBoolean(packet.success);
            buffer.writeBoolean(packet.closed);
            buffer.writeUtf(packet.message, 256);
        }

        public static EditorResultPacket decode(FriendlyByteBuf buffer) {
            return new EditorResultPacket(buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readUtf(256));
        }

        public static void handle(EditorResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.malice.terminalcraft.client.ClientShellPackets.applyEditorResult(
                            packet.containerId, packet.success, packet.closed, packet.message)));
            context.setPacketHandled(true);
        }
    }

    /**
     * Pushes authoritative shell state to the client open terminal screen.
     * Used for pocket terminals (no block entity sync path).
     */
    public static final class ShellSyncPacket {
        private final int containerId;
        private final CompoundTag shellTag;

        public ShellSyncPacket(int containerId, CompoundTag shellTag) {
            this.containerId = containerId;
            this.shellTag = shellTag == null ? new CompoundTag() : shellTag;
        }

        public static void encode(ShellSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeVarInt(pkt.containerId);
            buf.writeNbt(pkt.shellTag);
        }

        public static ShellSyncPacket decode(FriendlyByteBuf buf) {
            int containerId = buf.readVarInt();
            CompoundTag tag = buf.readNbt();
            return new ShellSyncPacket(containerId, tag == null ? new CompoundTag() : tag);
        }

        public static void handle(ShellSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            CompoundTag tag = pkt.shellTag;
            ctx.enqueueWork(() -> {
                if (!ShellSyncPolicy.isAdmissible(tag)) return;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.malice.terminalcraft.client.ClientShellPackets.applyShell(
                                pkt.containerId, tag));
            });
            ctx.setPacketHandled(true);
        }
    }
}
