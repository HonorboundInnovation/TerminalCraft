package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.item.ComputerBlockItem;
import com.malice.terminalcraft.persistence.ComputerRecoveryData;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.ShellComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;

@GameTestHolder("terminalcraft")
public final class ComputerRecoveryGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rackBreakRestoresFilesAndJobsWithoutDuplicatingBlades(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        var block = ModRegistries.SERVER_RACK_BLOCK.get();
        helper.setBlock(local, block);
        var rack = (ServerRackBlockEntity) helper.getBlockEntity(local);
        helper.assertTrue(rack.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false)
                && rack.installModule(1, new ItemStack(ModRegistries.ROUTER_BLADE.get()), false), "rack fixture blades installed");
        rack.getShell().executeForResult("echo rack-file > /home/player/keep.txt");
        String job = rack.serverSubmit(com.malice.terminalcraft.device.DeviceCallContext.readOnly("rack-recovery-test"), "echo queued");
        helper.assertTrue(!job.isBlank(), "queued job fixture");
        var before = rack.saveWithoutMetadata();
        var pos = helper.absolutePos(local);
        var oldIds = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(helper.getLevel().destroyBlock(pos, true), "rack breaks normally");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .filter(drop -> !oldIds.contains(drop.getUUID())).toList();
        var racks = drops.stream().filter(drop -> drop.getItem().is(block.asItem())).toList();
        helper.assertTrue(racks.size() == 1
                && drops.stream().filter(drop -> drop.getItem().is(ModRegistries.SERVER_BLADE.get())).mapToInt(drop -> drop.getItem().getCount()).sum() == 1
                && drops.stream().filter(drop -> drop.getItem().is(ModRegistries.ROUTER_BLADE.get())).mapToInt(drop -> drop.getItem().getCount()).sum() == 1, "one rack and each blade drop once");
        ItemStack item = racks.get(0).getItem().copy();
        ItemStack replay = item.copy();
        for (var drop : drops) drop.discard();
        var record = ComputerRecoveryData.get(helper.getLevel(), item.getTag().getUUID(ComputerBlockItem.COMPUTER));
        var payload = record.recover(item.getTag().getUUID(ComputerBlockItem.TICKET), "terminalcraft:server_rack");
        helper.assertTrue(payload != null && payload.contains("Modules", net.minecraft.nbt.Tag.TAG_LIST)
                && payload.getList("Modules", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()
                && before.getCompound("Scheduler").equals(payload.getCompound("Scheduler")), "ticket holds jobs and explicit empty modern module list");
        helper.assertTrue(((BlockItem) item.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos,
                Direction.NORTH, item, Direction.UP)).consumesAction(), "rack replacement succeeds");
        var restored = (ServerRackBlockEntity) helper.getBlockEntity(local);
        helper.assertTrue(restored.getDeviceId().equals(before.getUUID("DeviceId"))
                && "rack-file\n".equals(restored.getShell().getVfs().readFile("/home/player/keep.txt"))
                && before.getCompound("Scheduler").equals(restored.saveWithoutMetadata().getCompound("Scheduler")), "identity files and queued job preserved");
        helper.assertTrue(!restored.hasServerModule() && !restored.hasRouterModule(), "replacement does not duplicate modules");
        helper.assertTrue(restored.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false)
                && restored.serverQueuedJobs() == 1, "reinstalled compute blade sees retained queue");
        helper.setBlock(local.east(), Blocks.AIR);
        helper.assertTrue(!((BlockItem) replay.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos.east(),
                Direction.NORTH, replay, Direction.UP)).consumesAction(), "copied rack ticket cannot replay");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void damagedComputerBreakAndReplacementKeepRawDataAndRefuseReplay(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        for (var block : java.util.List.of(ModRegistries.TERMINAL_BLOCK.get(), ModRegistries.TURTLE_BLOCK.get())) {
            for (boolean wrongType : new boolean[] {false, true}) {
                helper.setBlock(local, Blocks.AIR);
                helper.setBlock(local, block);
                var entity = helper.getBlockEntity(local);
                var saved = entity.saveWithoutMetadata();
                if (wrongType) saved.putString("Shell", "exact invalid outer payload");
                else saved.getCompound("Shell").getCompound("Vfs").putString("/damaged.txt", "exact invalid filesystem entry");
                entity.load(saved);
                var raw = entity.saveWithoutMetadata().get("Shell").copy();
                helper.assertTrue(((ShellComputer) entity).getShell().isFileRecoveryRequired(), "source starts protected");
                var pos = helper.absolutePos(local);
                var oldIds = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                        .map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
                helper.assertTrue(helper.getLevel().destroyBlock(pos, true), "ordinary damaged computer break succeeds");
                var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                        .filter(drop -> !oldIds.contains(drop.getUUID()) && drop.getItem().is(block.asItem())).toList();
                helper.assertTrue(drops.size() == 1, "one recovery item for damaged machine");
                ItemStack item = drops.get(0).getItem().copy();
                drops.get(0).discard();
                ItemStack replay = item.copy();
                var ticket = item.getTag().getUUID(ComputerBlockItem.TICKET);
                var record = ComputerRecoveryData.get(helper.getLevel(), item.getTag().getUUID(ComputerBlockItem.COMPUTER));
                String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item.getItem()).toString();
                var reloaded = ComputerRecoveryData.load(record.save(new CompoundTag()));
                helper.assertTrue(raw.equals(reloaded.recover(ticket, itemId).get("Shell")), "recovery record codec retains exact malformed tag");
                var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    packet.writeItem(item);
                    helper.assertTrue(packet.readableBytes() < 512 && !item.getTag().contains("Shell"), "item carries reference, not malformed payload");
                } finally { packet.release(); }
                helper.assertTrue(((BlockItem) item.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos,
                        Direction.NORTH, item, Direction.UP)).consumesAction(), "recovery item restores block");
                var placed = helper.getBlockEntity(local);
                var shell = ((ShellComputer) placed).getShell();
                helper.assertTrue(raw.equals(placed.saveWithoutMetadata().get("Shell")) && shell.isFileRecoveryRequired()
                        && shell.runScriptText("echo unsafe") != 0, "replacement remains protected with exact original data");
                helper.assertTrue(record.recover(ticket, itemId) == null, "ticket consumed after placement");
                helper.assertTrue(raw.equals(record.save(new CompoundTag()).getCompound("Payload").get("Shell")), "consumed record retains raw recovery evidence");
                helper.setBlock(local.east(), Blocks.AIR);
                helper.assertTrue(!((BlockItem) replay.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos.east(),
                        Direction.NORTH, replay, Direction.UP)).consumesAction(), "copied ticket cannot create a second machine");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void largeComputerRecoveryLivesOnDiskNotInItemPackets(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.TERMINAL_BLOCK.get());
        var terminal = (TerminalBlockEntity) helper.getBlockEntity(local);
        String content = "x".repeat(60_000);
        for (int index = 0; index < 40; index++) helper.assertTrue(
                terminal.getShell().getVfs().writeFile("/home/player/large-" + index, content), "large fixture file written");
        helper.assertTrue(!com.malice.terminalcraft.network.ShellSyncPolicy.isAdmissible(terminal.getShell().save()),
                "fixture exceeds ordinary shell packet budget");
        BlockPos pos = terminal.getBlockPos();
        helper.getLevel().destroyBlock(pos, true);
        var entity = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1)).stream()
                .filter(e -> e.getItem().is(ModRegistries.TERMINAL_BLOCK.get().asItem())).findFirst().orElseThrow();
        ItemStack stack = entity.getItem().copy();
        entity.discard();
        var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            packet.writeItem(stack);
            helper.assertTrue(packet.readableBytes() < 512, "large computer uses a small item packet");
        } finally { packet.release(); }
        helper.getLevel().getServer().overworld().getDataStorage().save();
        java.util.UUID id = stack.getTag().getUUID(ComputerBlockItem.COMPUTER);
        var file = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve("terminalcraft_computer_" + id + ".dat").toFile();
        try {
            var disk = net.minecraft.nbt.NbtIo.readCompressed(file);
            var record = ComputerRecoveryData.load(disk.getCompound("data"));
            helper.assertTrue(record.recover(stack.getTag().getUUID(ComputerBlockItem.TICKET), "terminalcraft:terminal") != null,
                    "large recovery record was saved and can be read from disk");
        } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        helper.assertTrue(((BlockItem) stack.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos,
                Direction.NORTH, stack, Direction.UP)).consumesAction(), "large computer restores");
        var restored = (TerminalBlockEntity) helper.getBlockEntity(local);
        for (int index = 0; index < 40; index++) helper.assertTrue(content.equals(
                restored.getShell().getVfs().readFile("/home/player/large-" + index)), "all large files restored intact");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void terminalBreakItemRestoreAndReplayRejection(GameTestHelper helper) { verify(helper, false); }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void turtleBreakRestoresComputerWithoutDuplicatingInventory(GameTestHelper helper) { verify(helper, true); }

    private static void verify(GameTestHelper helper, boolean mobile) {
        BlockPos local = new BlockPos(3, 2, 3);
        var block = mobile ? ModRegistries.TURTLE_BLOCK.get() : ModRegistries.TERMINAL_BLOCK.get();
        helper.setBlock(local, block);
        var entity = helper.getBlockEntity(local);
        ShellComputer computer = (ShellComputer) entity;
        computer.getShell().executeForResult("echo recovery-ok > /home/player/keep.txt");
        CompoundTag original = entity.saveWithoutMetadata();
        original.putString("Label", "recovery-proof");
        if (mobile) { original.putInt("Fuel", 37); original.putBoolean("FuelUnlimited", false); }
        entity.load(original);
        if (mobile) entity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new)
                .insertItem(0, new ItemStack(Items.DIAMOND, 3), false);
        BlockPos pos = entity.getBlockPos();
        helper.assertTrue(helper.getLevel().destroyBlock(pos, true), "normal world break succeeds");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
        var computerDrops = drops.stream().filter(e -> e.getItem().is(block.asItem())).toList();
        helper.assertTrue(computerDrops.size() == 1, "one computer item drops");
        ItemStack stack = computerDrops.get(0).getItem().copy();
        computerDrops.get(0).discard();
        helper.assertTrue(stack.getTag().hasUUID(ComputerBlockItem.TICKET) && !stack.getTag().contains("BlockEntityTag"), "item carries a small recovery reference, not shell data");
        ItemStack replay = stack.copy();
        var savedRecord = ComputerRecoveryData.get(helper.getLevel(), stack.getTag().getUUID(ComputerBlockItem.COMPUTER));
        var restoredRecord = ComputerRecoveryData.load(savedRecord.save(new CompoundTag()));
        helper.assertTrue(restoredRecord.recover(stack.getTag().getUUID(ComputerBlockItem.TICKET),
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()) != null, "record survives its persistent codec");
        helper.setBlock(local, Blocks.STONE);
        helper.assertTrue(!((BlockItem) stack.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos,
                Direction.NORTH, stack, Direction.UP)).consumesAction(), "obstruction does not spend ticket");
        helper.setBlock(local, Blocks.AIR);
        helper.assertTrue(((BlockItem) stack.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), pos,
                Direction.NORTH, stack, Direction.UP)).consumesAction(), "original item restores its computer");
        var placed = helper.getBlockEntity(local);
        helper.assertTrue(placed.saveWithoutMetadata().getUUID("DeviceId").equals(original.getUUID("DeviceId")), "identity preserved");
        helper.assertTrue(placed.saveWithoutMetadata().getString("Label").equals("recovery-proof"), "label preserved");
        helper.assertTrue(((ShellComputer) placed).getShell().executeForResult("cat /home/player/keep.txt").outputLines().contains("recovery-ok"), "files preserved");
        if (mobile) {
            helper.assertTrue(((TurtleBlockEntity) placed).fuelBalance() == 37, "fuel preserved");
            var inventory = placed.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
            for (int slot = 0; slot < 16; slot++) helper.assertTrue(inventory.getStackInSlot(slot).isEmpty(), "contents are not also embedded in recovered turtle");
            int diamonds = drops.stream().filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(diamonds == 3, "inventory drops separately exactly once");
        }
        BlockPos other = pos.north();
        helper.setBlock(local.north(), Blocks.AIR);
        helper.assertTrue(!((BlockItem) replay.getItem()).place(new DirectionalPlaceContext(helper.getLevel(), other,
                Direction.NORTH, replay, Direction.UP)).consumesAction(), "copied ticket cannot restore another computer");
        helper.assertTrue(helper.getBlockState(local.north()).isAir() && replay.getCount() == 1, "rejected replay neither places nor consumes item");
        helper.succeed();
    }
}
