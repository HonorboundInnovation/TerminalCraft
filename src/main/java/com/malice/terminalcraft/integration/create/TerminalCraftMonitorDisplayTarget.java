package com.malice.terminalcraft.integration.create;

import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Create text sink backed by the complete wall containing the selected monitor tile. */
final class TerminalCraftMonitorDisplayTarget extends DisplayTarget {
    @Override
    public void acceptText(int startLine, List<MutableComponent> text, DisplayLinkContext context) {
        if (!(context.getTargetBlockEntity() instanceof MonitorBlockEntity monitor) || startLine < 0) return;
        int rows = monitor.wallRows();
        int columns = monitor.wallColumns();
        MonitorBlockEntity anchor = monitor.wallAnchor();
        boolean reserved = false;
        for (int index = 0; index < text.size() && startLine + index < rows; index++) {
            int row = startLine + index;
            if (!reserved) {
                reserve(row, anchor, context);
                reserved = true;
            } else if (isReserved(row, anchor, context)) {
                break;
            }
            monitor.setWallLine(row, text.get(index).getString(columns));
        }
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        BlockEntity target = context.getTargetBlockEntity();
        if (target instanceof MonitorBlockEntity monitor) {
            return new DisplayTargetStats(monitor.wallRows(), monitor.wallColumns(), this);
        }
        return new DisplayTargetStats(MonitorBlockEntity.MAX_LINES, MonitorBlockEntity.MAX_LINE_LEN, this);
    }

    @Override
    public AABB getMultiblockBounds(LevelAccessor level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MonitorBlockEntity monitor
                ? monitor.wallBounds() : new AABB(pos);
    }

    @Override
    public boolean requiresComponentSanitization() {
        return true;
    }
}
