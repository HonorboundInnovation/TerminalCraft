package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Renders every independently insulated/color-selected wire run in a multipart block. */
public final class RedAlloyWireBlockEntityRenderer implements BlockEntityRenderer<RedAlloyWireBlockEntity> {
    private static final ResourceLocation RED_ALLOY_TEXTURE =
            new ResourceLocation("terminalcraft", "block/red_alloy_wire_custom");
    private static final float[][][] ACTIVE_TINTS = new float[DyeColor.values().length][16][];

    static {
        for (DyeColor dye : DyeColor.values()) {
            for (int strength = 1; strength < 16; strength++) {
                ACTIVE_TINTS[dye.getId()][strength] = calculateTint(dye, strength);
            }
        }
    }
    public RedAlloyWireBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(RedAlloyWireBlockEntity wire, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (wire.getLevel() == null) return;
        for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
            int route = RedAlloyWireBlock.visibleRoute(wire.getLevel(), wire.getBlockPos(),
                    run.face(), run.lane(), run.color());
            VoxelShape shape = SurfaceCableSupport.centeredRunShape(run.face(), route);
            renderColoredRun(shape, RED_ALLOY_TEXTURE, run.color(), run.power(),
                    pose, buffers, packedLight, packedOverlay);
        }
    }

    static void renderColoredRun(VoxelShape shape, ResourceLocation texture, int color,
                                 int signalStrength, PoseStack pose, MultiBufferSource buffers,
                                 int packedLight, int packedOverlay) {
        DyeColor dye = SurfaceCableSupport.dyeColor(color);
        float[] rgb = tintForSignal(dye, signalStrength);
        SurfaceCableGeometryRenderer.render(shape, texture, pose, buffers,
                rgb[0], rgb[1], rgb[2],
                signalStrength > 0 ? LightTexture.FULL_BRIGHT : packedLight, packedOverlay);
    }

    private static float[] tintForSignal(DyeColor dye, int signalStrength) {
        float[] base = dye.getTextureDiffuseColors();
        if (signalStrength <= 0) return base;
        return ACTIVE_TINTS[dye.getId()][Math.min(15, signalStrength)];
    }

    private static float[] calculateTint(DyeColor dye, int signalStrength) {
        float[] base = dye.getTextureDiffuseColors();
        int firework = dye.getFireworkColor();
        float brightRed = ((firework >> 16) & 0xFF) / 255.0F;
        float brightGreen = ((firework >> 8) & 0xFF) / 255.0F;
        float brightBlue = (firework & 0xFF) / 255.0F;
        if (brightRed + brightGreen + brightBlue < 0.05F) {
            brightRed = brightGreen = brightBlue = 0.35F;
        }
        float strength = signalStrength / 15.0F;
        float blend = 0.30F + strength * 0.55F;
        return new float[] {
                Math.min(1.0F, base[0] * (1.0F - blend) + brightRed * blend + 0.08F * strength),
                Math.min(1.0F, base[1] * (1.0F - blend) + brightGreen * blend + 0.08F * strength),
                Math.min(1.0F, base[2] * (1.0F - blend) + brightBlue * blend + 0.08F * strength)
        };
    }
}
