package com.malice.terminalcraft.network;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Bounded, menu-authorized completion; filesystem contents stay on the server. */
public final class RemoteCompletionPackets {
    public static final int REQUEST_ID = 8;
    public static final int RESPONSE_ID = 9;
    public static final int MAX_CANDIDATES = 128;
    private static final CommandSubmissionGuard GUARD = new CommandSubmissionGuard();
    private RemoteCompletionPackets() { }

    public static void register() {
        ModNetwork.CHANNEL.registerMessage(REQUEST_ID, Request.class, Request::encode, Request::decode,
                Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        ModNetwork.CHANNEL.registerMessage(RESPONSE_ID, Response.class, Response::encode, Response::decode,
                Response::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void request(int container, String line, int cursor) {
        ModNetwork.CHANNEL.sendToServer(new Request(container, line, cursor));
    }

    public record Request(int container, String line, int cursor) {
        public static void encode(Request value, FriendlyByteBuf buf) {
            buf.writeVarInt(value.container); buf.writeUtf(value.line, 4096); buf.writeVarInt(value.cursor);
        }
        public static Request decode(FriendlyByteBuf buf) { return new Request(buf.readVarInt(), buf.readUtf(4096), buf.readVarInt()); }
        public static void handle(Request value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (player == null || !(player.containerMenu instanceof TerminalMenu menu)
                        || menu.containerId != value.container || value.cursor < 0 || value.cursor > value.line.length()) return;
                if (GUARD.evaluate(player, player.level().getGameTime(), value.line, menu.stillValid(player),
                        4096, Config.maxCommandsPerSecond) != CommandSubmissionGuard.Decision.ACCEPT) return;
                var result = menu.getShell().complete(value.line, value.cursor);
                var candidates = result.candidates().stream().filter(s -> s.length() <= 1024).limit(MAX_CANDIDATES).toList();
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(value.container,
                        value.line, value.cursor, result.tokenStart(), result.tokenEnd(), candidates));
            });
            context.setPacketHandled(true);
        }
    }

    public record Response(int container, String line, int cursor, int start, int end, List<String> candidates) {
        public static void encode(Response value, FriendlyByteBuf buf) {
            buf.writeVarInt(value.container); buf.writeUtf(value.line, 4096); buf.writeVarInt(value.cursor);
            buf.writeVarInt(value.start); buf.writeVarInt(value.end); buf.writeVarInt(value.candidates.size());
            value.candidates.forEach(s -> buf.writeUtf(s, 1024));
        }
        public static Response decode(FriendlyByteBuf buf) {
            int container = buf.readVarInt(); String line = buf.readUtf(4096); int cursor = buf.readVarInt();
            int start = buf.readVarInt(); int end = buf.readVarInt(); int count = buf.readVarInt();
            if (count < 0 || count > MAX_CANDIDATES) throw new IllegalArgumentException("Completion count out of bounds");
            var candidates = new java.util.ArrayList<String>();
            for (int i = 0; i < count; i++) candidates.add(buf.readUtf(1024));
            return new Response(container, line, cursor, start, end, List.copyOf(candidates));
        }
        public static void handle(Response value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.malice.terminalcraft.client.ClientShellPackets.applyCompletion(value)));
            context.setPacketHandled(true);
        }
    }
}
