package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.BundledNetworkCableBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Renders sixteen independently occupied/colorable data points on each block face. */
public final class NetworkCableBlockEntityRenderer implements BlockEntityRenderer<NetworkCableBlockEntity> {
    public NetworkCableBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(NetworkCableBlockEntity cable, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (cable.getLevel() == null) return;
        boolean bundled = cable.getBlockState().getBlock() instanceof BundledNetworkCableBlock;
        for (NetworkCableBlockEntity.Run run : cable.runs()) {
            if (bundled) {
                net.minecraft.world.phys.Vec3 offset = SurfaceCableSupport.laneOffset(
                        run.face(), run.lane(), run.ports());
                pose.pushPose();
                pose.translate(-offset.x, -offset.y, -offset.z);
            }
            net.minecraft.world.level.block.state.BlockState state = NetworkCableBlock.renderState(
                    cable.getLevel(), cable.getBlockPos(), run.face(), run.lane(), run.color());
            if (bundled) {
                RedAlloyWireBlockEntityRenderer.renderBakedRun(state, run.face(), run.lane(), run.color(),
                        run.ports(), pose, buffers, packedLight, packedOverlay);
            } else {
                net.minecraft.world.phys.shapes.VoxelShape shape = NetworkCableBlock.renderedRunShape(
                        cable.getLevel(), cable.getBlockPos(), run.face(), run.lane(), run.color());
                RedAlloyWireBlockEntityRenderer.renderColoredRun(state, shape, run.color(),
                        pose, buffers, packedLight, packedOverlay);
            }
            if (bundled) pose.popPose();
        }
    }
}
