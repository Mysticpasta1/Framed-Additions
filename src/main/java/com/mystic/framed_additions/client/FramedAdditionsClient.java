package com.mystic.framed_additions.client;

import com.mystic.framed_additions.CopiedBlockType;
import com.mystic.framed_additions.CopiedFramedBlock;
import com.mystic.framed_additions.CopiedFramedBlockEntity;
import com.mystic.framed_additions.FramedAdditions;
import com.mystic.framed_additions.FramedRegistry;
import com.mystic.framed_additions.PartTargetPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;
import xfacthd.framedblocks.api.camo.CamoContainer;
import xfacthd.framedblocks.api.render.debug.AttachDebugRenderersEvent;
import xfacthd.framedblocks.api.render.debug.BlockDebugRenderer;

@EventBusSubscriber(modid = FramedAdditions.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FramedAdditionsClient {
    private FramedAdditionsClient() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(net.neoforged.neoforge.event.AddPackFindersEvent event) {
        DynamicFramedResources.add(event);
    }

    @SubscribeEvent
    public static void onGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(FramedAdditions.MODID, "copy"), CopyGeometry.LOADER);
    }

    @SubscribeEvent
    public static void onAttachDebugRenderers(AttachDebugRenderersEvent event) {
        BlockEntityType<FramedBlockEntity> type = FramedRegistry.BLOCK_ENTITY;
        if (type == null) {
            return;
        }
        // FramedBlocks looks up this set while aiming at a framed block and crashes when it is absent.
        event.attach(type, NO_DEBUG_RENDERER);
    }

    private static final BlockDebugRenderer<FramedBlockEntity> NO_DEBUG_RENDERER = new BlockDebugRenderer<>() {
        @Override
        public void render(FramedBlockEntity blockEntity, BlockHitResult hit, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    };

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (FramedRegistry.BLOCK_ENTITY != null) {
            event.registerBlockEntityRenderer(FramedRegistry.BLOCK_ENTITY, CopiedBlockEntityRenderer::new);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        if (FramedRegistry.BLOCKS.isEmpty()) {
            return;
        }
        event.register((state, level, pos, tint) -> {
            if (tint < 0 || level == null || pos == null) {
                return 0xFFFFFF;
            }
            int part = tint / 16;
            int local = tint % 16;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof CopiedFramedBlockEntity framed) {
                CamoContainer<?, ?> camo = part == 0 ? framed.getCamo() : framed.partCamo(part);
                if (!camo.isEmpty()) {
                    return camo.getTintColor(level, pos, local);
                }
            }
            if (state.getBlock() instanceof CopiedFramedBlock copied) {
                BlockState source = copied.copiedType().toSourceState(state);
                return Minecraft.getInstance().getBlockColors().getColor(source, level, pos, local);
            }
            return 0xFFFFFF;
        }, FramedRegistry.BLOCKS.toArray(Block[]::new));
    }

    @EventBusSubscriber(modid = FramedAdditions.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    static final class SourceShapes {
        private SourceShapes() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            CopiedBlockType.enableSourceShapes();
        }

        @SubscribeEvent
        public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
            if (!event.getLevel().isClientSide()) {
                return;
            }
            BlockState state = event.getLevel().getBlockState(event.getPos());
            if (!(state.getBlock() instanceof CopiedFramedBlock)) {
                return;
            }
            // This runs before the use packet, so the server can read the part while handling the click.
            int part = CopiedFramedModel.targetPart(state, event.getHitVec());
            PacketDistributor.sendToServer(new PartTargetPayload(event.getPos(), part));
        }
    }
}
