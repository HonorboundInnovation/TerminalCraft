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

        drawScreen(pose, buffers, wall.surface());
        drawText(pose, buffers, wall.surface());
        pose.popPose();
    }

    private static void drawScreen(PoseStack pose, MultiBufferSource buffers, TerminalBuffer surface) {
        float wallWidth = (float) surface.width() / MonitorBlockEntity.MAX_LINE_LEN;
        float wallHeight = (float) surface.height() / MonitorBlockEntity.MAX_LINES;
        float left = 0.5f - SCREEN_MARGIN;
        float right = -wallWidth + 0.5f + SCREEN_MARGIN;
        float top = 0.5f - SCREEN_MARGIN;
        float bottom = -wallHeight + 0.5f + SCREEN_MARGIN;
        VertexConsumer consumer = buffers.getBuffer(RenderType.textBackground());
        Matrix4f matrix = pose.last().pose();
        float cellWidth = (wallWidth - (2.0f * SCREEN_MARGIN)) / surface.width();
        float cellHeight = (wallHeight - (2.0f * SCREEN_MARGIN)) / surface.height();
        for (int row = 0; row < surface.height(); row++) {
            for (int column = 0; column < surface.width(); column++) {
                int rgb = surface.paletteColor(surface.backgroundAt(column, row));
                int red = rgb >> 16 & 0xFF;
                int green = rgb >> 8 & 0xFF;
                int blue = rgb & 0xFF;
                float cellLeft = left - column * cellWidth;
                float cellRight = cellLeft - cellWidth;
                float cellTop = top - row * cellHeight;
                float cellBottom = cellTop - cellHeight;
                vertex(consumer, matrix, cellLeft, cellTop, red, green, blue);
                vertex(consumer, matrix, cellLeft, cellBottom, red, green, blue);
                vertex(consumer, matrix, cellRight, cellBottom, red, green, blue);
                vertex(consumer, matrix, cellRight, cellTop, red, green, blue);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y,
                               int red, int green, int blue) {
        consumer.vertex(matrix, x, y, 0.0f)
                .color(red, green, blue, 255)
                .uv2(LightTexture.FULL_BRIGHT)
                .endVertex();
    }

    private static void drawText(PoseStack pose, MultiBufferSource buffers, TerminalBuffer surface) {
        pose.pushPose();
        pose.scale(-FONT_SCALE, -FONT_SCALE, FONT_SCALE);

        Font font = Minecraft.getInstance().font;
        float wallWidth = (float) surface.width() / MonitorBlockEntity.MAX_LINE_LEN;
        float wallHeight = (float) surface.height() / MonitorBlockEntity.MAX_LINES;
        float left = -(0.5f - SCREEN_MARGIN) / FONT_SCALE;
        float top = -(0.5f - SCREEN_MARGIN) / FONT_SCALE;
        float cellPitchX = (wallWidth - (2.0f * SCREEN_MARGIN)) / FONT_SCALE / surface.width();
        float cellPitchY = (wallHeight - (2.0f * SCREEN_MARGIN)) / FONT_SCALE / surface.height();

        for (int row = 0; row < surface.height(); row++) {
            for (int column = 0; column < surface.width(); column++) {
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
