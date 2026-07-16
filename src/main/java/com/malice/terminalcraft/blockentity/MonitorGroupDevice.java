package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.MonitorBlock;
import com.malice.terminalcraft.device.MonitorDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic monitor wall. Connected monitors with the same facing form one independently addressed
 * text canvas. State remains distributed across member block entities, so every tile renders only
 * its own portion of the wall.
 */
final class MonitorGroupDevice implements MonitorDevice {
    // ComputerCraft monitor walls support up to 8 by 6 blocks.
    static final int MAX_TILES_WIDE = 8;
    static final int MAX_TILES_HIGH = 6;
    static final int MAX_TILES = MAX_TILES_WIDE * MAX_TILES_HIGH;

    private final MonitorBlockEntity origin;

    MonitorGroupDevice(MonitorBlockEntity origin) { this.origin = origin; }

    Group group() { return discover(origin); }

    @Override public int maxLines() { return group().height() * MonitorBlockEntity.MAX_LINES; }
    @Override public int maxLineLength() { return group().width() * MonitorBlockEntity.MAX_LINE_LEN; }
    @Override public String title() { return group().anchor().getTitle(); }
    @Override public void setTitle(String title) { for (MonitorBlockEntity tile : group().tiles()) tile.setTitle(title); }
    @Override public int foregroundColor() { return group().anchor().foregroundColor(); }
    @Override public int backgroundColor() { return group().anchor().backgroundColor(); }
    @Override public void setPalette(int foreground, int background) {
        for (MonitorBlockEntity tile : group().tiles()) tile.setPalette(foreground, background);
    }

