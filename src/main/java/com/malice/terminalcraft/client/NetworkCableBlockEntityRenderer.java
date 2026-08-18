package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.BundledNetworkCableBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Renders sixteen independently occupied/colorable data points on each block face. */
public final class NetworkCableBlockEntityRenderer implements BlockEntityRenderer<NetworkCableBlockEntity> {
    private static final ResourceLocation NETWORK_TEXTURE =
            new ResourceLocation("terminalcraft", "block/network_cable_custom");

    public NetworkCableBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(NetworkCableBlockEntity cable, float partialTick, PoseStack pose,
        MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (cable.getLevel() == null) return;
        boolean bundled = cable.getBlockState().getBlock() instanceof BundledNetworkCableBlock;
        for (NetworkCableBlockEntity.Run run : cable.runs()) {
            int route = NetworkCableBlock.visibleRoute(cable.getLevel(), cable.getBlockPos(),
                    run.face(), run.lane(), run.color());
            int signalStrength = route == 0 ? 0 : 5;
            net.minecraft.world.phys.shapes.VoxelShape shape = bundled
                    ? NetworkCableBlock.renderedRunShape(cable.getLevel(), cable.getBlockPos(),
                            run.face(), run.lane(), run.color())
                    : SurfaceCableSupport.centeredRunShape(run.face(), route);
            RedAlloyWireBlockEntityRenderer.renderColoredRun(shape, NETWORK_TEXTURE, run.color(),
                    signalStrength, pose, buffers, packedLight, packedOverlay);
        }
    }
}
