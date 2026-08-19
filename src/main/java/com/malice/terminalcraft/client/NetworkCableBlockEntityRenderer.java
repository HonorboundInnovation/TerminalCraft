package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Renders sixteen independently occupied/colorable data points on each block face. */
public final class NetworkCableBlockEntityRenderer implements BlockEntityRenderer<NetworkCableBlockEntity> {
    public NetworkCableBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(NetworkCableBlockEntity cable, float partialTick, PoseStack pose,
        MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (cable.getLevel() == null) return;
        // Both ordinary and bundled network cables render exactly like their Red Alloy
        // counterparts: the block model draws the primary face and this draws secondary faces.
        Direction primary = cable.getBlockState().getValue(NetworkCableBlock.FACE);
        for (NetworkCableBlockEntity.Run run : cable.runs()) {
            if (run.face() == primary) continue;
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    NetworkCableBlock.renderState(cable.getLevel(), cable.getBlockPos(),
                            run.face(), run.lane(), run.color()),
                    pose, buffers, packedLight, packedOverlay);
        }
    }
}
