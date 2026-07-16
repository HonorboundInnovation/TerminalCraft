package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Draws secondary face parts of a multipart surface network cable. */
public final class NetworkCableBlockEntityRenderer implements BlockEntityRenderer<NetworkCableBlockEntity> {
    public NetworkCableBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(NetworkCableBlockEntity cable, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (cable.getLevel() == null) return;
        Direction primary = cable.getBlockState().getValue(NetworkCableBlock.FACE);
        for (Direction face : cable.faces()) {
            if (face == primary) continue;
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    NetworkCableBlock.renderState(cable.getLevel(), cable.getBlockPos(), face),
                    pose, buffers, packedLight, packedOverlay);
        }
    }
}
