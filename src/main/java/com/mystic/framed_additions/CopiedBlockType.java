package com.mystic.framed_additions;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.FramedProperties;
import xfacthd.framedblocks.api.predicate.contex.ConTexMode;
import xfacthd.framedblocks.api.predicate.contex.ConnectionPredicate;
import xfacthd.framedblocks.api.predicate.cull.SideSkipPredicate;
import xfacthd.framedblocks.api.predicate.fullface.FullFacePredicate;
import xfacthd.framedblocks.api.shapes.ShapeProvider;
import xfacthd.framedblocks.api.type.IBlockType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class CopiedBlockType implements IBlockType {
    private static final long MAX_VARIANT_STATES = 2048L;

    private final Block source;
    private final String name;
    private final boolean fullBlock;
    private final List<Property<?>> properties;
    private final FullFacePredicate fullFace;
    private static volatile boolean sourceShapesEnabled;

    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();
    private boolean loggedShapeFailure;

    /**
     * Source shapes stay unread until the game has finished loading resources.
     * Reading them during registration initializes other mods' client classes too early,
     * and a failed class initializer stays broken for the rest of the launch.
     */
    public static void enableSourceShapes() {
        sourceShapesEnabled = true;
    }

    private CopiedBlockType(Block source) {
        this.source = source;
        this.name = String.valueOf(ForgeRegistries.BLOCKS.getKey(source));
        this.fullBlock = source.defaultBlockState().canOcclude();
        this.properties = selectProperties(source, fullBlock);
        this.fullFace = (state, side) -> {
            // Until source shapes are safe to query, assume faces are not full.
            // FramedBlocks may cache this result while building its state cache;
            // claiming a full cube here makes slabs and other partial blocks cull neighbors incorrectly.
            if (!sourceShapesEnabled) {
                return false;
            }
            try {
                return Block.isFaceFull(shapeFor(state), side);
            } catch (Throwable exception) {
                return fullBlock;
            }
        };
    }

    public static CopiedBlockType of(Block source) {
        return new CopiedBlockType(source);
    }

    public List<Property<?>> properties() {
        return properties;
    }

    public BlockState toSourceState(@Nullable BlockState framed) {
        BlockState sourceState = source.defaultBlockState();
        if (framed == null) {
            return sourceState;
        }
        for (Property<?> property : properties) {
            if (framed.hasProperty(property)) {
                sourceState = copyProperty(sourceState, framed, property);
            }
        }
        return sourceState;
    }

    public BlockState applySourceState(BlockState framed, BlockState sourceState) {
        for (Property<?> property : properties) {
            if (sourceState.hasProperty(property)) {
                framed = copyProperty(framed, sourceState, property);
            }
        }
        return framed;
    }

    public VoxelShape shapeFor(@Nullable BlockState framed) {
        if (!sourceShapesEnabled) {
            return Shapes.block();
        }
        return shapes.computeIfAbsent(toSourceState(framed), this::shapeOf);
    }

    private VoxelShape shapeOf(BlockState state) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            return shape == null ? Shapes.block() : shape;
        } catch (Throwable exception) {
            if (!loggedShapeFailure) {
                loggedShapeFailure = true;
                FramedAdditions.LOGGER.warn("Using a full cube shape for {}: {}", ForgeRegistries.BLOCKS.getKey(source), exception.toString());
            }
            return Shapes.block();
        }
    }

    private static List<Property<?>> selectProperties(Block source, boolean solidCamo) {
        List<Property<?>> selected = new ArrayList<>();
        long states = 1L;
        for (Property<?> property : source.getStateDefinition().getProperties()) {
            if (reserved(property, solidCamo)) {
                continue;
            }
            int values = property.getPossibleValues().size();
            if (values <= 0 || states > MAX_VARIANT_STATES / values) {
                FramedAdditions.LOGGER.warn(
                        "Keeping {} of {} properties on {} so the framed copy stays within {} variants",
                        selected.size(),
                        source.getStateDefinition().getProperties().size(),
                        ForgeRegistries.BLOCKS.getKey(source),
                        MAX_VARIANT_STATES
                );
                break;
            }
            states *= values;
            selected.add(property);
        }
        return List.copyOf(selected);
    }

    private static boolean reserved(Property<?> property, boolean solidCamo) {
        String name = property.getName();
        if (FramedProperties.GLOWING.getName().equals(name) || FramedProperties.PROPAGATES_SKYLIGHT.getName().equals(name)) {
            return true;
        }
        return solidCamo && FramedProperties.SOLID.getName().equals(name);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyProperty(BlockState to, BlockState from, Property property) {
        return to.setValue(property, from.getValue(property));
    }

    @Override
    public boolean canOccludeWithSolidCamo() {
        return fullBlock;
    }

    @Override
    public boolean hasSpecialHitbox() {
        return false;
    }

    @Override
    public FullFacePredicate getFullFacePredicate() {
        return fullFace;
    }

    @Override
    public SideSkipPredicate getSideSkipPredicate() {
        return SideSkipPredicate.FALSE;
    }

    @Override
    public ConnectionPredicate getConnectionPredicate() {
        return ConnectionPredicate.FALSE;
    }

    @Override
    public ShapeProvider generateShapes(ImmutableList<BlockState> states) {
        return new ShapeProvider() {
            @Override
            public VoxelShape get(BlockState state) {
                return shapeFor(state);
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public void forEach(BiConsumer<BlockState, VoxelShape> consumer) {
                for (BlockState state : states) {
                    consumer.accept(state, shapeFor(state));
                }
            }
        };
    }

    @Override
    public boolean hasSpecialTile() {
        return false;
    }

    @Override
    public boolean hasBlockItem() {
        return true;
    }

    @Override
    public boolean supportsWaterLogging() {
        return false;
    }

    @Override
    public boolean supportsConnectedTextures() {
        return false;
    }

    @Override
    public ConTexMode getMinimumConTexMode() {
        return ConTexMode.NONE;
    }

    @Override
    public boolean allowMakingIntangible() {
        return false;
    }

    @Override
    public boolean canLockState() {
        return false;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int compareTo(IBlockType other) {
        return getName().compareTo(other.getName());
    }
}
