package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FramedRegistry {
    public static final List<CopiedFramedBlock> BLOCKS = new ArrayList<>();
    public static final List<Item> ITEMS = new ArrayList<>();
    public static Item ICON;
    public static Item NBT_WAND;
    public static BlockEntityType<FramedBlockEntity> BLOCK_ENTITY;

    @Nullable
    public static CopiedFramedBlock copyOf(Block source) {
        for (CopiedFramedBlock block : BLOCKS) {
            if (block.source() == source) {
                return block;
            }
        }
        return null;
    }

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

    static String categoryKey(Block source) {
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(source);
        if (sourceId != null) {
            String label = CopiedFramedBlock.shapeLabel(sourceId.getPath());
            if (label != null) {
                return "type:" + label.toLowerCase(Locale.ROOT);
            }
            return "block:" + sourceId;
        }
        return "block:" + System.identityHashCode(source);
    }

    static String shapeKey(Block source) {
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(source);
        // A sculk sensor's outline matches a slab, which would hide it from the creative tab.
        if (source instanceof SculkSensorBlock) {
            return "block:" + sourceId;
        }
        if (sourceId != null) {
            String label = CopiedFramedBlock.shapeLabel(sourceId.getPath());
            if (label != null) {
                return "type:" + label.toLowerCase(Locale.ROOT);
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
