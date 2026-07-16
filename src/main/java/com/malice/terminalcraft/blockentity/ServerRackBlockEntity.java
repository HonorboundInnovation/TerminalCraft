package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.ServerRackBlock;
import com.malice.terminalcraft.device.AdjacentForgeDeviceAccess;
import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceAuthorization;
import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.item.RackModuleItem;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.server.RackModuleType;
import com.malice.terminalcraft.server.ServerJobScheduler;
import com.malice.terminalcraft.server.ServerCabinetTopology;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.ShellComputer;
import com.malice.terminalcraft.shell.TerminalHost;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Two-bay rack backplane whose installed blades provide compute and routing behavior. */
public class ServerRackBlockEntity extends BlockEntity implements MenuProvider, TerminalHost, ShellComputer {
    public static final int BAY_COUNT = 2;

    private final BashShell shell = new BashShell();
    private final ServerJobScheduler scheduler = new ServerJobScheduler();
    private final ItemStack[] modules = {ItemStack.EMPTY, ItemStack.EMPTY};
    private UUID deviceId = DeviceIdentity.create();
    private String label = "server-rack";

    public ServerRackBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.SERVER_RACK_BLOCK_ENTITY.get(), pos, state);
        shell.setHost(this);
    }

    public RackModuleType moduleType(int bay) {
        if (bay < 0 || bay >= BAY_COUNT || modules[bay].isEmpty()) return RackModuleType.EMPTY;
        return modules[bay].getItem() instanceof RackModuleItem item ? item.moduleType() : RackModuleType.EMPTY;
    }

    public boolean hasServerModule() {
        return moduleType(0) == RackModuleType.SERVER || moduleType(1) == RackModuleType.SERVER;
    }

    public boolean hasRouterModule() {
        return moduleType(0) == RackModuleType.ROUTER || moduleType(1) == RackModuleType.ROUTER;
    }

    public int localServerModules() {
        int count = 0;
        for (int bay = 0; bay < BAY_COUNT; bay++) if (moduleType(bay) == RackModuleType.SERVER) count++;
        return count;
    }

    public boolean installModule(int bay, ItemStack source, boolean creative) {
        if (bay < 0 || bay >= BAY_COUNT || !modules[bay].isEmpty()
                || !(source.getItem() instanceof RackModuleItem)) return false;
        modules[bay] = source.copyWithCount(1);
        if (!creative) source.shrink(1);
        modulesChanged();
        return true;
    }

    public ItemStack removeModule(int bay) {
        if (bay < 0 || bay >= BAY_COUNT) return ItemStack.EMPTY;
        ItemStack removed = modules[bay];
        modules[bay] = ItemStack.EMPTY;
        if (!removed.isEmpty()) modulesChanged();
        return removed;
    }

    public List<ItemStack> removeAllModules() {
        List<ItemStack> removed = new ArrayList<>();
        for (int bay = 0; bay < BAY_COUNT; bay++) {
            if (!modules[bay].isEmpty()) removed.add(modules[bay]);
            modules[bay] = ItemStack.EMPTY;
        }
        return removed;
    }

    private void modulesChanged() {
        syncModuleState();
        markShellChanged();
    }

    private void syncModuleState() {
        if (level == null || !(getBlockState().getBlock() instanceof ServerRackBlock)) return;
        BlockState next = getBlockState()
                .setValue(ServerRackBlock.LOWER_MODULE, moduleType(0))
                .setValue(ServerRackBlock.UPPER_MODULE, moduleType(1));
        if (next != getBlockState()) level.setBlock(worldPosition, next, 3);
    }

    public String cabinetStatus() {
        ServerCabinetTopology.Cabinet cabinet = ServerCabinetTopology.resolve(level, worldPosition);
        return "Cabinet: racks=" + cabinet.racks().size() + "/3 modules=" + cabinet.moduleCount()
                + "/" + cabinet.bayCount() + " servers=" + cabinet.serverModules()
                + " routers=" + cabinet.routerModules();
    }

    @Override public BashShell getShell() { return shell; }
    @Override public Component getDisplayName() { return Component.translatable("block.terminalcraft.server_rack"); }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (!hasServerModule()) return null;
        shell.setHost(this);
        return new TerminalMenu(containerId, inventory, this);
    }

    @Override
    public DeviceAccess deviceAccess(DeviceCallContext context) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        return new AdjacentForgeDeviceAccess(ServerDeviceManager.access(serverLevel.getServer(), context),
                serverLevel, worldPosition);
    }

    public UUID getDeviceId() { return deviceId; }

    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    @Override public String getLabel() { return label; }
    @Override public void setLabel(String value) {
        label = value == null || value.isBlank() ? "server-rack" : value.trim();
        markShellChanged();
    }
    @Override public int getRedstoneInput(String side) { return -1; }
    @Override public int getRedstoneOutput(String side) { return -1; }
    @Override public boolean setRedstoneOutput(String side, int power) { return false; }
    @Override public List<String> redstoneSides() { return List.of(); }

    @Override
    public List<String> listPeripherals() {
        List<String> result = new ArrayList<>();
        if (hasServerModule()) {
            result.add("internal:job_scheduler");
            result.add("internal:server_console");
        }
        if (hasRouterModule()) result.add("internal:rednet_router");
        return List.copyOf(result);
    }

    @Override
    public String serverSubmit(DeviceCallContext context, String command) {
        if (!hasServerModule() || level == null || level.isClientSide) return "";
        try {
            ServerJobScheduler.Job job = scheduler.submit(context, command, level.getGameTime());
            setChanged();
            return job.id().toString();
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return "";
        }
    }

    @Override public List<String> serverJobs() {
        return hasServerModule() ? scheduler.list().stream().map(ServerRackBlockEntity::formatJob).toList() : List.of();
    }
    @Override public String serverJob(String id) {
        if (!hasServerModule()) return "";
        UUID uuid = parseUuid(id);
        ServerJobScheduler.Job job = uuid == null ? null : scheduler.get(uuid);
        return job == null ? "" : formatJob(job);
    }
    @Override public List<String> serverJobs(DeviceCallContext context) {
        if (!hasServerModule() || context == null) return List.of();
        return scheduler.list().stream()
                .filter(job -> DeviceAuthorization.owns(context, job.context().principal()))
                .map(ServerRackBlockEntity::formatJob).toList();
    }
    @Override public String serverJob(DeviceCallContext context, String id) {
        if (!hasServerModule() || context == null) return "";
        UUID uuid = parseUuid(id);
        ServerJobScheduler.Job job = uuid == null ? null : scheduler.get(uuid);
        return job == null || !DeviceAuthorization.owns(context, job.context().principal())
                ? "" : formatJob(job);
    }
    @Override public boolean serverCancel(DeviceCallContext context, String id) {
        UUID uuid = parseUuid(id);
        if (!hasServerModule() || uuid == null || level == null || level.isClientSide) return false;
        boolean cancelled = scheduler.cancel(uuid, context, level.getGameTime());
        if (cancelled) setChanged();
        return cancelled;
    }
    @Override public int serverQueuedJobs() { return hasServerModule() ? scheduler.queuedCount() : -1; }
    @Override public String serverSchedulerDiagnostics() {
        if (!hasServerModule()) return "";
        ServerJobScheduler.Diagnostics value = scheduler.diagnostics();
        return "tick=" + value.tick() + " budget=" + value.budget()
                + " executed=" + value.executed() + " eligible=" + value.eligible()
                + " deferred=" + value.deferred() + " queued=" + value.queued()
                + " running=" + value.running() + " retained_finished=" + value.retainedFinished();
    }

    private static String formatJob(ServerJobScheduler.Job job) {
        String result = job.id() + " " + job.state().name().toLowerCase()
                + " exit=" + job.exitCode() + " owner=" + job.owner() + " command=" + job.command();
        return job.lastError().isBlank() ? result : result + " error=" + job.lastError();
    }

    @Nullable
    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    @Override
    public void markShellChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.putString("Label", label);
        tag.put("Shell", shell.save());
        tag.put("Scheduler", scheduler.save());
        ListTag savedModules = new ListTag();
        for (int bay = 0; bay < BAY_COUNT; bay++) {
            if (modules[bay].isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putByte("Bay", (byte) bay);
            entry.put("Stack", modules[bay].save(new CompoundTag()));
            savedModules.add(entry);
        }
        tag.put("Modules", savedModules);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        if (tag.contains("Label", Tag.TAG_STRING)) label = tag.getString("Label");
        if (tag.contains("Shell", Tag.TAG_COMPOUND)) shell.load(tag.getCompound("Shell"));
        if (tag.contains("Scheduler", Tag.TAG_COMPOUND)) scheduler.load(tag.getCompound("Scheduler"));
        modules[0] = ItemStack.EMPTY;
        modules[1] = ItemStack.EMPTY;
        if (tag.contains("Modules", Tag.TAG_LIST)) {
            ListTag savedModules = tag.getList("Modules", Tag.TAG_COMPOUND);
            for (int i = 0; i < savedModules.size(); i++) {
                CompoundTag entry = savedModules.getCompound(i);
                int bay = entry.getByte("Bay");
                if (bay >= 0 && bay < BAY_COUNT) modules[bay] = ItemStack.of(entry.getCompound("Stack"));
            }
        } else if (tag.contains("Shell", Tag.TAG_COMPOUND) || tag.contains("Scheduler", Tag.TAG_COMPOUND)) {
            // Existing monolithic racks migrate to one server blade without losing their shell/jobs.
            modules[0] = new ItemStack(ModRegistries.SERVER_BLADE.get());
        }
        shell.setHost(this);
    }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        if (tag.contains("Shell", CompoundTag.TAG_COMPOUND)
                && !com.malice.terminalcraft.network.ShellSyncPolicy.isAdmissible(tag.getCompound("Shell"))) {
            tag.remove("Shell");
        }
        return tag;
    }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }
    @Override public void onLoad() {
        super.onLoad();
        syncModuleState();
    }
    @Override public void setRemoved() {
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ServerRackBlockEntity rack) {
        if (!(level instanceof ServerLevel)) return;
        if (rack.hasServerModule() || rack.hasRouterModule()) {
            ServerDeviceManager.ensureRegistered(rack, rack.deviceId, rack.getDeviceAddress(), rack);
        }
        int budget = rack.localServerModules();
        int executed = rack.scheduler.tick(level.getGameTime(), budget, job -> {
            rack.shell.execute(job.command(), job.context());
            return rack.shell.getLastExitCode();
        });
        if (executed > 0) rack.markShellChanged();
    }
}
