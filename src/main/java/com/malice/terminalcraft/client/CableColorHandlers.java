package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;

/** Block-model tint colors for dye-selected surface cables. */
public final class CableColorHandlers {
    private CableColorHandlers() {}

    public static int redAlloy(BlockState state) {
        DyeColor dye = SurfaceCableSupport.dyeColor(state.getValue(RedAlloyWireBlock.COLOR));
        int power = state.getValue(RedAlloyWireBlock.POWER);
        return power <= 0 ? diffuseColor(dye) : poweredColor(dye, power);
    }

    public static int network(BlockState state) {
        return diffuseColor(SurfaceCableSupport.dyeColor(state.getValue(NetworkCableBlock.COLOR)));
    }

    private static int diffuseColor(DyeColor dye) {
        float[] rgb = dye.getTextureDiffuseColors();
        return packed(rgb[0], rgb[1], rgb[2]);
    }

    private static int poweredColor(DyeColor dye, int signalStrength) {
        float[] base = dye.getTextureDiffuseColors();
        int firework = dye.getFireworkColor();
        float brightRed = ((firework >> 16) & 0xFF) / 255.0F;
        float brightGreen = ((firework >> 8) & 0xFF) / 255.0F;
        float brightBlue = (firework & 0xFF) / 255.0F;
        if (brightRed + brightGreen + brightBlue < 0.05F) {
            brightRed = brightGreen = brightBlue = 0.35F;
        }
        float strength = Math.min(15, signalStrength) / 15.0F;
        float blend = 0.30F + strength * 0.55F;
        return packed(
                Math.min(1.0F, base[0] * (1.0F - blend) + brightRed * blend + 0.08F * strength),
                Math.min(1.0F, base[1] * (1.0F - blend) + brightGreen * blend + 0.08F * strength),
                Math.min(1.0F, base[2] * (1.0F - blend) + brightBlue * blend + 0.08F * strength));
    }

    private static int packed(float red, float green, float blue) {
        int r = Math.round(red * 255.0F);
        int g = Math.round(green * 255.0F);
        int b = Math.round(blue * 255.0F);
        return (r << 16) | (g << 8) | b;
    }
}
