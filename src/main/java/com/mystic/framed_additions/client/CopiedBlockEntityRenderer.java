package com.mystic.framed_additions.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mystic.framed_additions.CopiedFramedBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;

/**
 * Draws the copied block entity, or the camouflage block entity, with the stored data.
 * The NBT wand turns this off.
 */
public final class CopiedBlockEntityRenderer implements BlockEntityRenderer<FramedBlockEntity> {
    private final net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher;

    public CopiedBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.dispatcher = context.getBlockEntityRenderDispatcher();
    }

    @Override
    public void render(FramedBlockEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!(entity instanceof CopiedFramedBlockEntity framed)) {
            return;
        }
        BlockEntity visual = framed.visualBlockEntity();
        if (visual == null || framed.getLevel() == null) {
            return;
        }
        BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(visual);
        if (renderer == null) {
            return;
        }
        // The dispatcher skips a block entity whose type does not match the framed block in the world.
        // Campfire items, pots, and the other copied entities are drawn from their own state instead.
        int light = LevelRenderer.getLightColor(framed.getLevel(), framed.getBlockPos());
        renderer.render(visual, partialTick, poseStack, buffer, light, packedOverlay);
    }

    @Override
    public boolean shouldRender(FramedBlockEntity entity, Vec3 cameraPos) {
        return entity instanceof CopiedFramedBlockEntity framed && framed.visualBlockEntity() != null;
    }

    @Override
    public AABB getRenderBoundingBox(FramedBlockEntity entity) {
        if (!(entity instanceof CopiedFramedBlockEntity framed)) {
            return BlockEntityRenderer.super.getRenderBoundingBox(entity);
        }
        BlockEntity visual = framed.visualBlockEntity();
        if (visual == null) {
            return BlockEntityRenderer.super.getRenderBoundingBox(entity);
        }
        BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(visual);
        if (renderer == null) {
            return BlockEntityRenderer.super.getRenderBoundingBox(entity);
        }
        return renderer.getRenderBoundingBox(visual);
    }
}
