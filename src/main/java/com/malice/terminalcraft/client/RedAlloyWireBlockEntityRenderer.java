package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.resources.ResourceLocation;

/** Renders every independently insulated/color-selected wire run in a multipart block. */
public final class RedAlloyWireBlockEntityRenderer implements BlockEntityRenderer<RedAlloyWireBlockEntity> {
    public RedAlloyWireBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(RedAlloyWireBlockEntity wire, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (wire.getLevel() == null) return;
        for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
            BlockState state = RedAlloyWireBlock.renderState(wire.getLevel(), wire.getBlockPos(),
                    run.face(), run.lane(), run.color());
            VoxelShape shape = RedAlloyWireBlock.renderedRunShape(wire.getLevel(), wire.getBlockPos(),
                    run.face(), run.lane(), run.color());
            renderColoredRun(state, shape, run.color(),
                    pose, buffers, packedLight, packedOverlay);
        }
    }

    static void renderColoredRun(BlockState state, VoxelShape shape, int color,
                                 PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        DyeColor dye = SurfaceCableSupport.dyeColor(color);
        float[] rgb = dye.getTextureDiffuseColors();
        ResourceLocation texture = new ResourceLocation("terminalcraft", state.getBlock() instanceof
                com.malice.terminalcraft.block.NetworkCableBlock
                ? "block/network_cable_custom" : "block/red_alloy_wire_custom");
        SurfaceCableGeometryRenderer.render(shape,
                texture, pose, buffers, rgb[0], rgb[1], rgb[2], packedLight, packedOverlay);
    }

    static void renderBakedRun(BlockState state, net.minecraft.core.Direction face, int lane, int color,
                               int routeMask, PoseStack pose, MultiBufferSource buffers,
                               int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        DyeColor dye = SurfaceCableSupport.dyeColor(color);
        float[] rgb = dye.getTextureDiffuseColors();
        Vec3 offset = SurfaceCableSupport.laneOffset(face, lane, routeMask);
        pose.pushPose();
        pose.translate(offset.x, offset.y, offset.z);
        minecraft.getBlockRenderer().getModelRenderer().renderModel(
                pose.last(), buffers.getBuffer(ItemBlockRenderTypes.getRenderType(state, false)),
                state, model, rgb[0], rgb[1], rgb[2], packedLight, packedOverlay);
        pose.popPose();
    }
}
