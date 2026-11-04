package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.AbstractFramedBlock;
import xfacthd.framedblocks.api.block.FramedBlockEntity;
import xfacthd.framedblocks.api.block.FramedProperties;
import xfacthd.framedblocks.api.block.IFramedBlock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CopiedFramedBlock extends AbstractFramedBlock {
    private static final ThreadLocal<CopiedBlockType> BUILDING = new ThreadLocal<>();
    private final Block source;

    public static CopiedFramedBlock create(Block source) {
        CopiedBlockType type = CopiedBlockType.of(source);
        BUILDING.set(type);
        try {
            return new CopiedFramedBlock(source, type);
        } catch (Throwable failure) {
            // Block's constructor adds an intrusive holder before the rest of init can fail.
            // Leaving that holder unregistered crashes registry freeze.
            discardUnregisteredCopy();
            throw failure;
        } finally {
            BUILDING.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static void discardUnregisteredCopy() {
        try {
            Field field = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
            field.setAccessible(true);
            Object raw = field.get(BuiltInRegistries.BLOCK);
            if (!(raw instanceof Map<?, ?> holders) || holders.isEmpty()) {
                return;
            }
            Set<Block> registered = Collections.newSetFromMap(new IdentityHashMap<>());
            registered.addAll(FramedRegistry.BLOCKS);
            ((Map<Object, ?>) holders).keySet().removeIf(key ->
                    key instanceof CopiedFramedBlock block && !registered.contains(block)
            );
        } catch (Exception exception) {
            FramedAdditions.LOGGER.error("Could not drop an unregistered framed block", exception);
        }
    }

    private CopiedFramedBlock(Block source, CopiedBlockType type) {
        super(type, IFramedBlock.createProperties(type));
        this.source = source;
        registerDefaultState(type.applySourceState(defaultBlockState(), source.defaultBlockState()));
    }

    public Block source() {
        return source;
    }

    public CopiedBlockType copiedType() {
        return (CopiedBlockType) getBlockType();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        CopiedBlockType type = BUILDING.get();
        if (type == null) {
            return;
        }
        for (Property<?> property : type.properties()) {
            builder.add(property);
        }
        if (type.canOccludeWithSolidCamo()) {
            builder.add(FramedProperties.SOLID);
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState framed = defaultBlockState();
        try {
            BlockState placed = source.getStateForPlacement(context);
            if (placed != null) {
                framed = copiedType().applySourceState(framed, placed);
            }
        } catch (RuntimeException ignored) {
            // The source block rejected this placement context. The default variant still places.
        }
        return framed;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        try {
            BlockState sourceUpdated = copiedType().toSourceState(updated).updateShape(direction, asSourceNeighbor(neighborState), level, pos, neighborPos);
            if (sourceUpdated.is(source)) {
                return copiedType().applySourceState(updated, sourceUpdated);
            }
        } catch (RuntimeException ignored) {
            // Keep the framed state when the copied block cannot update from this neighbor.
        }
        return updated;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return copiedType().applySourceState(state, copiedType().toSourceState(state).rotate(rotation));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return copiedType().applySourceState(state, copiedType().toSourceState(state).mirror(mirror));
    }

    private BlockState asSourceNeighbor(BlockState neighborState) {
        if (neighborState.is(source)) {
            return neighborState;
        }
        if (neighborState.getBlock() instanceof CopiedFramedBlock copied && copied.source == source) {
            return copied.copiedType().toSourceState(neighborState);
        }
        return neighborState;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FramedBlockEntity(FramedRegistry.BLOCK_ENTITY, pos, state);
    }

    @Override
    public MutableComponent getName() {
        String path = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(source).getPath();
        String shape = shapeLabel(path);
        return Component.translatable("item.framed_additions.copy", shape == null ? source.getName() : Component.literal(shape));
    }

    @Nullable
    static String shapeLabel(String path) {
        String[][] suffixes = {
                {"_fence_gate", "Fence Gate"}, {"_pressure_plate", "Pressure Plate"},
                {"_trapdoor", "Trapdoor"}, {"_stairs", "Stairs"}, {"_slab", "Slab"},
                {"_button", "Button"}, {"_fence", "Fence"},
                {"_hanging_sign", "Hanging Sign"}, {"_wall_sign", "Wall Sign"},
                {"_wall_banner", "Wall Banner"}, {"_wall_head", "Wall Head"}, {"_wall_skull", "Wall Head"},
                {"_door", "Door"}, {"_banner", "Banner"}, {"_head", "Head"}, {"_skull", "Head"},
                {"_wall", "Wall"},
                {"_bell", "Bell"}, {"_sign", "Sign"}, {"_pane", "Pane"}, {"_bars", "Bars"}
        };
        for (String[] suffix : suffixes) {
            if (path.endsWith(suffix[0])) {
                return suffix[1];
            }
        }
        return null;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = new ArrayList<>();
        if (asItem() != Items.AIR) {
            drops.add(new ItemStack(this));
        }
        return getCamoDrops(drops, builder);
    }
}
