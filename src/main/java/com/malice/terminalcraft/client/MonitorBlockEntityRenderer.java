package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.MonitorBlock;
import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.device.TerminalBuffer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/** Renders one continuous display surface for a connected monitor wall. */
public final class MonitorBlockEntityRenderer implements BlockEntityRenderer<MonitorBlockEntity> {
    private static final float SCREEN_MARGIN = 1.0f / 16.0f;
    private static final float SCREEN_Z = -0.507f;
    private static final float FONT_SCALE = 0.00375f;
    private static final float LINE_HEIGHT = 9.0f;

    public MonitorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(MonitorBlockEntity monitor, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        MonitorBlockEntity.WallRenderState wall = monitor.wallRenderState();
        // Rendering the wall once prevents every tile from painting an independent screen.
        if (!wall.anchor()) return;

        Direction facing = monitor.getBlockState().getValue(MonitorBlock.FACING);
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180.0f - facing.toYRot()));
        pose.translate(0.0, 0.0, SCREEN_Z);

        float textScale = (float) Math.max(0.5, Math.min(5.0, wall.surface().textScale()));
        drawScreen(pose, buffers, wall.surface(), textScale);
        drawText(pose, buffers, wall.surface(), textScale);
        pose.popPose();
    }

    private static void drawScreen(PoseStack pose, MultiBufferSource buffers, TerminalBuffer surface,
                                   float textScale) {
        float wallWidth = (float) surface.width() / MonitorBlockEntity.MAX_LINE_LEN;
        float wallHeight = (float) surface.height() / MonitorBlockEntity.MAX_LINES;
        float left = 0.5f - SCREEN_MARGIN;
        float right = -wallWidth + 0.5f + SCREEN_MARGIN;
        float top = 0.5f - SCREEN_MARGIN;
        float bottom = -wallHeight + 0.5f + SCREEN_MARGIN;
        VertexConsumer consumer = buffers.getBuffer(RenderType.textBackground());
        Matrix4f matrix = pose.last().pose();
        float cellWidth = (wallWidth - (2.0f * SCREEN_MARGIN)) / surface.width() * textScale;
        float cellHeight = (wallHeight - (2.0f * SCREEN_MARGIN)) / surface.height() * textScale;
        for (int row = 0; row < surface.height(); row++) {
            float cellTop = top - row * cellHeight;
            if (cellTop <= bottom) break;
            for (int column = 0; column < surface.width(); column++) {
                float cellLeft = left - column * cellWidth;
                if (cellLeft <= right) break;
                int rgb = surface.paletteColor(surface.backgroundAt(column, row));
                quad(consumer, matrix, cellLeft, Math.max(right, cellLeft - cellWidth),
                        cellTop, Math.max(bottom, cellTop - cellHeight), rgb);
            }
        }
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix, float left, float right,
                             float top, float bottom, int rgb) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        vertex(consumer, matrix, left, top, red, green, blue);
        vertex(consumer, matrix, left, bottom, red, green, blue);
        vertex(consumer, matrix, right, bottom, red, green, blue);
        vertex(consumer, matrix, right, top, red, green, blue);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y,
                               int red, int green, int blue) {
        consumer.vertex(matrix, x, y, 0.0f)
                .color(red, green, blue, 255)
                .uv2(LightTexture.FULL_BRIGHT)
                .endVertex();
    }

    private static void drawText(PoseStack pose, MultiBufferSource buffers, TerminalBuffer surface,
                                 float textScale) {
        pose.pushPose();
        float scaledFont = FONT_SCALE * textScale;
        pose.scale(-scaledFont, -scaledFont, scaledFont);

        Font font = Minecraft.getInstance().font;
        float wallWidth = (float) surface.width() / MonitorBlockEntity.MAX_LINE_LEN;
        float wallHeight = (float) surface.height() / MonitorBlockEntity.MAX_LINES;
        float left = -(0.5f - SCREEN_MARGIN) / scaledFont;
        float top = -(0.5f - SCREEN_MARGIN) / scaledFont;
        float cellPitchX = (wallWidth - (2.0f * SCREEN_MARGIN)) / FONT_SCALE / surface.width();
        float cellPitchY = (wallHeight - (2.0f * SCREEN_MARGIN)) / FONT_SCALE / surface.height();
        int visibleColumns = Math.min(surface.width(), (int) Math.ceil(surface.width() / textScale));
        int visibleRows = Math.min(surface.height(), (int) Math.ceil(surface.height() / textScale));

        for (int row = 0; row < visibleRows; row++) {
            for (int column = 0; column < visibleColumns; column++) {
                char character = surface.characterAt(column, row);
                if (character == ' ') continue;
                int foreground = 0xFF000000 | surface.paletteColor(surface.foregroundAt(column, row));
                float x = left + column * cellPitchX;
                float y = top + row * Math.max(LINE_HEIGHT, cellPitchY);
                font.drawInBatch(String.valueOf(character), x, y, foreground, false, pose.last().pose(), buffers,
                        Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            }
        }
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(MonitorBlockEntity monitor) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}
