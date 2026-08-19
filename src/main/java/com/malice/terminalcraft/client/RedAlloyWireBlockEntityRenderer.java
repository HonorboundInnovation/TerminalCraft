package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Renders every independently insulated/color-selected wire run in a multipart block. */
public final class RedAlloyWireBlockEntityRenderer implements BlockEntityRenderer<RedAlloyWireBlockEntity> {
    public RedAlloyWireBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(RedAlloyWireBlockEntity wire, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (wire.getLevel() == null) return;
        Direction primary = wire.getBlockState().getValue(RedAlloyWireBlock.FACE);
        for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
            if (run.face() == primary) continue;
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    RedAlloyWireBlock.renderState(wire.getLevel(), wire.getBlockPos(),
                            run.face(), run.lane(), run.color()),
                    pose, buffers, packedLight, packedOverlay);
        }
    }
}
