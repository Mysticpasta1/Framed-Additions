package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import xfacthd.framedblocks.api.block.FramedBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FramedRegistry {
    public static final List<CopiedFramedBlock> BLOCKS = new ArrayList<>();
    public static final List<Item> ITEMS = new ArrayList<>();
    public static Item ICON;
    public static BlockEntityType<FramedBlockEntity> BLOCK_ENTITY;

    public static List<CopiedFramedBlock> uniqueShapeBlocks() {
        Map<String, CopiedFramedBlock> unique = new LinkedHashMap<>();
        for (CopiedFramedBlock block : BLOCKS) {
            unique.putIfAbsent(shapeKey(block), block);
        }
        return List.copyOf(unique.values());
    }

    private static String shapeKey(CopiedFramedBlock block) {
        return shapeKey(block.source());
    }

    static String categoryKey(net.minecraft.world.level.block.Block source) {
        net.minecraft.resources.ResourceLocation sourceId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(source);
        if (sourceId != null) {
            String label = CopiedFramedBlock.shapeLabel(sourceId.getPath());
            if (label != null) {
                return "type:" + label.toLowerCase(java.util.Locale.ROOT);
            }
            return "block:" + sourceId;
        }
        return "block:" + System.identityHashCode(source);
    }

    static String shapeKey(net.minecraft.world.level.block.Block source) {
        net.minecraft.resources.ResourceLocation sourceId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(source);
        if (sourceId != null) {
            String label = CopiedFramedBlock.shapeLabel(sourceId.getPath());
            if (label != null) {
                return "type:" + label.toLowerCase(java.util.Locale.ROOT);
            }
        }
        try {
            VoxelShape shape = source.defaultBlockState().getShape(
                    EmptyBlockGetter.INSTANCE,
                    BlockPos.ZERO,
                    CollisionContext.empty()
            );
            List<AABB> boxes = shape.toAabbs();
            if (boxes.isEmpty()) {
                return "block:" + sourceId;
            }
            List<String> parts = new ArrayList<>();
            for (AABB box : boxes) {
                parts.add(Double.toHexString(box.minX) + "," + Double.toHexString(box.minY) + "," + Double.toHexString(box.minZ)
                        + "," + Double.toHexString(box.maxX) + "," + Double.toHexString(box.maxY) + "," + Double.toHexString(box.maxZ));
            }
            parts.sort(String::compareTo);
            return "shape:" + String.join(";", parts);
        } catch (RuntimeException exception) {
            return String.valueOf(sourceId);
        }
    }

    private FramedRegistry() {
    }
}
