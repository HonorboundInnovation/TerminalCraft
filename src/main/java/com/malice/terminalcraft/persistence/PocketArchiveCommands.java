package com.malice.terminalcraft.persistence;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Disk archive transfer is an explicit operator workflow, never arbitrary path access. */
@Mod.EventBusSubscriber(modid = "terminalcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketArchiveCommands {
    private PocketArchiveCommands() {}

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tcpocket")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("export").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    try {
                        var id = PocketArchives.exportHeld(player.serverLevel(), player.getMainHandItem());
                        context.getSource().sendSuccess(() -> Component.literal("Pocket exported: " + id
                                + ".dat in the world's terminalcraft-pocket-archives directory"), false);
                        return 1;
                    } catch (java.io.IOException failure) {
                        context.getSource().sendFailure(Component.literal("Pocket export: " + failure.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("recover-block").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(context, "pos");
                            try {
                                var result = PocketArchives.recoverBlockFiles(player.serverLevel(), player.getMainHandItem(), pos);
                                context.getSource().sendSuccess(() -> Component.literal("Recovered " + result.files()
                                        + " block files into a new pocket; skipped " + result.skipped()
                                        + " entries. Source block unchanged. Read " + result.reportPath() + " for details."), false);
                                return 1;
                            } catch (java.io.IOException failure) {
                                context.getSource().sendFailure(Component.literal("Block file recovery: " + failure.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("recover-files").then(Commands.argument("computer", UuidArgument.uuid())
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            try {
                                var result = PocketArchives.recoverFiles(player.serverLevel(), player.getMainHandItem(),
                                        UuidArgument.getUuid(context, "computer"));
                                context.getSource().sendSuccess(() -> Component.literal("Recovered " + result.files()
                                        + " files into a new pocket; skipped " + result.skipped() + " entries. Original unchanged. Read "
                                        + result.reportPath() + " in the new pocket for details."), false);
                                return 1;
                            } catch (java.io.IOException failure) {
                                context.getSource().sendFailure(Component.literal("Pocket file recovery: " + failure.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("recover-original").then(Commands.argument("computer", UuidArgument.uuid())
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            try {
                                PocketArchives.recoverOriginal(player.serverLevel(), player.getMainHandItem(),
                                        UuidArgument.getUuid(context, "computer"));
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Original migration snapshot copied to a new pocket identity; current source files unchanged"), false);
                                return 1;
                            } catch (java.io.IOException failure) {
                                context.getSource().sendFailure(Component.literal("Pocket recovery: " + failure.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("import").then(Commands.argument("archive", UuidArgument.uuid())
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            try {
                                PocketArchives.importHeld(player.serverLevel(), player.getMainHandItem(),
                                        UuidArgument.getUuid(context, "archive"));
                                context.getSource().sendSuccess(() -> Component.literal("Pocket imported into a new world identity"), false);
                                return 1;
                            } catch (java.io.IOException failure) {
                                context.getSource().sendFailure(Component.literal("Pocket import: " + failure.getMessage()));
                                return 0;
                            }
                        }))));
    }
}
