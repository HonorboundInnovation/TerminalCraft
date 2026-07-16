package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Draws secondary face parts of a multipart bundled surface cable. */
public final class BundledCableBlockEntityRenderer implements BlockEntityRenderer<BundledCableBlockEntity> {
    public BundledCableBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(BundledCableBlockEntity cable, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (cable.getLevel() == null) return;
        Direction primary = cable.getBlockState().getValue(BundledCableBlock.FACE);
        for (Direction face : cable.faces()) {
            if (face == primary) continue;
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    BundledCableBlock.renderState(cable.getLevel(), cable.getBlockPos(), face),
                    pose, buffers, packedLight, packedOverlay);
        }
    }
}
