package com.malice.terminalcraft.client;

import com.malice.terminalcraft.TerminalCraftMod;
import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.block.BundledNetworkCableBlock;
import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.item.BundledCableItem;
import com.malice.terminalcraft.item.BundledNetworkCableItem;
import com.malice.terminalcraft.item.NetworkCableItem;
import com.malice.terminalcraft.item.RedAlloyWireItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** World-space centered-cable outline and concise crosshair readout before placement. */
@Mod.EventBusSubscriber(modid = TerminalCraftMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CablePlacementPreviewRenderer {
    private CablePlacementPreviewRenderer() {}

    @SubscribeEvent
    public static void renderWorldPreview(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        Preview preview = preview(minecraft, event.getTarget());
        if (preview == null) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(preview.target().getX() - camera.x, preview.target().getY() - camera.y,
                preview.target().getZ() - camera.z);
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        if (preview.family().bundled) {
            float[] color = preview.valid() ? preview.family().previewColor : INVALID;
            drawShape(pose, lines, SurfaceCableSupport.bundledMarkerShape(preview.face()), color, 0.95F);
        } else {
            float[] color = preview.valid() ? preview.dyeColor().getTextureDiffuseColors() : INVALID;
            drawShape(pose, lines, SurfaceCableSupport.centeredMarkerShape(preview.face()), color, 1.0F);
        }
        pose.popPose();
    }

    @SubscribeEvent
    public static void renderHudPreview(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id())) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || !(minecraft.hitResult instanceof BlockHitResult hit)) return;
        Preview preview = preview(minecraft, hit);
        if (preview == null) return;
        Component message = preview.message();
        int width = minecraft.font.width(message);
        int x = event.getWindow().getGuiScaledWidth() / 2;
        int y = event.getWindow().getGuiScaledHeight() / 2 + 18;
        event.getGuiGraphics().fill(x - width / 2 - 4, y - 3, x + width / 2 + 4, y + 11,
                0xB0080D0B);
        event.getGuiGraphics().drawCenteredString(minecraft.font, message, x, y,
                preview.valid() ? 0xFFE6FFF0 : 0xFFFF7777);
    }

    private static Preview preview(Minecraft minecraft, BlockHitResult hit) {
        if (minecraft.player == null || minecraft.level == null || hit.getType() != HitResult.Type.BLOCK) return null;
        ItemStack stack = cableStack(minecraft.player.getMainHandItem())
                ? minecraft.player.getMainHandItem() : minecraft.player.getOffhandItem();
        Family family = Family.forStack(stack);
        if (family == null) return null;

        Level level = minecraft.level;
        BlockPos clicked = hit.getBlockPos();
        boolean clickedExisting = family.matches(level.getBlockState(clicked));
        Direction face = hit.getDirection();
        if (clickedExisting) {
            face = family.placementFace(level, clicked, minecraft.player, hit.getLocation(), face);
        }
        BlockPos target = clickedExisting ? clicked : clicked.relative(face);
        BlockState targetState = level.getBlockState(target);
        boolean existing = family.matches(targetState);
        boolean replaceable = existing || targetState.canBeReplaced();
        boolean supported = family.canSurvive(level, target, face);
        DyeColor dye = family.color(stack);
        boolean duplicate = existing && family.hasFace(level, target, face);
        boolean opposite = family == Family.RED_BUNDLE && existing
                && family.hasFace(level, target, face.getOpposite());
        boolean valid = replaceable && supported && !duplicate && !opposite;
        boolean shielded = family != Family.RED_ALLOY || RedAlloyWireItem.isShielded(stack);
        return new Preview(target.immutable(), face, dye, family, shielded, valid);
    }

    private static boolean cableStack(ItemStack stack) {
        return Family.forStack(stack) != null;
    }

    private static void drawShape(PoseStack pose, VertexConsumer lines, VoxelShape shape,
                                  float[] color, float alpha) {
        for (AABB box : shape.toAabbs()) {
            LevelRenderer.renderLineBox(pose, lines, box, color[0], color[1], color[2], alpha);
        }
    }

    private enum Family {
        RED_ALLOY(false, new float[]{1.0F, 0.22F, 0.12F}),
        NETWORK(false, new float[]{0.0F, 0.88F, 1.0F}),
        RED_BUNDLE(true, new float[]{1.0F, 0.18F, 0.08F}),
        NETWORK_BUNDLE(true, new float[]{0.0F, 0.88F, 1.0F});

        private final boolean bundled;
        private final float[] previewColor;

        Family(boolean bundled, float[] previewColor) {
            this.bundled = bundled;
            this.previewColor = previewColor;
        }

        private static Family forStack(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return null;
            if (stack.getItem() instanceof BundledNetworkCableItem) return NETWORK_BUNDLE;
            if (stack.getItem() instanceof BundledCableItem) return RED_BUNDLE;
            if (stack.getItem() instanceof NetworkCableItem) return NETWORK;
            if (stack.getItem() instanceof RedAlloyWireItem) return RED_ALLOY;
            return null;
        }

        private boolean matches(BlockState state) {
            return switch (this) {
                case RED_ALLOY -> state.getBlock() instanceof RedAlloyWireBlock;
                case NETWORK -> state.getBlock() instanceof NetworkCableBlock
                        && !(state.getBlock() instanceof BundledNetworkCableBlock);
                case RED_BUNDLE -> state.getBlock() instanceof BundledCableBlock;
                case NETWORK_BUNDLE -> state.getBlock() instanceof BundledNetworkCableBlock;
            };
        }

        private DyeColor color(ItemStack stack) {
            return switch (this) {
                case RED_ALLOY -> RedAlloyWireItem.color(stack);
                case NETWORK -> NetworkCableItem.color(stack);
                case RED_BUNDLE -> DyeColor.RED;
                case NETWORK_BUNDLE -> DyeColor.CYAN;
            };
        }

        private boolean canSurvive(Level level, BlockPos pos, Direction face) {
            return switch (this) {
                case RED_ALLOY -> RedAlloyWireBlock.canFaceSurvive(level, pos, face);
                case NETWORK, NETWORK_BUNDLE -> NetworkCableBlock.canFaceSurvive(level, pos, face);
                case RED_BUNDLE -> BundledCableBlock.canFaceSurvive(level, pos, face);
            };
        }

        private Direction placementFace(Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
                                        Vec3 hit, Direction fallback) {
            return switch (this) {
                case RED_ALLOY -> RedAlloyWireBlock.placementFace(level, pos, player, hit, fallback);
                case NETWORK -> NetworkCableBlock.placementFace(level, pos, player, hit, fallback);
                default -> fallback;
            };
        }

        private boolean hasFace(Level level, BlockPos pos, Direction face) {
            return switch (this) {
                case RED_ALLOY -> RedAlloyWireBlock.hasFace(level, pos, face);
                case NETWORK -> NetworkCableBlock.hasFace(level, pos, face);
                case RED_BUNDLE -> BundledCableBlock.hasFace(level, pos, face);
                case NETWORK_BUNDLE -> NetworkCableBlock.hasFace(level, pos, face);
            };
        }

    }

    private record Preview(BlockPos target, Direction face, DyeColor dyeColor,
                           Family family, boolean shielded, boolean valid) {
        private Component message() {
            if (!valid) {
                return Component.translatable(family.bundled
                        ? "preview.terminalcraft.cable.invalid_bundle"
                        : "preview.terminalcraft.cable.invalid_cable", face.getName());
            }
            if (family.bundled) {
                return Component.translatable(family == Family.RED_BUNDLE
                        ? "preview.terminalcraft.cable.red_bundle"
                        : "preview.terminalcraft.cable.network_bundle", face.getName());
            }
            if (family == Family.RED_ALLOY && !shielded) {
                return Component.translatable("preview.terminalcraft.cable.red_alloy_unshielded",
                        face.getName());
            }
            String key = family == Family.RED_ALLOY
                    ? "preview.terminalcraft.cable.red_alloy" : "preview.terminalcraft.cable.network";
            return Component.translatable(key, face.getName(),
                    Component.translatable("color.minecraft." + dyeColor.getName()), dyeColor.getId());
        }
    }

    private static final float[] INVALID = {1.0F, 0.16F, 0.12F};
}
