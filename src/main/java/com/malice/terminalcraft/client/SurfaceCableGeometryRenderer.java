package com.malice.terminalcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Emits textured axis-aligned route geometry whose endpoints remain on their actual block edges. */
final class SurfaceCableGeometryRenderer {
    private SurfaceCableGeometryRenderer() {}

    static void render(VoxelShape shape, ResourceLocation texture, PoseStack pose,
                       net.minecraft.client.renderer.MultiBufferSource buffers,
                       float red, float green, float blue, int packedLight, int packedOverlay) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
        VertexConsumer vertices = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose last = pose.last();
        for (AABB box : shape.toAabbs()) {
            renderBox(vertices, last.pose(), last.normal(), sprite, box,
                    red, green, blue, packedLight, packedOverlay);
        }
    }

    private static void renderBox(VertexConsumer out, Matrix4f matrix, Matrix3f normal,
                                  TextureAtlasSprite sprite, AABB box,
                                  float red, float green, float blue,
                                  int light, int overlay) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        quad(out, matrix, normal, red, green, blue, light, overlay,
                x0,y0,z1,u0,v1, x1,y0,z1,u1,v1, x1,y0,z0,u1,v0, x0,y0,z0,u0,v0, 0,-1,0);
        quad(out, matrix, normal, red, green, blue, light, overlay,
                x0,y1,z0,u0,v0, x1,y1,z0,u1,v0, x1,y1,z1,u1,v1, x0,y1,z1,u0,v1, 0,1,0);
        quad(out, matrix, normal, red, green, blue, light, overlay,
                x1,y0,z0,u0,v1, x0,y0,z0,u1,v1, x0,y1,z0,u1,v0, x1,y1,z0,u0,v0, 0,0,-1);
        quad(out, matrix, normal, red, green, blue, light, overlay,
                x0,y0,z1,u0,v1, x1,y0,z1,u1,v1, x1,y1,z1,u1,v0, x0,y1,z1,u0,v0, 0,0,1);
        quad(out, matrix, normal, red, green, blue, light, overlay,
                x0,y0,z0,u0,v1, x0,y0,z1,u1,v1, x0,y1,z1,u1,v0, x0,y1,z0,u0,v0, -1,0,0);
        quad(out, matrix, normal, red, green, blue, light, overlay,
                x1,y0,z1,u0,v1, x1,y0,z0,u1,v1, x1,y1,z0,u1,v0, x1,y1,z1,u0,v0, 1,0,0);
    }

    private static void quad(VertexConsumer out, Matrix4f matrix, Matrix3f normal,
                             float red, float green, float blue, int light, int overlay,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3,
                             float nx, float ny, float nz) {
        vertex(out, matrix, normal, x0,y0,z0,u0,v0, red,green,blue, light,overlay, nx,ny,nz);
        vertex(out, matrix, normal, x1,y1,z1,u1,v1, red,green,blue, light,overlay, nx,ny,nz);
        vertex(out, matrix, normal, x2,y2,z2,u2,v2, red,green,blue, light,overlay, nx,ny,nz);
        vertex(out, matrix, normal, x3,y3,z3,u3,v3, red,green,blue, light,overlay, nx,ny,nz);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float z, float u, float v,
                               float red, float green, float blue, int light, int overlay,
                               float nx, float ny, float nz) {
        out.vertex(matrix, x, y, z).color(red, green, blue, 1.0F).uv(u, v)
                .overlayCoords(overlay).uv2(light).normal(normal, nx, ny, nz).endVertex();
    }
}
