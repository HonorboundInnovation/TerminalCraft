package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.MonitorBlock;
import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
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

import java.util.List;

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

        drawScreen(pose, buffers, wall.width(), wall.height(), wall.backgroundColor());
        drawText(pose, buffers, wall);
        pose.popPose();
    }

    private static void drawScreen(PoseStack pose, MultiBufferSource buffers, int width, int height,
                                   int rgb) {
        float left = 0.5f - SCREEN_MARGIN;
        float right = -width + 0.5f + SCREEN_MARGIN;
        float top = 0.5f - SCREEN_MARGIN;
        float bottom = -height + 0.5f + SCREEN_MARGIN;
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.textBackground());
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

    private static void drawText(PoseStack pose, MultiBufferSource buffers,
                                 MonitorBlockEntity.WallRenderState wall) {
        pose.pushPose();
        pose.scale(-FONT_SCALE, -FONT_SCALE, FONT_SCALE);

        Font font = Minecraft.getInstance().font;
        int foreground = 0xFF000000 | wall.foregroundColor();
        float left = -(0.5f - SCREEN_MARGIN) / FONT_SCALE;
        float top = -(0.5f - SCREEN_MARGIN) / FONT_SCALE;
        float tilePitchX = 1.0f / FONT_SCALE;
        float tilePitchY = 1.0f / FONT_SCALE;
        List<String> lines = wall.lines();

        for (int row = 0; row < wall.height() * MonitorBlockEntity.MAX_LINES; row++) {
            String line = row < lines.size() ? lines.get(row) : "";
            int tileRow = row / MonitorBlockEntity.MAX_LINES;
            int localRow = row % MonitorBlockEntity.MAX_LINES;
            for (int tileColumn = 0; tileColumn < wall.width(); tileColumn++) {
                int start = tileColumn * MonitorBlockEntity.MAX_LINE_LEN;
                if (start >= line.length()) break;
                String part = line.substring(start,
                        Math.min(line.length(), start + MonitorBlockEntity.MAX_LINE_LEN));
                if (part.isBlank()) continue;
                float x = left + tileColumn * tilePitchX;
                float y = top + tileRow * tilePitchY + localRow * LINE_HEIGHT;
                font.drawInBatch(part, x, y, foreground, false, pose.last().pose(), buffers,
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