    @Override
    public List<String> lines() {
        Group group = group();
        List<String> result = new ArrayList<>(group.height() * MonitorBlockEntity.MAX_LINES);
        for (int row = 0; row < group.height() * MonitorBlockEntity.MAX_LINES; row++) {
            int tileRow = row / MonitorBlockEntity.MAX_LINES;
            int localRow = row % MonitorBlockEntity.MAX_LINES;
            StringBuilder line = new StringBuilder();
            for (int tileColumn = 0; tileColumn < group.width(); tileColumn++) {
                MonitorBlockEntity tile = group.at(tileColumn, tileRow);
                String part = tile == null ? "" : valueAt(tile.getLines(), localRow);
                line.append(pad(part, MonitorBlockEntity.MAX_LINE_LEN));
            }
            result.add(stripTrailingSpaces(line.toString()));
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return List.copyOf(result);
    }

    @Override
    public void writeLine(String text) {
        List<String> snapshot = new ArrayList<>(lines());
        int row = snapshot.size();
        if (row >= maxLines()) {
            snapshot.remove(0);
            clear();
            for (int i = 0; i < snapshot.size(); i++) setLine(i, snapshot.get(i));
            row = maxLines() - 1;
        }
        setLine(row, text);
    }

    @Override
    public void setLine(int row, String text) {
        Group group = group();
        if (row < 0 || row >= group.height() * MonitorBlockEntity.MAX_LINES)
            throw new IllegalArgumentException("monitor row is outside the wall");
        String safe = text == null ? "" : text;
        if (safe.length() > group.width() * MonitorBlockEntity.MAX_LINE_LEN)
            throw new IllegalArgumentException("monitor line exceeds wall width");
        int tileRow = row / MonitorBlockEntity.MAX_LINES;
        int localRow = row % MonitorBlockEntity.MAX_LINES;
        for (int tileColumn = 0; tileColumn < group.width(); tileColumn++) {
            MonitorBlockEntity tile = group.at(tileColumn, tileRow);
            if (tile == null) continue;
            int start = tileColumn * MonitorBlockEntity.MAX_LINE_LEN;
            String part = start >= safe.length() ? "" : safe.substring(start,
                    Math.min(safe.length(), start + MonitorBlockEntity.MAX_LINE_LEN));
            tile.setLine(localRow, part);
        }
    }

    @Override public void clear() { for (MonitorBlockEntity tile : group().tiles()) tile.clear(); }

    static Group discover(MonitorBlockEntity start) {
        Level level = start.getLevel();
        Direction facing = facing(start);
        if (level == null) return Group.single(start);
        Direction horizontal = facing.getClockWise();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<MonitorBlockEntity> tiles = new ArrayList<>();
        queue.add(start.getBlockPos());
        while (!queue.isEmpty() && tiles.size() <= MAX_TILES) {
            BlockPos position = queue.removeFirst();
            if (!visited.add(position)) continue;
            if (!(level.getBlockEntity(position) instanceof MonitorBlockEntity tile) || facing(tile) != facing) continue;
            tiles.add(tile);
            queue.add(position.relative(horizontal));
            queue.add(position.relative(horizontal.getOpposite()));
            queue.add(position.above());
            queue.add(position.below());
        }
        int minH = tiles.stream().mapToInt(tile -> project(tile.getBlockPos(), horizontal)).min().orElse(0);
        int maxH = tiles.stream().mapToInt(tile -> project(tile.getBlockPos(), horizontal)).max().orElse(minH);
        int minY = tiles.stream().mapToInt(tile -> tile.getBlockPos().getY()).min().orElse(start.getBlockPos().getY());
        int maxY = tiles.stream().mapToInt(tile -> tile.getBlockPos().getY()).max().orElse(minY);
        int width = maxH - minH + 1;
        int height = maxY - minY + 1;
        if (tiles.size() > MAX_TILES || width > MAX_TILES_WIDE || height > MAX_TILES_HIGH) return Group.single(start);

        Map<Long, MonitorBlockEntity> byCell = new HashMap<>();
        for (MonitorBlockEntity tile : tiles) {
            int column = maxH - project(tile.getBlockPos(), horizontal);
            int row = maxY - tile.getBlockPos().getY();
            byCell.put(cell(column, row), tile);
        }
        // Stable visual top-left ownership, independent of BlockPos packed-long ordering.
        MonitorBlockEntity anchor = tiles.stream().min(Comparator
                .comparingInt((MonitorBlockEntity tile) -> maxY - tile.getBlockPos().getY())
                .thenComparingInt(tile -> maxH - project(tile.getBlockPos(), horizontal)))
                .orElse(start);
        return new Group(anchor, List.copyOf(tiles), Map.copyOf(byCell), width, height);
    }

    private static Direction facing(MonitorBlockEntity tile) {
        return tile.getBlockState().hasProperty(MonitorBlock.FACING)
                ? tile.getBlockState().getValue(MonitorBlock.FACING) : Direction.NORTH;
    }

    private static int project(BlockPos position, Direction axis) {
        return position.getX() * axis.getStepX() + position.getZ() * axis.getStepZ();
    }

    private static long cell(int column, int row) { return ((long) column << 32) | (row & 0xffffffffL); }
    private static String valueAt(List<String> values, int index) { return index < values.size() ? values.get(index) : ""; }
    private static String pad(String value, int width) { return value + " ".repeat(Math.max(0, width - value.length())); }
    private static String stripTrailingSpaces(String value) { int end = value.length(); while (end > 0 && value.charAt(end - 1) == ' ') end--; return value.substring(0, end); }

    record Group(MonitorBlockEntity anchor, List<MonitorBlockEntity> tiles,
                 Map<Long, MonitorBlockEntity> byCell, int width, int height) {
        static Group single(MonitorBlockEntity tile) {
            return new Group(tile, List.of(tile), Map.of(cell(0, 0), tile), 1, 1);
        }
        MonitorBlockEntity at(int column, int row) { return byCell.get(cell(column, row)); }
        String signature() {
            return width + "x" + height + ":" + tiles.stream()
                    .map(tile -> Long.toUnsignedString(tile.getBlockPos().asLong()))
                    .sorted().reduce((a, b) -> a + "," + b).orElse("");
        }
    }
}
