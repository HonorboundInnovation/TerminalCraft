package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.device.MonitorDevice;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.device.TerminalBuffer;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Multi-line text display peripheral. Written by adjacent terminals via shell.
 */
public class MonitorBlockEntity extends BlockEntity implements MonitorDevice, MenuProvider {
    public static final int MAX_LINES = 20;
    public static final int MAX_LINE_LEN = 40;

    private final List<String> lines = new ArrayList<>();
    private UUID deviceId = DeviceIdentity.create();
    private String title = "Monitor";
    private int foregroundColor = 0x66FF99;
    private int backgroundColor = 0x050A05;
    private String registeredWallSignature = "";
    private final TerminalBuffer terminalSurface =
            new com.malice.terminalcraft.device.TerminalBuffer(MAX_LINE_LEN, MAX_LINES);

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.MONITOR_BLOCK_ENTITY.get(), pos, state);
        syncLegacyPaletteToSurface();
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    @Override
    public int maxLines() {
        return MAX_LINES;
    }

    @Override
    public int maxLineLength() {
        return MAX_LINE_LEN;
    }

    @Override
    public String title() {
        return getTitle();
    }

    @Override
    public List<String> lines() {
        return getLines();
    }

    public List<String> getLines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    @Override
    public TerminalBuffer terminalSurface() {
        return terminalSurface;
    }

    public String getTitle() {
        return title;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Monitor Configuration");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new com.malice.terminalcraft.menu.DisplayDiagnosticsMenu(containerId, inventory, this);
    }

    /**
     * Client render snapshot for the complete connected wall. Only the visual top-left tile is
     * marked as the anchor, allowing the block-entity renderer to paint one seamless canvas.
     */
    public WallRenderState wallRenderState() {
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(this);
        MonitorBlockEntity palette = group.anchor();
        TerminalBuffer surface = new MonitorWallSurface(group);
        return new WallRenderState(group.anchor() == this, group.width(), group.height(),
                new MonitorGroupDevice(this).lines(), palette.foregroundColor(), palette.backgroundColor(), surface);
    }

    public record WallRenderState(boolean anchor, int width, int height, List<String> lines,
                                  int foregroundColor, int backgroundColor, TerminalBuffer surface) {}

    /** Public wall geometry boundary used by optional display integrations. */
    public int wallColumns() {
        return new MonitorGroupDevice(this).maxLineLength();
    }

    /** Public wall geometry boundary used by optional display integrations. */
    public int wallRows() {
        return new MonitorGroupDevice(this).maxLines();
    }

    /** Stable top-left owner shared by optional integrations and the wall renderer. */
    public MonitorBlockEntity wallAnchor() {
        return MonitorGroupDevice.discover(this).anchor();
    }

    /** Current visual text scale shared by every tile in the connected wall. */
    public double wallTextScale() {
        return MonitorGroupDevice.discover(this).anchor().terminalSurface().textScale();
    }

    /** Current default text color shared by every tile in the connected wall. */
    public int wallForegroundColor() {
        return MonitorGroupDevice.discover(this).anchor().foregroundColor();
    }

    /**
     * Applies one persisted, synchronized appearance setting to the complete connected wall.
     * Keeping a copy on every tile means the setting survives wall splits and anchor changes.
     */
    public void configureWallAppearance(double textScale, int foreground) {
        if (!Double.isFinite(textScale) || textScale < 0.5 || textScale > 5.0
                || textScale * 2 != Math.rint(textScale * 2)) {
            throw new IllegalArgumentException("text scale must be from 0.5 to 5.0 in increments of 0.5");
        }
        if (foreground < 0 || foreground > 0xFFFFFF) {
            throw new IllegalArgumentException("foreground color must be a 24-bit RGB value");
        }
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(this);
        for (MonitorBlockEntity tile : group.tiles()) {
            tile.terminalSurface.setTextScale(textScale);
            tile.foregroundColor = foreground;
            tile.syncLegacyPaletteToSurface();
            tile.setChangedAndSync();
        }
    }

    /** Writes one zero-based row across the complete connected wall. */
    public void setWallLine(int row, String text) {
        new MonitorGroupDevice(this).setLine(row, text);
    }

    /** World-space bounds for Create's Display Link target highlighting. */
    public AABB wallBounds() {
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(this);
        BlockPos min = group.tiles().stream().map(MonitorBlockEntity::getBlockPos)
                .reduce((a, b) -> new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()))).orElse(worldPosition);
        BlockPos max = group.tiles().stream().map(MonitorBlockEntity::getBlockPos)
                .reduce((a, b) -> new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                        Math.max(a.getZ(), b.getZ()))).orElse(worldPosition);
        return new AABB(min, max.offset(1, 1, 1));
    }

    @Override
    public void setTitle(String title) {
        this.title = title == null || title.isBlank() ? "Monitor" : title.trim();
        if (this.title.length() > 32) {
            this.title = this.title.substring(0, 32);
        }
        setChangedAndSync();
    }

    @Override
    public int foregroundColor() {
        return foregroundColor;
    }

    @Override
    public int backgroundColor() {
        return backgroundColor;
    }

    @Override
    public void setPalette(int foreground, int background) {
        foregroundColor = foreground & 0xFFFFFF;
        backgroundColor = background & 0xFFFFFF;
        syncLegacyPaletteToSurface();
        setChangedAndSync();
    }

    @Override
    public void setLine(int row, String text) {
        if (row < 0 || row >= MAX_LINES) {
            throw new IllegalArgumentException("monitor row must be from 0 to " + (MAX_LINES - 1));
        }
        String safe = sanitize(text);
        while (lines.size() <= row) lines.add("");
        lines.set(row, safe);
        terminalSurface.setLine(row, safe);
        trimTrailingBlankLines();
        setChangedAndSync();
    }

    @Override
    public void setLineFromSurface(int row, String text) {
        if (row < 0 || row >= MAX_LINES) {
            throw new IllegalArgumentException("monitor row must be from 0 to " + (MAX_LINES - 1));
        }
        while (lines.size() <= row) lines.add("");
        lines.set(row, sanitize(text));
        trimTrailingBlankLines();
        setChangedAndSync();
    }

    private void trimTrailingBlankLines() {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    @Override
    public void clear() {
        lines.clear();
        terminalSurface.clear();
        setChangedAndSync();
    }

    @Override
    public void writeLine(String text) {
        String line = sanitize(text);
        lines.add(line);
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        terminalSurface.clear();
        for (int row = 0; row < lines.size(); row++) terminalSurface.setLine(row, lines.get(row));
        setChangedAndSync();
    }

    public void setLines(List<String> newLines) {
        lines.clear();
        if (newLines != null) {
            for (String l : newLines) {
                lines.add(sanitize(l));
                if (lines.size() >= MAX_LINES) {
                    break;
                }
            }
        }
        terminalSurface.clear();
        for (int row = 0; row < lines.size(); row++) terminalSurface.setLine(row, lines.get(row));
        setChangedAndSync();
    }

    /** Replaces the local tile surface without publishing one event per row. */
    void replaceLinesQuietly(List<String> newLines) {
        lines.clear();
        if (newLines != null) {
            for (String line : newLines) {
                lines.add(sanitize(line));
                if (lines.size() >= MAX_LINES) break;
            }
        }
        terminalSurface.clear();
        for (int row = 0; row < lines.size(); row++) terminalSurface.setLine(row, lines.get(row));
    }

    /** Replaces a local tile's text, per-cell colors, and palette without publishing row events. */
    void replaceSurfaceQuietly(List<String> newLines, List<String> foreground,
                               List<String> background, int[] surfacePalette) {
        if (surfacePalette == null || surfacePalette.length != TerminalBuffer.PALETTE_SIZE) {
            throw new IllegalArgumentException("monitor surface palette must contain 16 colors");
        }
        lines.clear();
        if (newLines != null) {
            for (String line : newLines) {
                lines.add(sanitize(line));
                if (lines.size() >= MAX_LINES) break;
            }
        }
        for (int color = 0; color < surfacePalette.length; color++) {
            terminalSurface.setPaletteColor(color, surfacePalette[color]);
        }
        terminalSurface.clear();
        for (int row = 0; row < MAX_LINES; row++) {
            String text = row < lines.size() ? lines.get(row) : "";
            String foregroundRow = foreground != null && row < foreground.size() ? foreground.get(row) : "";
            String backgroundRow = background != null && row < background.size() ? background.get(row) : "";
            for (int column = 0; column < MAX_LINE_LEN; column++) {
                char character = column < text.length() ? text.charAt(column) : ' ';
                terminalSurface.setCell(column, row, character,
                        colorAt(foregroundRow, column), colorAt(backgroundRow, column));
            }
        }
    }

    /** Publishes one batched output event after a multi-tile surface update. */
    void publishOutputChanged() {
        setChangedAndSync();
    }

    private static int colorAt(String colors, int column) {
        if (colors == null || column < 0 || column >= colors.length()) return 0;
        int color = Character.digit(colors.charAt(column), 16);
        return color < 0 ? 0 : color;
    }


    /** Publishes a zero-based wall-cell touch event for interactive GUI scripts. */
    public void publishTouch(net.minecraft.world.phys.Vec3 hitLocation, net.minecraft.world.entity.player.Player player) {
        if (level == null || level.isClientSide) return;
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(this);
        int tileColumn = 0;
        int tileRow = 0;
        for (int row = 0; row < group.height(); row++) {
            for (int column = 0; column < group.width(); column++) {
                if (group.at(column, row) == this) {
                    tileColumn = column;
                    tileRow = row;
                }
            }
        }
        double localX = hitLocation.x - worldPosition.getX();
        double localY = hitLocation.y - worldPosition.getY();
        net.minecraft.core.Direction facing = getBlockState().getValue(com.malice.terminalcraft.block.MonitorBlock.FACING);
        double horizontal = switch (facing) {
            case NORTH -> localX;
            case SOUTH -> 1.0 - localX;
            case EAST -> hitLocation.z - worldPosition.getZ();
            case WEST -> 1.0 - (hitLocation.z - worldPosition.getZ());
            default -> localX;
        };
        int column = tileColumn * MAX_LINE_LEN + Math.min(MAX_LINE_LEN - 1, Math.max(0, (int) (horizontal * MAX_LINE_LEN)));
        int row = tileRow * MAX_LINES + Math.min(MAX_LINES - 1, Math.max(0, (int) ((1.0 - localY) * MAX_LINES)));
        DisplayTransportRuntime.handleTouch(group.anchor(), column, row, player);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.malice.terminalcraft.scada.ScadaRuntime.handleMonitorTouch(
                    serverPlayer.server, group.anchor().getDeviceId(), column, row, serverPlayer);
        }
        ServerDeviceManager.publishEvent(group.anchor(), "touch", level.getGameTime(),
                (DeviceValue.MapValue) DeviceValue.map(java.util.Map.of(
                        "x", DeviceValue.of(column),
                        "y", DeviceValue.of(row),
                        "player", DeviceValue.of(player.getGameProfile().getName()))));
    }

    public Component asChatComponent() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(title).append("]\n");
        if (lines.isEmpty()) {
            sb.append("(blank)");
        } else {
            for (String line : lines) {
                sb.append(line).append('\n');
            }
        }
        return Component.literal(sb.toString().trim());
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r", "").replace("\t", " ");
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl);
        }
        if (s.length() > MAX_LINE_LEN) {
            s = s.substring(0, MAX_LINE_LEN);
        }
        return s;
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
            if (!level.isClientSide) {
                ServerDeviceManager.publishEvent(this, "output_changed", level.getGameTime(),
                        (DeviceValue.MapValue) DeviceValue.map(java.util.Map.of(
                                "title", DeviceValue.of(title),
                                "line_count", DeviceValue.of(lines.size()))));
            }
        }
    }


    @Override
    public void setRemoved() {
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MonitorBlockEntity monitor) {
        MonitorGroupDevice groupDevice = new MonitorGroupDevice(monitor);
        MonitorGroupDevice.Group group = groupDevice.group();
        if (group.anchor() == monitor) {
            String signature = group.signature();
            boolean resized = !signature.equals(monitor.registeredWallSignature);
            if (resized) {
                // MonitorDeviceEndpoint snapshots terminal dimensions when constructed. Rebuild it
                // whenever the wall changes so horizontal/vertical expansion is immediately visible.
                ServerDeviceManager.invalidate(monitor);
                monitor.registeredWallSignature = signature;
            }
            ServerDeviceManager.ensureMonitorRegistered(monitor, monitor.getDeviceId(),
                    monitor.getDeviceAddress(), groupDevice);
            if (resized) {
                // Publish only after registration so consumers resolving the endpoint observe the
                // same geometry carried by this event. The wall anchor is the sole producer.
                ServerDeviceManager.publishEvent(monitor, "monitor_resize", level.getGameTime(),
                        (DeviceValue.MapValue) DeviceValue.map(java.util.Map.of(
                                "width", DeviceValue.of(group.width()),
                                "height", DeviceValue.of(group.height()))));
            }
        } else {
            monitor.registeredWallSignature = "";
            ServerDeviceManager.invalidate(monitor);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.putString("Title", title);
        tag.putInt("Foreground", foregroundColor);
        tag.putInt("Background", backgroundColor);
        ListTag list = new ListTag();
        for (String line : lines) {
            list.add(StringTag.valueOf(line));
        }
        tag.put("Lines", list);
        CompoundTag surface = new CompoundTag();
        terminalSurface.save(surface);
        tag.put("Surface", surface);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        if (tag.contains("Title", Tag.TAG_STRING)) {
            title = PersistedDataLimits.readString(tag, "Title",
                    PersistedDataLimits.MAX_LABEL_CHARS, "Monitor");
        }
        if (tag.contains("Foreground", Tag.TAG_INT)) foregroundColor = tag.getInt("Foreground") & 0xFFFFFF;
        if (tag.contains("Background", Tag.TAG_INT)) backgroundColor = tag.getInt("Background") & 0xFFFFFF;
        lines.clear();
        if (tag.contains("Lines", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Lines", Tag.TAG_STRING);
            for (int i = 0; i < list.size() && i < MAX_LINES; i++) {
                lines.add(sanitize(list.getString(i)));
            }
        }
        CompoundTag surface = tag.contains("Surface", Tag.TAG_COMPOUND) ? tag.getCompound("Surface") : null;
        if (surface == null || !terminalSurface.load(surface)) {
            terminalSurface.clear();
            syncLegacyPaletteToSurface();
            for (int row = 0; row < lines.size(); row++) terminalSurface.setLine(row, lines.get(row));
            terminalSurface.setCursor(1, 1);
        } else {
            // Legacy monitor palette fields remain the compatibility source of truth for line
            // APIs. Keep the active surface slots in sync after loading older snapshots whose
            // Surface palette predates the palette bridge.
            syncLegacyPaletteToSurface();
            lines.clear();
            lines.addAll(terminalSurface.lines());
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    private void syncLegacyPaletteToSurface() {
        terminalSurface.setPaletteColor(terminalSurface.textColor(), foregroundColor);
        terminalSurface.setPaletteColor(terminalSurface.backgroundColor(), backgroundColor);
    }
}
