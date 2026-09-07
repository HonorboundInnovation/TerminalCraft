package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.item.FloppyDiskItem;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;

/** Real registered computers and drives: no mock media identity or write implementation. */
@GameTestHolder("terminalcraft")
public final class CoreComputerGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rackClientUpdatesExcludeFullPrivateJobQueueButDiskRetainsIt(GameTestHelper helper) {
        var pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, ModRegistries.SERVER_RACK_BLOCK.get());
        var rack = (ServerRackBlockEntity) helper.getBlockEntity(pos);
        helper.assertTrue(rack.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false), "compute blade installed");
        var emptyView = rack.getUpdateTag();
        String command = "echo private-job-" + "x".repeat(1007);
        for (int owner = 0; owner < 4; owner++) {
            var context = com.malice.terminalcraft.device.DeviceCallContext.player(java.util.UUID.randomUUID(), "private-owner-" + owner,
                    java.util.Set.of(com.malice.terminalcraft.device.DeviceCallContext.READ, com.malice.terminalcraft.device.DeviceCallContext.WRITE));
            for (int i = 0; i < 32; i++) helper.assertTrue(!rack.serverSubmit(context, command).isBlank(), "full private job queue fixture");
        }
        var saved = rack.saveWithoutMetadata();
        helper.assertTrue(saved.getCompound("Scheduler").getList("Jobs", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 128, "all jobs remain in server persistence");
        var view = rack.getUpdateTag();
        helper.assertTrue(!view.contains("Scheduler") && view.equals(emptyView), "routine client snapshot is independent of job count and private context");
        helper.assertTrue(!rack.getUpdatePacket().getTag().contains("Scheduler"), "incremental packet omits scheduler too");
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeNbt(view);
            helper.assertTrue(buffer.readableBytes() < 20_000, "full queue does not enlarge display packet");
        } finally { buffer.release(); }
        var copy = new ServerRackBlockEntity(helper.absolutePos(pos), rack.getBlockState());
        copy.handleUpdateTag(saved);
        helper.assertTrue(copy.serverQueuedJobs() == 0, "display route ignores even an included scheduler");
        copy.load(saved);
        helper.assertTrue(copy.serverQueuedJobs() == 128
                && saved.getCompound("Scheduler").equals(copy.saveWithoutMetadata().getCompound("Scheduler")), "ordinary saved-data load retains exact full scheduler");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wrongTypeShellPayloadsSurvivePlacedComputerSaveAndRestore(GameTestHelper helper) throws Exception {
        var pos = new BlockPos(1, 2, 1);
        var list = new net.minecraft.nbt.ListTag();
        list.add(net.minecraft.nbt.StringTag.valueOf("raw list contents"));
        for (var block : java.util.List.of(ModRegistries.TERMINAL_BLOCK.get(), ModRegistries.TURTLE_BLOCK.get(), ModRegistries.SERVER_RACK_BLOCK.get())) {
            helper.setBlock(pos, block);
            var entity = helper.getBlockEntity(pos);
            var computer = (com.malice.terminalcraft.shell.ShellComputer) entity;
            var valid = entity.saveWithoutMetadata();
            for (net.minecraft.nbt.Tag malformed : java.util.List.of(net.minecraft.nbt.StringTag.valueOf("raw malformed shell"), net.minecraft.nbt.IntTag.valueOf(123), list)) {
                var data = valid.copy();
                data.put("Shell", malformed.copy());
                entity.load(data);
                helper.assertTrue(computer.getShell().isFileRecoveryRequired() && computer.getShell().runScriptText("echo unsafe") != 0, "wrong-type shell cannot execute");
                var saved = entity.saveWithoutMetadata();
                helper.assertTrue(malformed.equals(saved.get("Shell")), "exact outer tag type and value retained");
                entity.load(saved);
                helper.assertTrue(malformed.equals(entity.saveWithoutMetadata().get("Shell")), "malformed payload survives repeat load/save");
                var update = entity.getUpdateTag().getCompound("Shell");
                helper.assertTrue(!update.contains("InvalidShellPayload") && !update.contains("Vfs"), "raw malformed payload excluded from client view");
                ItemStack target = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
                var before = target.save(new net.minecraft.nbt.CompoundTag());
                boolean rejected = false;
                try { com.malice.terminalcraft.persistence.PocketArchives.recoverBlockFiles(helper.getLevel(), target, helper.absolutePos(pos)); }
                catch (java.io.IOException expected) { rejected = true; }
                helper.assertTrue(rejected && before.equals(target.save(new net.minecraft.nbt.CompoundTag())), "no invented recovery files from invalid shell");
                entity.load(valid);
                helper.assertTrue(!computer.getShell().isFileRecoveryRequired()
                        && entity.saveWithoutMetadata().contains("Shell", net.minecraft.nbt.Tag.TAG_COMPOUND), "valid explicit restoration clears invalid payload");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingSavedFilesystemIsProtectedButClientViewsStillLoad(GameTestHelper helper) {
        var pos = new BlockPos(1, 2, 1);
        for (var block : java.util.List.of(ModRegistries.TERMINAL_BLOCK.get(), ModRegistries.TURTLE_BLOCK.get(), ModRegistries.SERVER_RACK_BLOCK.get())) {
            helper.setBlock(pos, block);
            var entity = helper.getBlockEntity(pos);
            var computer = (com.malice.terminalcraft.shell.ShellComputer) entity;
            var data = entity.saveWithoutMetadata();
            var shell = data.getCompound("Shell");
            shell.remove("Vfs");
            shell.putString("RecoveryClue", "retain the incomplete snapshot");
            var before = shell.copy();
            entity.load(data);
            helper.assertTrue(computer.getShell().isFileRecoveryRequired()
                    && before.equals(entity.saveWithoutMetadata().getCompound("Shell")), "saved shell missing filesystem is preserved, not replaced with defaults");
            var clientCopy = entity.getType().create(helper.absolutePos(pos), entity.getBlockState());
            helper.assertTrue(clientCopy != null, "client test instance created");
            clientCopy.handleUpdateTag(entity.getUpdateTag());
            var clientShell = ((com.malice.terminalcraft.shell.ShellComputer) clientCopy).getShell();
            helper.assertTrue(!clientShell.isFileRecoveryRequired() && clientShell.getOutputLines().stream().anyMatch(line -> line.contains("recovery required")), "display-only update loads warning without misclassifying omitted files");
            var packet = entity.getUpdatePacket();
            helper.assertTrue(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket, "block entity update packet");
            clientCopy.onDataPacket(null, (net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket) packet);
            helper.assertTrue(!clientShell.isFileRecoveryRequired(), "incremental packet uses display route too");
            clientCopy.load(data);
            helper.assertTrue(clientShell.isFileRecoveryRequired(), "later full load cannot inherit a client-view bypass");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recoverBlockFilesRescuesRawFilesWithoutChangingSourceMachine(GameTestHelper helper) throws Exception {
        var pos = new BlockPos(1, 2, 1);
        var absolute = helper.absolutePos(pos);
        for (var block : java.util.List.of(ModRegistries.TERMINAL_BLOCK.get(), ModRegistries.TURTLE_BLOCK.get(), ModRegistries.SERVER_RACK_BLOCK.get())) {
            helper.setBlock(pos, block);
            var entity = helper.getBlockEntity(pos);
            var computer = (com.malice.terminalcraft.shell.ShellComputer) entity;
            BashShell source = new BashShell();
            source.getVfs().clearAll();
            source.getVfs().writeFile("/rescued.txt", "exact rescued 漢🙂\n");
            var raw = source.save();
            raw.getCompound("Vfs").putString("/damaged.txt", "raw original remains");
            var data = entity.saveWithoutMetadata();
            data.put("Shell", raw);
            entity.load(data);
            var before = entity.saveWithoutMetadata();
            ItemStack target = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
            var result = com.malice.terminalcraft.persistence.PocketArchives.recoverBlockFiles(helper.getLevel(), target, absolute);
            helper.assertTrue(result.files() == 1 && result.skipped() == 1, "raw source files recovered, not safe display defaults");
            var recovered = new BashShell.VirtualFileSystem();
            recovered.load(com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), target).readShell().getCompound("Vfs"));
            helper.assertTrue("exact rescued 漢🙂\n".equals(recovered.readFile("/rescued.txt"))
                    && recovered.readFile(result.reportPath()).contains("/damaged.txt"), "file and skipped-entry report present");
            helper.assertTrue(before.equals(entity.saveWithoutMetadata()) && computer.getShell().isFileRecoveryRequired(), "entire source machine snapshot and recovery state unchanged");
            var occupied = target.save(new net.minecraft.nbt.CompoundTag());
            boolean rejected = false;
            try { com.malice.terminalcraft.persistence.PocketArchives.recoverBlockFiles(helper.getLevel(), target, absolute); }
            catch (java.io.IOException expected) { rejected = true; }
            helper.assertTrue(rejected && occupied.equals(target.save(new net.minecraft.nbt.CompoundTag())), "occupied target protected");
        }
        helper.setBlock(pos, net.minecraft.world.level.block.Blocks.CHEST);
        ItemStack blank = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        var before = blank.save(new net.minecraft.nbt.CompoundTag());
        boolean rejected = false;
        try { com.malice.terminalcraft.persistence.PocketArchives.recoverBlockFiles(helper.getLevel(), blank, absolute); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && before.equals(blank.save(new net.minecraft.nbt.CompoundTag())), "unsupported block refused without target change");
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("tcpocket");
        helper.assertTrue(root.getChild("recover-block") != null && !root.canUse(helper.getLevel().getServer().createCommandSourceStack().withPermission(0)), "block rescue is operator gated");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void placedComputersPreserveDamagedFilesAcrossSaveAndReload(GameTestHelper helper) {
        var pos = new BlockPos(1, 2, 1);
        for (var block : java.util.List.of(ModRegistries.TERMINAL_BLOCK.get(), ModRegistries.TURTLE_BLOCK.get(), ModRegistries.SERVER_RACK_BLOCK.get())) {
            helper.setBlock(pos, block);
            var entity = helper.getBlockEntity(pos);
            var computer = (com.malice.terminalcraft.shell.ShellComputer) entity;
            BashShell original = new BashShell();
            original.getVfs().writeFile("/home/player/keep.txt", "retain original");
            var raw = original.save();
            raw.getCompound("Vfs").putString("/bad.txt", "unreadable entry retained");
            var data = entity.saveWithoutMetadata();
            data.put("Shell", raw.copy());
            entity.load(data);
            helper.assertTrue(computer.getShell().isFileRecoveryRequired(), "damaged block filesystem enters preservation state");
            helper.assertTrue(computer.getShell().runScriptText("echo replace > /home/player/keep.txt") != 0, "scripts cannot run on salvaged defaults");
            computer.getShell().execute("rm /home/player/keep.txt");
            helper.assertTrue(!computer.getShell().saveEditorFromGui("overwrite", true), "GUI save refused");
            computer.markShellChanged();
            var preserved = entity.saveWithoutMetadata();
            helper.assertTrue(raw.equals(preserved.getCompound("Shell")), "normal block save preserves exact raw shell");
            var view = entity.getUpdateTag().getCompound("Shell");
            helper.assertTrue(!view.contains("Vfs") && view.getList("Output", net.minecraft.nbt.Tag.TAG_STRING).toString().contains("recovery required"), "client sees warning, never raw damaged files");
            entity.load(preserved);
            helper.assertTrue(computer.getShell().isFileRecoveryRequired() && raw.equals(entity.saveWithoutMetadata().getCompound("Shell")), "save/reload remains protected");
            var detached = computer.getShell().save();
            detached.putString("ExternalMutation", "not shared");
            helper.assertTrue(!computer.getShell().save().contains("ExternalMutation"), "snapshot copies are detached");
            data.put("Shell", original.save());
            entity.load(data);
            helper.assertTrue(!computer.getShell().isFileRecoveryRequired() && computer.getShell().runScriptText("echo restored > /home/player/keep.txt") == 0, "valid restored data resumes execution");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pocketRecoveryPressureKeepsReportAndAccurateOmissionCounts(GameTestHelper helper) throws Exception {
        for (String mode : java.util.List.of("characters", "nodes", "diagnostics")) {
            BashShell source = new BashShell();
            source.getVfs().clearAll();
            String content = "漢".repeat(65_536);
            if (mode.equals("characters")) {
                for (int i = 0; i < 64; i++) helper.assertTrue(source.getVfs().writeFile(String.format(java.util.Locale.ROOT, "/file%03d", i), content), "full character fixture fits exactly");
            }
            var saved = source.save();
            var vfs = saved.getCompound("Vfs");
            if (mode.equals("nodes")) {
                for (int i = 0; i < 4100; i++) {
                    var dir = new net.minecraft.nbt.CompoundTag();
                    dir.putBoolean("Dir", true);
                    vfs.put(String.format(java.util.Locale.ROOT, "/dir%04d", i), dir);
                }
            }
            if (mode.equals("diagnostics")) {
                for (int i = 0; i < 4200; i++) vfs.putString(String.format(java.util.Locale.ROOT, "/bad%04d-", i) + "x".repeat(220), "retain malformed original");
            }
            var id = java.util.UUID.randomUUID();
            var record = com.malice.terminalcraft.persistence.PocketComputerData.allocate(helper.getLevel(), id);
            record.initialize(saved, null);
            var before = record.save(new net.minecraft.nbt.CompoundTag());
            var revision = record.revision();
            record.setDirty(false);
            ItemStack item = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
            var result = com.malice.terminalcraft.persistence.PocketArchives.recoverFiles(helper.getLevel(), item, id);
            var copy = com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), item);
            var recovered = new BashShell.VirtualFileSystem();
            recovered.load(copy.readShell().getCompound("Vfs"));
            String report = recovered.readFile(result.reportPath());
            helper.assertTrue(report != null && report.length() <= 60_000, mode + " always preserves bounded report");
            if (mode.equals("characters")) {
                helper.assertTrue(result.files() == 63 && result.skipped() == 1, "report reservation skips one whole file at full capacity");
                for (int i = 0; i < 63; i++) helper.assertTrue(content.equals(recovered.readFile(String.format(java.util.Locale.ROOT, "/file%03d", i))), "recovered full-size Unicode file is exact");
                helper.assertTrue(recovered.readFile("/file063") == null && report.contains("Unexamined entries beyond selection limit: 0"), "no partial last file or unexplained omission");
            } else if (mode.equals("nodes")) {
                helper.assertTrue(result.files() == 0 && result.skipped() == 6
                        && report.contains("Unexamined entries beyond selection limit: 4"), "node and selection omissions exclude schema metadata");
                helper.assertTrue(copy.readShell().getCompound("Vfs").size() == 4097, "exactly 4096 nodes plus schema marker including report");
            } else {
                helper.assertTrue(result.files() == 0 && result.skipped() == 4200
                        && report.contains("Unexamined entries beyond selection limit: 104"), "all malformed entries counted, schema marker excluded");
                helper.assertTrue(!report.contains("Additional omitted diagnostic lines: 0\n"), "diagnostic truncation explicitly counted");
            }
            helper.assertTrue(before.equals(record.save(new net.minecraft.nbt.CompoundTag())) && revision.equals(record.revision()) && !record.isDirty(), mode + " leaves original record unchanged");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pocketFileRecoveryCopiesOnlyExactFilesAndPreservesDamagedOriginal(GameTestHelper helper) throws Exception {
        BashShell shell = new BashShell();
        shell.getVfs().clearAll();
        shell.getVfs().writeFile("/keep.txt", "exact original\n");
        String unicode = "漢🙂".repeat(20_000);
        shell.getVfs().writeFile("/unicode.txt", unicode);
        shell.getVfs().writeFile("/conflict", "parent is a file");
        var saved = shell.save();
        var files = saved.getCompound("Vfs");
        files.putString("/wrong.txt", "raw entry must remain in original");
        var unknown = files.getCompound("/keep.txt").copy();
        unknown.putInt("ContentEncoding", 999);
        files.put("/unknown.txt", unknown);
        files.put("/../ambiguous.txt", files.getCompound("/keep.txt").copy());
        files.put("/conflict/child.txt", files.getCompound("/keep.txt").copy());
        var oversized = files.getCompound("/keep.txt").copy();
        oversized.putString("Data", "x".repeat(21_845));
        var parts = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 3; i++) parts.add(net.minecraft.nbt.StringTag.valueOf("x".repeat(21_845)));
        oversized.put("DataParts", parts);
        oversized.putInt("ContentEncoding", 2);
        files.put("/oversized.txt", oversized);
        var id = java.util.UUID.randomUUID();
        var record = com.malice.terminalcraft.persistence.PocketComputerData.allocate(helper.getLevel(), id);
        var backup = new net.minecraft.nbt.CompoundTag();
        backup.putString("Untouched", "original backup");
        record.initialize(saved, backup);
        var path = com.malice.terminalcraft.persistence.PocketComputerData.file(helper.getLevel(), id);
        record.persistVerified(path);
        var before = record.save(new net.minecraft.nbt.CompoundTag());
        byte[] diskBefore = java.nio.file.Files.readAllBytes(path);
        ItemStack target = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        var result = com.malice.terminalcraft.persistence.PocketArchives.recoverFiles(helper.getLevel(), target, id);
        helper.assertTrue(result.files() == 3 && result.skipped() == 5, "exact recovery and skipped counts");
        var copy = com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), target);
        helper.assertTrue(!id.equals(target.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)), "recovery is independent");
        var recovered = new BashShell.VirtualFileSystem();
        recovered.load(copy.readShell().getCompound("Vfs"));
        helper.assertTrue("exact original\n".equals(recovered.readFile("/keep.txt"))
                && unicode.equals(recovered.readFile("/unicode.txt")), "ASCII and chunked Unicode copied exactly");
        helper.assertTrue(recovered.readFile("/oversized.txt") == null && recovered.readFile("/unknown.txt") == null
                && recovered.readFile("/ambiguous.txt") == null && recovered.readFile("/conflict/child.txt") == null, "no fragments or ambiguous files published");
        String report = recovered.readFile(result.reportPath());
        helper.assertTrue(report.contains(id.toString()) && report.contains("Skipped entries: 5")
                && report.contains("/oversized.txt") && report.contains("/wrong.txt"), "persisted report identifies source and skipped entries");
        var player = helper.makeMockPlayer();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, target);
        var host = new com.malice.terminalcraft.shell.PocketShellComputer(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        helper.assertTrue(host.storageError() == null && host.isStillHolding(), "recovered pocket opens normally");
        host.getShell().executeForResult("echo changed > /keep.txt");
        host.markShellChanged();
        helper.assertTrue(before.equals(record.save(new net.minecraft.nbt.CompoundTag())) && !record.isDirty()
                && java.util.Arrays.equals(diskBefore, java.nio.file.Files.readAllBytes(path)), "source record, backup and disk bytes remain unchanged after copy edits");
        var occupied = target.save(new net.minecraft.nbt.CompoundTag());
        boolean rejected = false;
        try { com.malice.terminalcraft.persistence.PocketArchives.recoverFiles(helper.getLevel(), target, id); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && occupied.equals(target.save(new net.minecraft.nbt.CompoundTag())), "occupied target protected");
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("tcpocket");
        helper.assertTrue(root.getChild("recover-files") != null && !root.canUse(helper.getLevel().getServer().createCommandSourceStack().withPermission(0)), "recovery command is operator gated");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void pocketFileRecoveryRefusesMissingAndFutureSourcesWithoutChangingTarget(GameTestHelper helper) throws Exception {
        for (String kind : java.util.List.of("missing", "future-shell", "future-vfs", "missing-vfs")) {
            var id = java.util.UUID.randomUUID();
            if (!kind.equals("missing")) {
                var shell = new BashShell().save();
                if (kind.equals("future-shell")) shell.putInt("DataVersion", 999);
                if (kind.equals("future-vfs")) shell.getCompound("Vfs").putInt("DataVersion", 999);
                if (kind.equals("missing-vfs")) shell.remove("Vfs");
                com.malice.terminalcraft.persistence.PocketComputerData.allocate(helper.getLevel(), id).initialize(shell, null);
            }
            ItemStack target = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
            target.getOrCreateTag().putString("Label", "blank target label");
            var before = target.save(new net.minecraft.nbt.CompoundTag());
            boolean rejected = false;
            try { com.malice.terminalcraft.persistence.PocketArchives.recoverFiles(helper.getLevel(), target, id); }
            catch (java.io.IOException expected) { rejected = true; }
            helper.assertTrue(rejected && before.equals(target.save(new net.minecraft.nbt.CompoundTag())), kind + " refuses without target mutation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void damagedPocketFilesCannotBecomeWritableSalvagedSessions(GameTestHelper helper) throws Exception {
        var player = helper.makeMockPlayer();
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        for (String damage : java.util.List.of("missing", "wrong-type", "truncated", "unknown-encoding", "future-vfs", "normalized-path")) {
            BashShell original = new BashShell();
            original.getVfs().writeFile("/home/player/keep.txt", "original 漢🙂");
            var saved = original.save();
            var vfs = saved.getCompound("Vfs");
            switch (damage) {
                case "missing" -> saved.remove("Vfs");
                case "wrong-type" -> vfs.putString("/home/player/bad.txt", "raw recoverable data");
                case "truncated" -> vfs.getCompound("/home/player/keep.txt").putString("Data", "x".repeat(65_537));
                case "unknown-encoding" -> vfs.getCompound("/home/player/keep.txt").putInt("ContentEncoding", 999);
                case "future-vfs" -> vfs.putInt("DataVersion", 999);
                case "normalized-path" -> vfs.put("/home/player/../collision.txt", vfs.getCompound("/home/player/keep.txt").copy());
            }
            var id = java.util.UUID.randomUUID();
            var record = com.malice.terminalcraft.persistence.PocketComputerData.allocate(helper.getLevel(), id);
            record.initialize(saved, null);
            var before = record.save(new net.minecraft.nbt.CompoundTag());
            var revision = record.revision();
            record.setDirty(false);
            ItemStack item = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
            item.getOrCreateTag().putUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY, id);
            var itemBefore = item.save(new net.minecraft.nbt.CompoundTag());
            player.setItemInHand(hand, item);
            var host = new com.malice.terminalcraft.shell.PocketShellComputer(player, hand);
            helper.assertTrue(host.storageError() != null && !host.isStillHolding(), damage + " refuses writable session");
            host.getShell().executeForResult("echo overwrite > /home/player/keep.txt");
            host.markShellChanged();
            host.setLabel("must not change");
            helper.assertTrue(before.equals(record.save(new net.minecraft.nbt.CompoundTag())) && revision.equals(record.revision())
                    && !record.isDirty(), damage + " keeps raw record and revision unchanged");
            helper.assertTrue(itemBefore.equals(item.save(new net.minecraft.nbt.CompoundTag())), damage + " keeps source identity and label");
        }
        // A legacy malformed file is still safely moved off the item, with its exact raw backup.
        ItemStack legacy = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        var malformed = new BashShell().save();
        malformed.getCompound("Vfs").putString("/broken.txt", "retain this raw value");
        legacy.getOrCreateTag().put("Shell", malformed);
        var backup = legacy.getTag().copy();
        player.setItemInHand(hand, legacy);
        var refused = new com.malice.terminalcraft.shell.PocketShellComputer(player, hand);
        helper.assertTrue(refused.storageError() != null && !refused.isStillHolding(), "legacy corruption cannot open writable");
        var migrated = com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), legacy);
        helper.assertTrue(malformed.equals(migrated.readShell()) && backup.equals(migrated.readLegacyBackup()), "migration retains exact malformed source and backup");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realServerDeathAndRestoreHonorKeepInventoryAndMigrateRetainedPockets(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var rule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        boolean previous = rule.get();
        try {
            for (boolean keep : new boolean[] {false, true}) {
                rule.set(keep, level.getServer());
                var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "pocket-life-test");
                var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level, profile);
                // Only network output is stubbed; the tested player is not FakePlayer (whose die is a no-op).
                player.connection = new net.minecraftforge.common.util.FakePlayer(level, profile).connection;
                var pos = helper.absolutePos(new BlockPos(2, 3, 2));
                player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                ItemStack pocket = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
                BashShell shell = new BashShell();
                shell.getVfs().writeFile("/home/player/life.txt", "preserved 漢🙂");
                com.malice.terminalcraft.item.PocketTerminalItem.saveShell(pocket, shell);
                player.getInventory().setItem(0, pocket);
                player.getEnderChestInventory().setItem(0, pocket.copy());
                var area = new net.minecraft.world.phys.AABB(pos).inflate(4);
                var oldIds = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                        .map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
                player.setHealth(0);
                player.die(level.damageSources().genericKill());
                var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                        .filter(entity -> !oldIds.contains(entity.getUUID()) && entity.getItem().is(ModRegistries.POCKET_TERMINAL.get())).toList();
                helper.assertTrue(drops.size() == (keep ? 0 : 1), "death creates expected pocket drop count for keepInventory=" + keep);
                helper.assertTrue(player.getInventory().countItem(ModRegistries.POCKET_TERMINAL.get()) == (keep ? 1 : 0), "death honors retained inventory rule");
                var replacement = new net.minecraft.server.level.ServerPlayer(level.getServer(), level, profile);
                replacement.connection = new net.minecraftforge.common.util.FakePlayer(level, profile).connection;
                replacement.restoreFrom(player, false);
                helper.assertTrue(replacement.getInventory().countItem(ModRegistries.POCKET_TERMINAL.get()) == (keep ? 1 : 0), "restore does not duplicate dropped inventory");
                ItemStack surviving = keep ? replacement.getInventory().getItem(0) : drops.get(0).getItem();
                helper.assertTrue(surviving.getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)
                        && !surviving.getTag().contains("Shell"), "retained or dropped pocket migrated before its next synchronization");
                ItemStack ender = replacement.getEnderChestInventory().getItem(0);
                helper.assertTrue(ender.getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)
                        && !ender.getTag().contains("Shell"), "clone hook migrates retained ender pocket under both rules");
                for (ItemStack item : java.util.List.of(surviving, ender)) {
                    BashShell restored = new BashShell();
                    restored.load(com.malice.terminalcraft.persistence.PocketStorage.open(level, item).readShell());
                    helper.assertTrue("preserved 漢🙂".equals(restored.getVfs().readFile("/home/player/life.txt")), "files survive death/restore");
                }
                for (var drop : drops) drop.discard();
            }
        } finally { rule.set(previous, level.getServer()); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void serverInventoryDropAllPreservesMigratedFailedAndPackedPocketsExactlyOnce(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var player = new net.minecraftforge.common.util.FakePlayer(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "pocket-drop-test"));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        ItemStack good = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell shell = new BashShell();
        String contents = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) helper.assertTrue(shell.getVfs().writeFile("/home/player/death-drop" + i, contents), "inventory drop fixture");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(good, shell);
        ItemStack packed = pocketTestShulker(good.copy(), new ItemStack(net.minecraft.world.item.Items.EMERALD, 3));
        ItemStack bad = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        bad.getOrCreateTag().putString("Shell", "retain original after failed migration");
        var badBefore = bad.save(new net.minecraft.nbt.CompoundTag());
        player.getInventory().setItem(0, good);
        player.getInventory().setItem(1, bad);
        player.getInventory().setItem(40, packed);
        player.getInventory().setItem(2, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        var area = new net.minecraft.world.phys.AABB(pos).inflate(4);
        var oldIds = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                .map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
        player.getInventory().dropAll();
        helper.assertTrue(player.getInventory().isEmpty(), "actual server inventory dropAll clears all compartments");
        var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                .filter(entity -> !oldIds.contains(entity.getUUID())).toList();
        helper.assertTrue(drops.size() == 4, "exactly four original stacks spawned, including offhand shulker");
        var goodDrop = drops.stream().filter(entity -> entity.getItem().is(ModRegistries.POCKET_TERMINAL.get())
                && entity.getItem().getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)).findFirst().orElseThrow();
        var failedDrop = drops.stream().filter(entity -> entity.getItem().is(ModRegistries.POCKET_TERMINAL.get())
                && entity.getItem().getTag().contains("Shell")).findFirst().orElseThrow();
        helper.assertTrue(badBefore.equals(failedDrop.getItem().save(new net.minecraft.nbt.CompoundTag())), "failed migration survives source-inventory removal intact");
        var identity = goodDrop.getItem().getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        player.getInventory().dropAll();
        helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                .filter(entity -> !oldIds.contains(entity.getUUID())).count() == 4, "repeating empty dropAll creates no duplicate stacks");
        var receiver = helper.makeMockPlayer();
        for (var drop : drops) { drop.setNoPickUpDelay(); drop.playerTouch(receiver); }
        helper.assertTrue(drops.stream().allMatch(net.minecraft.world.entity.Entity::isRemoved), "all four stacks picked up once");
        helper.assertTrue(receiver.getInventory().countItem(ModRegistries.POCKET_TERMINAL.get()) == 2
                && receiver.getInventory().countItem(net.minecraft.world.item.Items.BLUE_SHULKER_BOX) == 1
                && receiver.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) == 7, "exact received inventory counts");
        var recovered = receiver.getInventory().items.stream().filter(stack -> stack.is(ModRegistries.POCKET_TERMINAL.get())
                && stack.getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)).findFirst().orElseThrow();
        helper.assertTrue(identity.equals(recovered.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)), "pickup keeps computer identity");
        BashShell restored = new BashShell();
        restored.load(com.malice.terminalcraft.persistence.PocketStorage.open(level, recovered).readShell());
        for (int i = 0; i < 40; i++) helper.assertTrue(contents.equals(restored.getVfs().readFile("/home/player/death-drop" + i)), "all dropped inventory files preserved");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void originalPocketRecoveryCreatesIndependentCopyWithoutRevertingSource(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        ItemStack source = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell original = new BashShell();
        original.getVfs().writeFile("/home/player/recover.txt", "original 漢🙂");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(source, original);
        com.malice.terminalcraft.item.PocketTerminalItem.setLabel(source, "original-label");
        var oldModem = com.malice.terminalcraft.item.PocketTerminalItem.getOrCreateModemId(source);
        var record = com.malice.terminalcraft.persistence.PocketStorage.open(level, source);
        var identity = source.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        var backup = record.readLegacyBackup();
        backup.putString("ExternalMutation", "must not affect original");
        helper.assertTrue(!record.readLegacyBackup().contains("ExternalMutation"), "recovery backup reads are detached");
        BashShell edited = new BashShell();
        edited.load(record.readShell());
        edited.getVfs().writeFile("/home/player/recover.txt", "newer contents");
        record.compareAndSave(record.revision(), edited.save());
        var sourceRecordBefore = record.save(new net.minecraft.nbt.CompoundTag());
        var sourceItemBefore = source.save(new net.minecraft.nbt.CompoundTag());
        ItemStack target = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.persistence.PocketArchives.recoverOriginal(level, target, identity);
        var newIdentity = target.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        helper.assertTrue(!identity.equals(newIdentity), "recovery creates independent identity");
        BashShell restored = new BashShell();
        restored.load(com.malice.terminalcraft.persistence.PocketStorage.open(level, target).readShell());
        helper.assertTrue("original 漢🙂".equals(restored.getVfs().readFile("/home/player/recover.txt")), "recovery uses migration-time contents, not latest edits");
        helper.assertTrue("original-label".equals(com.malice.terminalcraft.item.PocketTerminalItem.getLabel(target)), "original label restored");
        helper.assertTrue(!oldModem.equals(com.malice.terminalcraft.item.PocketTerminalItem.getOrCreateModemId(target)), "source modem identity is not cloned");
        helper.assertTrue(sourceRecordBefore.equals(record.save(new net.minecraft.nbt.CompoundTag()))
                && sourceItemBefore.equals(source.save(new net.minecraft.nbt.CompoundTag())), "current source item and full record unchanged");
        var beforeOccupied = target.save(new net.minecraft.nbt.CompoundTag());
        boolean rejected = false;
        try { com.malice.terminalcraft.persistence.PocketArchives.recoverOriginal(level, target, identity); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && beforeOccupied.equals(target.save(new net.minecraft.nbt.CompoundTag())), "occupied recovery target is protected");
        ItemStack noBackup = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.persistence.PocketStorage.open(level, noBackup);
        for (var unavailable : java.util.List.of(java.util.UUID.randomUUID(), noBackup.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY))) {
            ItemStack blank = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
            var beforeBlank = blank.save(new net.minecraft.nbt.CompoundTag());
            rejected = false;
            try { com.malice.terminalcraft.persistence.PocketArchives.recoverOriginal(level, blank, unavailable); }
            catch (java.io.IOException expected) { rejected = true; }
            helper.assertTrue(rejected && beforeBlank.equals(blank.save(new net.minecraft.nbt.CompoundTag())), "missing record/backup does not alter blank target");
        }
        var root = level.getServer().getCommands().getDispatcher().getRoot().getChild("tcpocket");
        helper.assertTrue(root.getChild("recover-original") != null
                && !root.canUse(level.getServer().createCommandSourceStack().withPermission(0)), "recovery command registered behind operator permission");
        var legacyFiles = new net.minecraft.nbt.CompoundTag();
        var file = new net.minecraft.nbt.CompoundTag();
        file.putBoolean("Dir", false);
        file.putString("Data", "old-format content");
        legacyFiles.put("/old.txt", file);
        var rootDirectory = new net.minecraft.nbt.CompoundTag();
        rootDirectory.putBoolean("Dir", true);
        legacyFiles.put("/", rootDirectory);
        var legacyShell = new net.minecraft.nbt.CompoundTag();
        legacyShell.put("Vfs", legacyFiles);
        ItemStack old = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        old.getOrCreateTag().put("Shell", legacyShell);
        com.malice.terminalcraft.persistence.PocketStorage.open(level, old);
        ItemStack oldRecovered = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.persistence.PocketArchives.recoverOriginal(level, oldRecovered,
                old.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY));
        var oldContents = new BashShell();
        oldContents.load(com.malice.terminalcraft.persistence.PocketStorage.open(level, oldRecovered).readShell());
        helper.assertTrue("old-format content".equals(oldContents.getVfs().readFile("/old.txt")), "versionless legacy backup recovers without requiring bundled startup programs");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void shulkerPackedPocketMigratesOnLoadAndDropWithoutChangingOtherContents(GameTestHelper helper) throws Exception {
        ItemStack pocket = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell shell = new BashShell();
        String content = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) helper.assertTrue(shell.getVfs().writeFile("/home/player/packed" + i, content), "packed large file");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(pocket, shell);
        ItemStack packed = pocketTestShulker(pocket, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        var untouched = packed.getTag().getCompound("BlockEntityTag").getList("Items", 10).getCompound(1).copy();
        ItemStack dropCopy = packed.copy();
        var player = helper.makeMockPlayer();
        player.getInventory().setItem(0, packed);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.LoadFromFile(
                player, helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(), player.getStringUUID()));
        var entries = packed.getTag().getCompound("BlockEntityTag").getList("Items", 10);
        var child = ItemStack.of(entries.getCompound(0));
        helper.assertTrue(!child.getTag().contains("Shell") && child.getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY), "load handler migrates pocket inside shulker");
        helper.assertTrue(untouched.equals(entries.getCompound(1)) && entries.getCompound(0).getByte("Slot") == 0
                && "retain entry metadata".equals(entries.getCompound(0).getString("UnknownField")), "other contents and entry metadata unchanged");
        helper.assertTrue("retain outer metadata".equals(packed.getTag().getString("UnknownOuter")), "outer metadata unchanged");
        var files = new BashShell();
        files.load(com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), child).readShell());
        for (int i = 0; i < 40; i++) helper.assertTrue(content.equals(files.getVfs().readFile("/home/player/packed" + i)), "packed files remain exact");
        var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try { packet.writeItem(packed); helper.assertTrue(packet.readableBytes() < 1024, "packed item packet is small"); }
        finally { packet.release(); }
        var pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var entity = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), pos.getX(), pos.getY(), pos.getZ(), dropCopy);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "packed loose item spawns");
        var droppedChild = ItemStack.of(entity.getItem().getTag().getCompound("BlockEntityTag").getList("Items", 10).getCompound(0));
        helper.assertTrue(!droppedChild.getTag().contains("Shell"), "entity handler also migrates packed pocket");
        entity.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nestedPocketFailureRetainsWholeContainerAndTraversalIsBounded(GameTestHelper helper) throws Exception {
        ItemStack valid = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(valid, new BashShell());
        ItemStack bad = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        bad.getOrCreateTag().putString("Shell", "retain invalid original");
        ItemStack packed = pocketTestShulker(valid, bad);
        var before = packed.save(new net.minecraft.nbt.CompoundTag());
        long recordsBefore = pocketRecordFileCount(helper);
        boolean rejected = false;
        try { com.malice.terminalcraft.persistence.PocketItemMigration.migrate(helper.getLevel(), packed); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && before.equals(packed.save(new net.minecraft.nbt.CompoundTag())), "failed descendant leaves entire outer container unchanged");
        helper.assertTrue(recordsBefore == pocketRecordFileCount(helper), "malformed later child creates no unused records for earlier valid children");
        for (String invalid : java.util.List.of("string", "key", "future")) {
            ItemStack later = valid.copy();
            if (invalid.equals("string")) later.getOrCreateTag().putString("Unencodable", "漢".repeat(21_846));
            else if (invalid.equals("key")) later.getOrCreateTag().putString("k".repeat(65_536), "value");
            else later.getOrCreateTag().getCompound("Shell").putInt(com.malice.terminalcraft.persistence.PersistedDataVersions.TAG_DATA_VERSION, 999);
            ItemStack invalidPacked = pocketTestShulker(valid, later);
            var unchanged = invalidPacked.save(new net.minecraft.nbt.CompoundTag());
            rejected = false;
            try { com.malice.terminalcraft.persistence.PocketItemMigration.migrate(helper.getLevel(), invalidPacked); }
            catch (java.io.IOException expected) { rejected = true; }
            helper.assertTrue(rejected && unchanged.equals(invalidPacked.save(new net.minecraft.nbt.CompoundTag()))
                    && recordsBefore == pocketRecordFileCount(helper), invalid + " rejected before any descendant file is written");
        }
        ItemStack nested = valid.copy();
        for (int depth = 0; depth < 10; depth++) nested = pocketTestShulker(nested, ItemStack.EMPTY);
        var deepBefore = nested.save(new net.minecraft.nbt.CompoundTag());
        rejected = false;
        try { com.malice.terminalcraft.persistence.PocketItemMigration.migrate(helper.getLevel(), nested); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && deepBefore.equals(nested.save(new net.minecraft.nbt.CompoundTag())), "over-deep data rejected without changing root");
        ItemStack supportedNested = pocketTestShulker(pocketTestShulker(valid, ItemStack.EMPTY), ItemStack.EMPTY);
        helper.assertTrue(com.malice.terminalcraft.persistence.PocketItemMigration.migrate(helper.getLevel(), supportedNested), "bounded known nesting migrates");
        helper.assertTrue(!com.malice.terminalcraft.persistence.PocketItemMigration.migrate(helper.getLevel(), supportedNested), "already migrated nested tree stays stable");
        helper.succeed();
    }

    private static long pocketRecordFileCount(GameTestHelper helper) throws java.io.IOException {
        var directory = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
        java.nio.file.Files.createDirectories(directory);
        try (var files = java.nio.file.Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("terminalcraft_pocket_")).count();
        }
    }

    private static ItemStack pocketTestShulker(ItemStack first, ItemStack second) {
        ItemStack shulker = new ItemStack(net.minecraft.world.item.Items.BLUE_SHULKER_BOX);
        var list = new net.minecraft.nbt.ListTag();
        var a = first.save(new net.minecraft.nbt.CompoundTag());
        a.putByte("Slot", (byte) 0);
        a.putString("UnknownField", "retain entry metadata");
        list.add(a);
        if (!second.isEmpty()) {
            var b = second.save(new net.minecraft.nbt.CompoundTag());
            b.putByte("Slot", (byte) 12);
            list.add(b);
        }
        var block = new net.minecraft.nbt.CompoundTag();
        block.put("Items", list);
        block.putString("CustomName", "\"Test packing\"");
        shulker.getOrCreateTag().put("BlockEntityTag", block);
        shulker.getOrCreateTag().putString("UnknownOuter", "retain outer metadata");
        return shulker;
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void looseLegacyPocketMigratesSurvivesReloadAndPicksUpOnce(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var position = helper.absolutePos(new BlockPos(2, 3, 2));
        ItemStack legacy = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell shell = new BashShell();
        String content = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) helper.assertTrue(shell.getVfs().writeFile("/home/player/drop" + i, content), "large dropped pocket fixture");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(legacy, shell);
        var original = legacy.save(new net.minecraft.nbt.CompoundTag());
        var item = new net.minecraft.world.entity.item.ItemEntity(level, position.getX(), position.getY(), position.getZ(), legacy);
        helper.assertTrue(level.addFreshEntity(item), "legacy drop is added");
        helper.assertTrue(!item.getItem().getTag().contains("Shell")
                && item.getItem().getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY), "join hook migrates before tracking");
        helper.assertTrue(original.equals(legacy.save(new net.minecraft.nbt.CompoundTag())), "detached migration does not mutate source reference");
        var identity = item.getItem().getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try { packet.writeItem(item.getItem()); helper.assertTrue(packet.readableBytes() < 512, "entity item payload is small"); }
        finally { packet.release(); }
        var saved = new net.minecraft.nbt.CompoundTag();
        helper.assertTrue(item.save(saved), "item entity saves");
        item.discard();
        var restored = new net.minecraft.world.entity.item.ItemEntity(net.minecraft.world.entity.EntityType.ITEM, level);
        restored.load(saved);
        helper.assertTrue(level.addFreshEntity(restored), "saved entity reloads");
        helper.assertTrue(identity.equals(restored.getItem().getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)), "reload retains same world identity");
        var player = helper.makeMockPlayer();
        restored.setNoPickUpDelay();
        restored.playerTouch(player);
        helper.assertTrue(restored.isRemoved(), "successful pickup removes loose entity");
        helper.assertTrue(player.getInventory().countItem(ModRegistries.POCKET_TERMINAL.get()) == 1, "pickup adds exactly one pocket");
        var held = player.getInventory().getItem(0);
        helper.assertTrue(identity.equals(held.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)), "pickup retains identity");
        BashShell files = new BashShell();
        files.load(com.malice.terminalcraft.persistence.PocketStorage.open(level, held).readShell());
        for (int i = 0; i < 40; i++) helper.assertTrue(content.equals(files.getVfs().readFile("/home/player/drop" + i)), "all files survive spawn/reload/pickup");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void loosePocketMigrationFailureNeverCancelsOrDeletesOriginal(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(2, 3, 2));
        ItemStack damaged = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        damaged.getOrCreateTag().putString("Shell", "retain malformed item");
        var before = damaged.save(new net.minecraft.nbt.CompoundTag());
        var item = new net.minecraft.world.entity.item.ItemEntity(level, pos.getX(), pos.getY(), pos.getZ(), damaged);
        helper.assertTrue(level.addFreshEntity(item) && !item.isRemoved(), "failure does not cancel or delete drop");
        helper.assertTrue(before.equals(item.getItem().save(new net.minecraft.nbt.CompoundTag())), "failed original unchanged");
        item.discard();
        ItemStack legacy = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(legacy, new BashShell());
        var canceledItem = new net.minecraft.world.entity.item.ItemEntity(level, pos.getX(), pos.getY(), pos.getZ(), legacy);
        var canceled = new net.minecraftforge.event.entity.EntityJoinLevelEvent(canceledItem, level);
        canceled.setCanceled(true);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(canceled);
        helper.assertTrue(legacy.getTag().contains("Shell") && !legacy.getTag().contains(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY), "already cancelled join leaves item untouched");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void vanillaContainerPreflightMigratesBeforeSlotPacketsAndRespectsDenial(GameTestHelper helper) throws Exception {
        var player = helper.makeMockPlayer();
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, net.minecraft.world.level.block.Blocks.BARREL);
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getBlockEntity(local);
        ItemStack legacy = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell shell = new BashShell();
        for (int i = 0; i < 40; i++) helper.assertTrue(shell.getVfs().writeFile("/home/player/container" + i, "漢🙂".repeat(20_000)), "large container fixture");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(legacy, shell);
        barrel.setItem(0, legacy);
        var before = legacy.save(new net.minecraft.nbt.CompoundTag());
        var canceled = pocketContainerClick(player, helper.absolutePos(local));
        canceled.setCanceled(true);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(canceled);
        helper.assertTrue(before.equals(legacy.save(new net.minecraft.nbt.CompoundTag())), "cancelled interaction leaves legacy item untouched");
        var denied = pocketContainerClick(player, helper.absolutePos(local));
        denied.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(denied);
        helper.assertTrue(before.equals(legacy.save(new net.minecraft.nbt.CompoundTag())), "denied block use leaves legacy item untouched");
        var allowed = pocketContainerClick(player, helper.absolutePos(local));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(allowed);
        helper.assertTrue(!allowed.isCanceled() && !legacy.getTag().contains("Shell")
                && legacy.getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY), "allowed barrel interaction migrates before opening");
        var record = com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), legacy);
        helper.assertTrue(shell.save().equals(record.readShell()), "full barrel filesystem preserved");
        var menu = net.minecraft.world.inventory.ChestMenu.threeRows(9, player.getInventory(), barrel);
        var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            for (var slot : menu.slots) packet.writeItem(slot.getItem());
            helper.assertTrue(packet.readableBytes() < 4096, "initial container slot payload contains no large legacy shell");
        } finally { packet.release(); }
        ItemStack damaged = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        damaged.getOrCreateTag().putString("Shell", "retain me");
        var damagedBefore = damaged.save(new net.minecraft.nbt.CompoundTag());
        barrel.setItem(1, damaged);
        var failed = pocketContainerClick(player, helper.absolutePos(local));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(failed);
        helper.assertTrue(failed.isCanceled() && damagedBefore.equals(damaged.save(new net.minecraft.nbt.CompoundTag())), "failed migration keeps container closed and original intact");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void doubleChestPreflightCoversBothHalvesWithoutUnpackingLoot(GameTestHelper helper) {
        var player = helper.makeMockPlayer();
        BlockPos firstPos = new BlockPos(2, 2, 2);
        var firstState = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        BlockPos secondPos = firstPos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(firstState));
        helper.setBlock(firstPos, firstState);
        helper.setBlock(secondPos, firstState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        var first = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(firstPos);
        var second = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(secondPos);
        ItemStack a = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(a, new BashShell());
        ItemStack b = a.copy();
        first.setItem(0, a);
        second.setItem(0, b);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(pocketContainerClick(player, helper.absolutePos(firstPos)));
        helper.assertTrue(!a.getTag().contains("Shell") && !b.getTag().contains("Shell"), "both halves migrate before double chest opens");
        BlockPos lootPos = new BlockPos(5, 2, 5);
        helper.setBlock(lootPos, net.minecraft.world.level.block.Blocks.BARREL);
        var loot = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getBlockEntity(lootPos);
        loot.setLootTable(new net.minecraft.resources.ResourceLocation("minecraft", "chests/simple_dungeon"), 1L);
        var savedLoot = loot.saveWithoutMetadata();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(pocketContainerClick(player, helper.absolutePos(lootPos)));
        helper.assertTrue(savedLoot.equals(loot.saveWithoutMetadata()) && loot.saveWithoutMetadata().contains("LootTable"), "preflight never unpacks pending loot");
        BlockPos lockedPos = new BlockPos(5, 2, 2);
        helper.setBlock(lockedPos, net.minecraft.world.level.block.Blocks.BARREL);
        var locked = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getBlockEntity(lockedPos);
        ItemStack lockedItem = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(lockedItem, new BashShell());
        locked.setItem(0, lockedItem);
        var lockedData = locked.saveWithoutMetadata();
        lockedData.putString("Lock", "test-key");
        locked.load(lockedData);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(pocketContainerClick(player, helper.absolutePos(lockedPos)));
        helper.assertTrue(lockedData.equals(locked.saveWithoutMetadata()), "locked container remains unchanged");
        helper.succeed();
    }

    private static net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock pocketContainerClick(
            net.minecraft.world.entity.player.Player player, BlockPos pos) {
        return new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,
                net.minecraft.world.InteractionHand.MAIN_HAND, pos,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void loadedInventoriesMigrateLegacyPocketsBeforeOpening(GameTestHelper helper) throws Exception {
        var player = helper.makeMockPlayer();
        BashShell shell = new BashShell();
        String content = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) helper.assertTrue(shell.getVfs().writeFile("/home/player/login" + i, content), "large saved inventory fixture");
        ItemStack legacy = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(legacy, shell);
        ItemStack ender = legacy.copy();
        player.getInventory().setItem(0, legacy);
        player.getEnderChestInventory().setItem(0, ender);
        ItemStack blank = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        player.getInventory().setItem(1, blank);
        ItemStack damaged = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        damaged.getOrCreateTag().putString("Shell", "retain malformed source");
        var damagedBefore = damaged.save(new net.minecraft.nbt.CompoundTag());
        player.getInventory().setItem(2, damaged);
        ItemStack unrelated = new ItemStack(net.minecraft.world.item.Items.PAPER);
        unrelated.getOrCreateTag().putString("Shell", "not a pocket");
        var unrelatedBefore = unrelated.save(new net.minecraft.nbt.CompoundTag());
        player.getInventory().setItem(3, unrelated);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.LoadFromFile(
                player, helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(), player.getStringUUID()));
        for (ItemStack item : java.util.List.of(legacy, ender)) {
            helper.assertTrue(item.getTag().hasUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY)
                    && !item.getTag().contains("Shell"), "load hook migrates unopened main and ender inventory pockets");
            var stored = com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), item);
            BashShell restored = new BashShell();
            restored.load(stored.readShell());
            for (int i = 0; i < 40; i++) helper.assertTrue(content.equals(restored.getVfs().readFile("/home/player/login" + i)), "all inventory files preserved");
            var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try { packet.writeItem(item); helper.assertTrue(packet.readableBytes() < 512, "loaded item packet contains no oversized filesystem"); }
            finally { packet.release(); }
        }
        helper.assertTrue(blank.getTag() == null, "blank pocket remains unopened and importable");
        helper.assertTrue(damagedBefore.equals(damaged.save(new net.minecraft.nbt.CompoundTag())), "failed migration retains malformed original");
        helper.assertTrue(unrelatedBefore.equals(unrelated.save(new net.minecraft.nbt.CompoundTag())), "unrelated items not changed");
        var beforeAgain = legacy.save(new net.minecraft.nbt.CompoundTag());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.LoadFromFile(
                player, helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(), player.getStringUUID()));
        helper.assertTrue(beforeAgain.equals(legacy.save(new net.minecraft.nbt.CompoundTag())), "repeated load hook does not remigrate identity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pocketArchivesRejectDamagedAndFutureFilesWithoutItemChanges(GameTestHelper helper) throws Exception {
        var directory = com.malice.terminalcraft.persistence.PocketArchives.directory(helper.getLevel());
        java.nio.file.Files.createDirectories(directory);
        for (String damage : java.util.List.of("gzip", "archive-format", "shell-format", "filesystem", "decode-budget")) {
            var id = java.util.UUID.randomUUID();
            var file = directory.resolve(id + ".dat");
            try {
                if (damage.equals("gzip")) {
                    java.nio.file.Files.write(file, new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.CREATE_NEW);
                } else {
                    var shell = new BashShell().save();
                    var metadata = new net.minecraft.nbt.CompoundTag();
                    metadata.putInt("PocketArchiveFormat", damage.equals("archive-format") ? 2 : 1);
                    if (damage.equals("shell-format")) shell.putInt(com.malice.terminalcraft.persistence.PersistedDataVersions.TAG_DATA_VERSION, 999);
                    if (damage.equals("filesystem")) shell.getCompound("Vfs").putString("/damaged", "not a file node");
                    var snapshot = new com.malice.terminalcraft.persistence.PocketComputerData();
                    snapshot.initialize(shell, metadata);
                    var root = new net.minecraft.nbt.CompoundTag();
                    root.put("data", snapshot.save(new net.minecraft.nbt.CompoundTag()));
                    if (damage.equals("decode-budget")) root.putByteArray("Oversized", new byte[33 * 1024 * 1024]);
                    net.minecraft.nbt.NbtIo.writeCompressed(root, file.toFile());
                }
                ItemStack blank = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
                var before = blank.save(new net.minecraft.nbt.CompoundTag());
                boolean rejected = false;
                try { com.malice.terminalcraft.persistence.PocketArchives.importHeld(helper.getLevel(), blank, id); }
                catch (java.io.IOException expected) { rejected = true; }
                helper.assertTrue(rejected && before.equals(blank.save(new net.minecraft.nbt.CompoundTag())),
                        damage + " archive rejected without altering held item");
            } finally { java.nio.file.Files.deleteIfExists(file); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pocketArchivesImportIndependentFilesAndProtectOccupiedItems(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        ItemStack source = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell shell = new BashShell();
        String contents = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) helper.assertTrue(shell.getVfs().writeFile("/home/player/archive" + i, contents), "large archive fixture");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(source, shell);
        com.malice.terminalcraft.item.PocketTerminalItem.setLabel(source, "portable-pocket");
        var archive = com.malice.terminalcraft.persistence.PocketArchives.exportHeld(level, source);
        var sourceId = source.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        ItemStack target = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        com.malice.terminalcraft.persistence.PocketArchives.importHeld(level, target, archive);
        var targetId = target.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        helper.assertTrue(!sourceId.equals(targetId) && !target.getTag().contains("Shell"), "import creates independent identity-only item");
        helper.assertTrue("portable-pocket".equals(com.malice.terminalcraft.item.PocketTerminalItem.getLabel(target)), "label transferred");
        var targetRecord = com.malice.terminalcraft.persistence.PocketStorage.open(level, target);
        BashShell imported = new BashShell();
        imported.load(targetRecord.readShell());
        for (int i = 0; i < 40; i++) helper.assertTrue(contents.equals(imported.getVfs().readFile("/home/player/archive" + i)), "all Unicode files transferred");
        var sourceRecord = com.malice.terminalcraft.persistence.PocketStorage.open(level, source);
        sourceRecord.compareAndSave(sourceRecord.revision(), new BashShell().save());
        helper.assertTrue(targetRecord.readShell().equals(imported.save()), "source changes cannot affect imported record");
        var before = target.save(new net.minecraft.nbt.CompoundTag());
        boolean rejected = false;
        try { com.malice.terminalcraft.persistence.PocketArchives.importHeld(level, target, archive); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && before.equals(target.save(new net.minecraft.nbt.CompoundTag())), "occupied target never overwritten");
        ItemStack blank = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        var blankBefore = blank.save(new net.minecraft.nbt.CompoundTag());
        rejected = false;
        try { com.malice.terminalcraft.persistence.PocketArchives.importHeld(level, blank, java.util.UUID.randomUUID()); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && blankBefore.equals(blank.save(new net.minecraft.nbt.CompoundTag())), "missing archive leaves blank item untouched");
        var node = level.getServer().getCommands().getDispatcher().getRoot().getChild("tcpocket");
        var commandSource = level.getServer().createCommandSourceStack();
        helper.assertTrue(node != null && !node.canUse(commandSource.withPermission(0))
                && node.canUse(commandSource.withPermission(2)), "archive commands registered and operator gated");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pocketMigrationKeepsLargeFilesAndRejectsStaleClones(GameTestHelper helper) throws Exception {
        var player = helper.makeMockPlayer();
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        ItemStack item = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        BashShell legacy = new BashShell();
        String content = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) helper.assertTrue(legacy.getVfs().writeFile("/home/player/file" + i, content), "large legacy file");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(item, legacy);
        var original = item.getTag().copy();
        player.setItemInHand(hand, item);
        var first = new com.malice.terminalcraft.shell.PocketShellComputer(player, hand);
        helper.assertTrue(first.storageError() == null && first.isStillHolding(), "legacy migration opens");
        helper.assertTrue(!item.getTag().contains("Shell"), "migrated inventory carries no filesystem");
        var id = item.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        var disk = net.minecraft.nbt.NbtIo.readCompressed(
                com.malice.terminalcraft.persistence.PocketComputerData.file(helper.getLevel(), id).toFile()).getCompound("data");
        helper.assertTrue(original.equals(disk.getCompound("LegacyBackup")), "full original verified on disk before source removal");
        for (int i = 0; i < 40; i++) helper.assertTrue(content.equals(first.getShell().getVfs().readFile("/home/player/file" + i)), "large files preserved");
        var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            packet.writeItem(item);
            helper.assertTrue(packet.readableBytes() < 512, "migrated item packet is bounded and small");
        } finally { packet.release(); }
        var otherPlayer = helper.makeMockPlayer();
        otherPlayer.setItemInHand(hand, item.copy());
        var second = new com.malice.terminalcraft.shell.PocketShellComputer(otherPlayer, hand);
        helper.assertTrue(second.isStillHolding(), "copied identity initially resolves same files");
        first.getShell().executeForResult("echo newest > /home/player/newest.txt");
        first.markShellChanged();
        helper.assertTrue(!second.isStillHolding(), "earlier cloned session is invalidated before another command");
        second.getShell().executeForResult("echo stale > /home/player/newest.txt");
        second.markShellChanged();
        var reopened = new com.malice.terminalcraft.shell.PocketShellComputer(otherPlayer, hand);
        helper.assertTrue("newest\n".equals(reopened.getShell().getVfs().readFile("/home/player/newest.txt")), "stale clone cannot overwrite newer files");
        ItemStack missing = item.copy();
        missing.getTag().putUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY, java.util.UUID.randomUUID());
        var before = missing.save(new net.minecraft.nbt.CompoundTag());
        var savedIdentity = item.getTag().getUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY);
        item.getTag().putUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY, java.util.UUID.randomUUID());
        helper.assertTrue(!first.isStillHolding(), "in-place identity mutation invalidates session");
        item.getTag().putUUID(com.malice.terminalcraft.persistence.PocketStorage.IDENTITY, savedIdentity);
        player.setItemInHand(hand, missing);
        var unavailable = new com.malice.terminalcraft.shell.PocketShellComputer(player, hand);
        helper.assertTrue(unavailable.storageError() != null && !unavailable.isStillHolding(), "missing world files refuse writable blank shell");
        helper.assertTrue(before.equals(missing.save(new net.minecraft.nbt.CompoundTag())), "missing-record error retains item");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void pocketMigrationFailuresPreserveSources(GameTestHelper helper) throws Exception {
        var identity = java.util.UUID.randomUUID();
        var file = com.malice.terminalcraft.persistence.PocketComputerData.file(helper.getLevel(), identity);
        java.nio.file.Files.createDirectories(file.getParent());
        byte[] corrupt = new byte[] {1, 2, 3, 4};
        java.nio.file.Files.write(file, corrupt, java.nio.file.StandardOpenOption.CREATE_NEW);
        try {
            boolean rejected = false;
            try { com.malice.terminalcraft.persistence.PocketComputerData.allocate(helper.getLevel(), identity); }
            catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "unreadable existing record is not allocated over");
            helper.assertTrue(java.util.Arrays.equals(corrupt, java.nio.file.Files.readAllBytes(file)), "unreadable original unchanged");
        } finally { java.nio.file.Files.delete(file); }
        ItemStack item = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        var legacy = new net.minecraft.nbt.CompoundTag();
        legacy.putString("CannotEncode", "漢".repeat(65_536));
        item.getOrCreateTag().put("Shell", legacy);
        var before = item.save(new net.minecraft.nbt.CompoundTag());
        boolean rejected = false;
        try { com.malice.terminalcraft.persistence.PocketStorage.open(helper.getLevel(), item); }
        catch (java.io.IOException expected) { rejected = true; }
        helper.assertTrue(rejected && before.equals(item.save(new net.minecraft.nbt.CompoundTag())),
                "failed lossless disk verification retains exact original item");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void pocketWorldRecordsShareDimensionsAndRejectCollisions(GameTestHelper helper) {
        var level = helper.getLevel();
        var identity = java.util.UUID.randomUUID();
        helper.assertTrue(com.malice.terminalcraft.persistence.PocketComputerData.find(level, identity) == null,
                "missing pocket lookup does not invent a blank computer");
        var record = com.malice.terminalcraft.persistence.PocketComputerData.allocate(level, identity);
        var shell = new BashShell();
        shell.executeForResult("echo retained > /home/player/world-pocket.txt");
        var snapshot = shell.save();
        helper.assertTrue(record.initialize(snapshot, snapshot), "new world pocket initializes once");
        var otherDimension = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(otherDimension != null, "test server has the nether dimension");
        var same = com.malice.terminalcraft.persistence.PocketComputerData.find(otherDimension, identity);
        helper.assertTrue(same == record, "dimension change resolves the same authoritative record");
        var collision = com.malice.terminalcraft.persistence.PocketComputerData.allocate(otherDimension, identity);
        helper.assertTrue(collision == record && !collision.initialize(new net.minecraft.nbt.CompoundTag(), null),
                "allocation of an existing identity cannot erase files");
        var oldRevision = record.revision();
        helper.assertTrue(same.compareAndSave(oldRevision, snapshot) != null, "current revision saves across dimensions");
        helper.assertTrue(record.compareAndSave(oldRevision, new net.minecraft.nbt.CompoundTag()) == null,
                "stale revision rejected across dimensions");
        helper.assertTrue(snapshot.equals(record.readShell()), "rejected overwrite retains complete shell");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void pocketMenuCannotOverwriteAReplacementHandheld(GameTestHelper helper) {
        var player = helper.makeMockPlayer();
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        ItemStack original = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        player.setItemInHand(hand, original);
        var computer = new com.malice.terminalcraft.shell.PocketShellComputer(player, hand);
        var menu = new com.malice.terminalcraft.menu.TerminalMenu(17, player.getInventory(), computer);
        helper.assertTrue(menu.stillValid(player), "menu starts bound to held pocket");
        computer.getShell().executeForResult("echo original > /home/player/original.txt");
        computer.markShellChanged();
        var savedOriginal = original.save(new net.minecraft.nbt.CompoundTag());
        ItemStack replacement = new ItemStack(ModRegistries.POCKET_TERMINAL.get());
        var replacementShell = new com.malice.terminalcraft.shell.BashShell();
        replacementShell.executeForResult("echo replacement > /home/player/replacement.txt");
        com.malice.terminalcraft.item.PocketTerminalItem.saveShell(replacement, replacementShell);
        var savedReplacement = replacement.save(new net.minecraft.nbt.CompoundTag());
        player.setItemInHand(hand, replacement);
        helper.assertTrue(!menu.stillValid(player), "another pocket in the same hand invalidates old menu");
        computer.getShell().executeForResult("echo stale > /home/player/stale.txt");
        computer.markShellChanged();
        computer.setLabel("stale label");
        helper.assertTrue(!computer.modemOpen(7) && !computer.modemTransmit(7, 0, "stale")
                && computer.modemReceive(5).isEmpty(), "stale host cannot use replacement modem");
        helper.assertTrue(savedOriginal.equals(original.save(new net.minecraft.nbt.CompoundTag()))
                && savedReplacement.equals(replacement.save(new net.minecraft.nbt.CompoundTag())),
                "both original and replacement data remain unchanged by stale actions");
        player.setItemInHand(hand, original.copy());
        helper.assertTrue(!menu.stillValid(player), "copied identity/NBT cannot rebind an old screen");
        player.setItemInHand(hand, ItemStack.EMPTY);
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original);
        helper.assertTrue(!menu.stillValid(player), "moving to the other hand does not rebind screen");
        var fresh = new com.malice.terminalcraft.shell.PocketShellComputer(player, net.minecraft.world.InteractionHand.OFF_HAND);
        helper.assertTrue(fresh.isStillHolding(), "fresh screen can open original in its new hand");
        fresh.getShell().executeForResult("echo fresh > /home/player/fresh.txt");
        fresh.markShellChanged();
        helper.assertTrue("fresh\n".equals(new com.malice.terminalcraft.shell.PocketShellComputer(player, net.minecraft.world.InteractionHand.OFF_HAND).getShell()
                .getVfs().readFile("/home/player/fresh.txt")), "fresh valid screen persists normally");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nasTierAndEightDriveCapacitySurviveEncodedReload(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        var execute = net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
        for (var driveItem : java.util.List.of(ModRegistries.SOLID_STATE_DRIVE_ITEM.get(),
                ModRegistries.ADVANCED_SOLID_STATE_DRIVE_ITEM.get(), ModRegistries.QUANTUM_SOLID_STATE_DRIVE_ITEM.get())) {
            for (int count : new int[] {1, 8}) {
                nas.load(new net.minecraft.nbt.CompoundTag());
                for (int slot = 0; slot < count; slot++) helper.assertTrue(nas.insertDrive(new ItemStack(driveItem)), "install capacity fixture");
                int itemCapacity = nas.totalItemCapacity();
                int fluidCapacity = nas.totalFluidCapacityMb();
                var cargo = new ItemStack(net.minecraft.world.item.Items.DIAMOND, itemCapacity);
                helper.assertTrue(nas.insertItemStack(cargo) == itemCapacity, "fill exact item capacity");
                helper.assertTrue(nas.insertItemStack(new ItemStack(net.minecraft.world.item.Items.DIAMOND)) == 0,
                        "one extra item rejected");
                var water = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, fluidCapacity);
                helper.assertTrue(nas.storageFluidHandler().fill(water, execute) == fluidCapacity, "fill exact fluid capacity");
                helper.assertTrue(nas.storageFluidHandler().fill(new net.minecraftforge.fluids.FluidStack(
                        net.minecraft.world.level.material.Fluids.WATER, 1), execute) == 0, "one extra millibucket rejected");
                try {
                    var bytes = new java.io.ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.writeCompressed(nas.saveWithoutMetadata(), bytes);
                    nas.load(net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes.toByteArray())));
                } catch (java.io.IOException error) { throw new AssertionError("NAS encoded reload failed", error); }
                helper.assertTrue(nas.itemCount("minecraft:diamond") == itemCapacity, "all bulk items survive real encoding");
                helper.assertTrue(nas.extractFirstItem(itemCapacity).getCount() == itemCapacity
                        && nas.extractFirstItem(1).isEmpty(), "cross-drive extraction returns full exact quantity once");
                helper.assertTrue(nas.storageFluidHandler().drain(fluidCapacity, execute).getAmount() == fluidCapacity
                        && nas.storageFluidHandler().drain(1, execute).isEmpty(), "cross-drive fluid drain conserves full exact quantity");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nasMalformedFluidAmountsCannotOverflowCapacity(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        ItemStack drive = new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get());
        var entries = new net.minecraft.nbt.ListTag();
        for (var fluid : java.util.List.of(net.minecraft.world.level.material.Fluids.WATER,
                net.minecraft.world.level.material.Fluids.LAVA)) {
            entries.add(new net.minecraftforge.fluids.FluidStack(fluid, Integer.MAX_VALUE).writeToNBT(new net.minecraft.nbt.CompoundTag()));
        }
        drive.getOrCreateTag().put(com.malice.terminalcraft.item.SolidStateDriveItem.TAG_FLUIDS, entries);
        nas.insertDrive(drive);
        var fluids = nas.storageFluidHandler();
        var execute = net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
        helper.assertTrue(fluids.fill(new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1), execute) == 0,
                "malformed quantities cannot overflow used-capacity accounting");
        helper.assertTrue(fluids.drain(Integer.MAX_VALUE, execute).getAmount() == nas.totalFluidCapacityMb(),
                "salvaged quantity bounded to drive capacity");
        helper.assertTrue(fluids.drain(Integer.MAX_VALUE, execute).isEmpty(), "over-capacity payload does not reappear after mutation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nasAmountDrainPreservesAndSeparatesTaggedFluidVariants(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        nas.insertDrive(new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get()));
        var fluids = nas.storageFluidHandler();
        var first = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 500);
        var firstTag = new net.minecraft.nbt.CompoundTag(); firstTag.putString("Batch", "first"); first.setTag(firstTag);
        var second = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 700);
        var secondTag = new net.minecraft.nbt.CompoundTag(); secondTag.putString("Batch", "second"); second.setTag(secondTag);
        var plain = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 900);
        var execute = net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
        var simulate = net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;
        helper.assertTrue(fluids.fill(first, execute) == 500 && fluids.fill(second, execute) == 700
                && fluids.fill(plain, execute) == 900, "three variants stored separately");
        var saved = nas.saveWithoutMetadata();
        var preview = fluids.drain(300, simulate);
        helper.assertTrue(preview.getAmount() == 300 && preview.isFluidEqual(first), "amount simulation preserves selected variant");
        helper.assertTrue(saved.equals(nas.saveWithoutMetadata()), "simulation preserves all stored data");
        var drained = fluids.drain(600, execute);
        helper.assertTrue(drained.getAmount() == 500 && drained.isFluidEqual(first), "amount drain stops at first variant without mixing");
        helper.assertTrue(fluids.drain(first, execute).isEmpty(), "exhausted variant cannot drain other batches");
        var request = second.copy(); request.setAmount(600);
        helper.assertTrue(fluids.drain(request, execute).getAmount() == 600, "explicit variant request drains only matching batch");
        nas.load(nas.saveWithoutMetadata());
        helper.setBlock(local.east(), ModRegistries.MATERIALIZER_BLOCK.get());
        var materializer = helper.getBlockEntity(local.east());
        var output = materializer.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER)
                .orElseThrow(IllegalStateException::new);
        nas.insertItemStack(new ItemStack(net.minecraft.world.item.Items.EMERALD, 3));
        var itemOutput = materializer.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .orElseThrow(IllegalStateException::new);
        var beforeRemoval = nas.saveWithoutMetadata();
        materializer.setRemoved();
        helper.assertTrue(output.drain(1000, execute).isEmpty() && itemOutput.extractItem(0, 64, false).isEmpty(),
                "removed materializer cannot access a still-live neighboring NAS through cached handlers");
        helper.assertTrue(beforeRemoval.equals(nas.saveWithoutMetadata()), "removed output leaves NAS contents intact");
        materializer.clearRemoved();
        materializer.reviveCaps();
        var remainder = output.drain(1000, execute);
        helper.assertTrue(remainder.isFluidEqual(second) && remainder.getAmount() == 100,
                "materializer amount drain preserves variant after reload");
        var untagged = output.drain(1000, execute);
        helper.assertTrue(untagged.isFluidEqual(plain) && untagged.getAmount() == 900,
                "untagged batch remains exact and separate");
        helper.assertTrue(output.drain(1000, execute).isEmpty(), "no fluid duplicated");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void removedNasRejectsCachedItemFluidAndDriveAccess(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        nas.insertDrive(new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get()));
        nas.insertItemStack(new ItemStack(net.minecraft.world.item.Items.DIAMOND, 20));
        var items = nas.storageItemHandler();
        var fluids = nas.storageFluidHandler();
        var water = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
        fluids.fill(water, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        var saved = nas.saveWithoutMetadata();
        nas.setRemoved(); // Exercise the unload/removal state while old callers retain direct references.
        for (boolean simulate : new boolean[] {true, false}) {
            helper.assertTrue(items.extractItem(0, 10, simulate).isEmpty(), "cached item extraction refused");
            helper.assertTrue(items.insertItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5), simulate).getCount() == 5,
                    "cached item insertion returns all unaccepted items");
            var action = simulate ? net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE
                    : net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
            helper.assertTrue(fluids.fill(water, action) == 0 && fluids.drain(water, action).isEmpty()
                    && fluids.drain(1000, action).isEmpty(), "cached fluid operations refused");
        }
        helper.assertTrue(!nas.insertDrive(new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get()))
                && nas.ejectLastDrive().isEmpty(), "removed controller cannot insert or eject media");
        nas.dropContents();
        helper.assertTrue(saved.equals(nas.saveWithoutMetadata()), "stale operations leave retained save state intact");
        // Restore the same instance as the game does on revival; valid access must recover.
        nas.clearRemoved();
        nas.reviveCaps();
        helper.assertTrue(nas.storageItemHandler().extractItem(0, 10, false).getCount() == 10,
                "revived controller accepts valid item transfers");
        helper.assertTrue(nas.storageFluidHandler().drain(1000,
                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).getAmount() == 1000,
                "revived controller accepts valid fluid transfers");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nasLargeCountsAndSimulatedExtractionStayExact(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        nas.insertDrive(new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get()));
        helper.assertTrue(nas.insertItemStack(new ItemStack(net.minecraft.world.item.Items.DIAMOND, 1000)) == 1000,
                "large logical count accepted");
        nas.load(nas.saveWithoutMetadata());
        helper.assertTrue(nas.itemCount("minecraft:diamond") == 1000, "integer item count survives persistence");
        var inventory = nas.storageItemHandler();
        helper.assertTrue(inventory.extractItem(0, 12, true).getCount() == 12
                && inventory.extractItem(0, 12, true).getCount() == 12 && nas.itemCount("minecraft:diamond") == 1000,
                "simulated extraction returns requested count without mutation");
        helper.assertTrue(inventory.extractItem(0, 12, false).getCount() == 12
                && nas.itemCount("minecraft:diamond") == 988, "executed extraction removes exactly returned count");
        var water = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 4000);
        var fluids = nas.storageFluidHandler();
        fluids.fill(water, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(fluids.drain(500, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE).getAmount() == 500,
                "fluid simulation returns only requested amount");
        helper.assertTrue(fluids.drain(500, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).getAmount() == 500
                && fluids.drain(9999, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).getAmount() == 3500,
                "fluid execution conserves exact total");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nasBreakPreservesDriveItemsAndFluidsExactlyOnce(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        ItemStack drive = new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get());
        com.malice.terminalcraft.item.SolidStateDriveItem.setLabel(drive, "recovery cargo");
        helper.assertTrue(nas.insertDrive(drive), "insert labeled SSD");
        helper.assertTrue(nas.insertItemStack(new ItemStack(net.minecraft.world.item.Items.DIAMOND, 32)) == 32,
                "store thirty-two diamonds");
        var water = new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
        helper.assertTrue(nas.storageFluidHandler().fill(water,
                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE) == 1000, "store water");
        var payload = nas.drive(0).save(new net.minecraft.nbt.CompoundTag());
        nas.load(nas.saveWithoutMetadata());
        helper.assertTrue(payload.equals(nas.drive(0).save(new net.minecraft.nbt.CompoundTag())), "drive survives controller reload");
        BlockPos absolute = helper.absolutePos(local);
        helper.assertTrue(helper.getLevel().destroyBlock(absolute, true), "NAS breaks");
        nas.dropContents();
        var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(absolute).inflate(1));
        var media = drops.stream().filter(entity -> entity.getItem().is(ModRegistries.SOLID_STATE_DRIVE_ITEM.get())).toList();
        helper.assertTrue(media.size() == 1 && payload.equals(media.get(0).getItem().save(new net.minecraft.nbt.CompoundTag())),
                "SSD drops once with exact label/items/fluids");
        helper.assertTrue(drops.stream().filter(entity -> entity.getItem().is(ModRegistries.NETWORK_ACCESS_STORAGE_ITEM.get()))
                .mapToInt(entity -> entity.getItem().getCount()).sum() == 1, "controller drops once separately");
        ItemStack recovered = media.get(0).getItem().copy();
        media.get(0).discard();
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var restored = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        helper.assertTrue(restored.insertDrive(recovered), "reinsert recovered SSD");
        ItemStack extracted = restored.extractFirstItem(64);
        helper.assertTrue(extracted.is(net.minecraft.world.item.Items.DIAMOND) && extracted.getCount() == 32,
                "all stored items recoverable");
        var drained = restored.storageFluidHandler().drain(water,
                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(drained.isFluidEqual(water) && drained.getAmount() == 1000, "all stored fluid recoverable");
        helper.assertTrue(restored.extractFirstItem(64).isEmpty() && restored.storageFluidHandler().drain(1000,
                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).isEmpty(), "no duplicate contents");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nasLoadKeepsEightSlotsAndRejectsInvalidMedia(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get());
        var nas = (NetworkAccessStorageBlockEntity) helper.getBlockEntity(local);
        var entries = new net.minecraft.nbt.ListTag();
        var valid = new ItemStack(ModRegistries.SOLID_STATE_DRIVE_ITEM.get(), 4).save(new net.minecraft.nbt.CompoundTag());
        valid.putInt("Slot", 7);
        entries.add(valid);
        entries.add(valid.copy());
        var invalid = new ItemStack(net.minecraft.world.item.Items.DIAMOND).save(new net.minecraft.nbt.CompoundTag());
        invalid.putInt("Slot", 0);
        entries.add(invalid);
        var outOfBounds = valid.copy(); outOfBounds.putInt("Slot", 99); entries.add(outOfBounds);
        for (int size : new int[] {0, -1, Integer.MAX_VALUE}) {
            var root = new net.minecraft.nbt.CompoundTag();
            var drives = new net.minecraft.nbt.CompoundTag();
            drives.putInt("Size", size); drives.put("Items", entries.copy()); root.put("Drives", drives);
            nas.load(root);
            helper.assertTrue(nas.installedDriveCount() == 1 && nas.drive(7).getCount() == 1 && nas.drive(0).isEmpty(),
                    "serialized size, duplicates, invalid media and stack counts cannot change physical slots");
            helper.assertTrue(nas.saveWithoutMetadata().getCompound("Drives").getInt("Size") == 8, "canonical slot count");
        }
        nas.load(new net.minecraft.nbt.CompoundTag());
        helper.assertTrue(nas.installedDriveCount() == 0, "loading empty state clears old media");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void survivalMachineDropsAndPickaxeTagsAreComplete(GameTestHelper helper) {
        ItemStack pickaxe = new ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE);
        for (var entry : ModRegistries.BLOCKS.getEntries()) {
            helper.assertTrue(entry.get().defaultBlockState().is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE),
                    entry.getId() + " has the pickaxe mining tag");
        }
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local.below(), net.minecraft.world.level.block.Blocks.STONE);
        for (String name : java.util.List.of("sensor_array", "redstone_sensor", "block_state_sensor", "inventory_sensor", "fluid_sensor", "energy_sensor", "entity_sensor", "machine_sensor", "environment_sensor", "network_sensor", "kinetic_sensor", "chemical_sensor", "network_access_storage", "materializer")) {
            var id = new net.minecraft.resources.ResourceLocation("terminalcraft", name);
            var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
            helper.assertTrue(block != null, name + " is registered");
            helper.setBlock(local, block);
            var state = helper.getBlockState(local);
            helper.assertTrue(pickaxe.isCorrectToolForDrops(state), name + " is harvestable with iron pickaxe");
            BlockPos absolute = helper.absolutePos(local);
            helper.assertTrue(helper.getLevel().destroyBlock(absolute, true), name + " breaks successfully");
            int dropped = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(absolute).inflate(1)).stream()
                    .map(net.minecraft.world.entity.item.ItemEntity::getItem)
                    .filter(stack -> stack.is(block.asItem())).mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(dropped == 1, name + " drops exactly one matching block item, found " + dropped);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void terminalRejectsReplacementFloppyAndRestoresOriginal(GameTestHelper helper) {
        verifySwap(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void turtleRejectsReplacementFloppyAndRestoresOriginal(GameTestHelper helper) {
        verifySwap(helper, true);
    }

    private static void verifySwap(GameTestHelper helper, boolean turtle) {
        BlockPos computer = new BlockPos(2, 2, 2);
        BlockPos drivePos = computer.east();
        helper.setBlock(computer, turtle ? ModRegistries.TURTLE_BLOCK.get() : ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(drivePos, ModRegistries.DISK_DRIVE_BLOCK.get());
        DiskDriveBlockEntity drive = (DiskDriveBlockEntity) helper.getBlockEntity(drivePos);
        BashShell shell = turtle ? ((TurtleBlockEntity) helper.getBlockEntity(computer)).getShell()
                : ((TerminalBlockEntity) helper.getBlockEntity(computer)).getShell();
        drive.setDisk(new ItemStack(ModRegistries.FLOPPY_DISK.get()));
        helper.assertTrue(shell.executeForResult("mount").exitCode() == 0, "original media mounts");
        shell.executeForResult("echo original-edit > /disk/keep.txt");
        var savedShell = shell.save();
        ItemStack original = drive.ejectDisk();
        drive.setDisk(new ItemStack(ModRegistries.FLOPPY_DISK.get()));
        var replacementContents = drive.readMedia().copy();
        shell.load(savedShell);
        helper.assertTrue(shell.executeForResult("disk sync").exitCode() != 0, "replacement media must reject sync");
        helper.assertTrue(shell.executeForResult("umount").exitCode() != 0, "replacement media must reject unmount write");
        helper.assertTrue(replacementContents.equals(drive.readMedia()), "replacement floppy must remain byte-for-byte unchanged");
        helper.assertTrue("original-edit\n".equals(shell.getVfs().readFile("/disk/keep.txt")), "pending data survives rejection/reload");
        drive.ejectDisk();
        BlockPos otherDrivePos = computer.west();
        helper.setBlock(otherDrivePos, ModRegistries.DISK_DRIVE_BLOCK.get());
        DiskDriveBlockEntity otherDrive = (DiskDriveBlockEntity) helper.getBlockEntity(otherDrivePos);
        otherDrive.setDisk(original);
        helper.assertTrue(shell.executeForResult("disk sync").exitCode() != 0,
                "moving the original floppy to another adjacent drive must not redirect an existing mount");
        original = otherDrive.ejectDisk();
        drive.setDisk(original);
        helper.assertTrue(shell.executeForResult("disk sync").exitCode() == 0, "original unchanged media accepts retry");
        helper.assertTrue(shell.executeForResult("umount").exitCode() == 0, "successful sync advances the mount revision");
        BashShell.VirtualFileSystem media = new BashShell.VirtualFileSystem();
        media.load(FloppyDiskItem.getVfsTag(drive.getDisk()));
        helper.assertTrue("original-edit\n".equals(media.readFile("/keep.txt")), "original disk receives pending data");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void sharedDriveRejectsStaleWriterAndAllowsExplicitDiscard(GameTestHelper helper) {
        BlockPos left = new BlockPos(2, 2, 2);
        BlockPos middle = left.east();
        BlockPos right = middle.east();
        helper.setBlock(left, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(right, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(middle, ModRegistries.DISK_DRIVE_BLOCK.get());
        DiskDriveBlockEntity drive = (DiskDriveBlockEntity) helper.getBlockEntity(middle);
        drive.setDisk(new ItemStack(ModRegistries.FLOPPY_DISK.get()));
        BashShell first = ((TerminalBlockEntity) helper.getBlockEntity(left)).getShell();
        BashShell second = ((TerminalBlockEntity) helper.getBlockEntity(right)).getShell();
        helper.assertTrue(first.executeForResult("mount").exitCode() == 0
                && second.executeForResult("mount").exitCode() == 0, "both computers mount same revision");
        first.executeForResult("echo winner > /disk/shared.txt");
        helper.assertTrue(first.executeForResult("disk sync").exitCode() == 0, "first writer commits");
        var committed = drive.readMedia().copy();
        second.executeForResult("echo stale > /disk/shared.txt");
        helper.assertTrue(second.executeForResult("disk sync").exitCode() != 0, "stale writer cannot overwrite newer contents");
        helper.assertTrue(committed.equals(drive.readMedia()), "committed disk remains intact");
        helper.assertTrue(second.executeForResult("cat /disk/shared.txt > /home/player/recovered.txt").exitCode() == 0,
                "recovery through the supported shell redirection must succeed");
        helper.assertTrue(second.executeForResult("umount --discard").exitCode() == 0, "explicit discard releases stale mount");
        helper.assertTrue("stale\n".equals(second.getVfs().readFile("/home/player/recovered.txt")), "local recovery survives discard");
        helper.assertTrue(second.executeForResult("mount").exitCode() == 0, "fresh mount sees latest disk");
        helper.assertTrue("winner\n".equals(second.getVfs().readFile("/disk/shared.txt")), "fresh mount reads winner");
        helper.succeed();
    }
}
