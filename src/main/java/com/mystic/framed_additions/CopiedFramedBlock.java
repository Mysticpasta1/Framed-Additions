package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.NyliumBlock;
import net.minecraft.world.level.block.RootedDirtBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.ItemAbility;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.AbstractFramedBlock;
import xfacthd.framedblocks.api.block.FramedProperties;
import xfacthd.framedblocks.api.block.IFramedBlock;
import xfacthd.framedblocks.api.camo.CamoContainer;
import xfacthd.framedblocks.api.camo.CamoContainerFactory;
import xfacthd.framedblocks.api.camo.CamoContainerHelper;
import xfacthd.framedblocks.api.camo.block.AbstractBlockCamoContainer;
import xfacthd.framedblocks.api.camo.empty.EmptyCamoContainer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CopiedFramedBlock extends AbstractFramedBlock {
    private static final VoxelShape BEACON_BEAM = box(5, 0, 5, 11, 16, 11);
    // Clients see the change. Neighbor shapes stay quiet until every half is the framed block.
    private static final int PLACED_QUIETLY = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final ThreadLocal<CopiedBlockType> BUILDING = new ThreadLocal<>();
    private static final ThreadLocal<Map<Long, CompoundTag>> FRAMED_HANDOFF = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Boolean> SCULK_TICK = ThreadLocal.withInitial(() -> false);
    private final Block source;
    @Nullable
    private BlockEntityType<?> sourceEntityType;
    private boolean sourceEntityTypeReady;
    private int campfireHurt = -1;

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
        if (source instanceof PointedDripstoneBlock) {
            framed = orientDripstone(framed, context);
        } else if (source instanceof BigDripleafBlock || source instanceof BigDripleafStemBlock) {
            framed = copyDripleafFacing(framed, context);
        }
        return framed;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        // Doors, beds, and tall plants place the other half inside the source block.
        // The origin is briefly that source block so the second half is written, then both
        // halves become this framed copy before any neighbor is allowed to see a mixed pair.
        BlockPos origin = pos.immutable();
        BlockState sourceState = copiedType().toSourceState(state);
        BlockPos min = origin.offset(-2, -2, -2);
        BlockPos max = origin.offset(2, 2, 2);
        CompoundTag carried = blockEntityData(stack);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            before.put(cursor.immutable(), level.getBlockState(cursor));
        }
        level.setBlock(origin, sourceState, PLACED_QUIETLY);
        try {
            source.setPlacedBy(level, origin, sourceState, placer, stack);
        } catch (RuntimeException ignored) {
            // The source rejected placement of its other half. The framed block still remains.
        }
        CompoundTag originData = CopiedFramedBlockEntity.captureForeignData(level, origin);
        List<BlockPos> spawned = new ArrayList<>();
        Map<BlockPos, CompoundTag> spawnedData = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            BlockPos at = entry.getKey();
            if (at.equals(origin)) {
                continue;
            }
            BlockState prior = entry.getValue();
            BlockState now = level.getBlockState(at);
            if (prior.is(source)) {
                if (!now.equals(prior)) {
                    level.setBlock(at, prior, PLACED_QUIETLY);
                }
                continue;
            }
            if (now.is(source)) {
                spawned.add(at);
                CompoundTag partData = CopiedFramedBlockEntity.captureForeignData(level, at);
                if (partData != null) {
                    spawnedData.put(at, partData);
                }
            }
        }
        BlockState originNow = level.getBlockState(origin);
        BlockState framedOrigin = originNow.is(source) ? copiedType().applySourceState(state, originNow) : state;
        level.setBlock(origin, framedOrigin, PLACED_QUIETLY);
        for (BlockPos spawnedPos : spawned) {
            BlockState found = level.getBlockState(spawnedPos);
            if (found.is(source)) {
                level.setBlock(spawnedPos, copiedType().applySourceState(defaultBlockState(), found), PLACED_QUIETLY);
            }
        }
        BlockState placed = level.getBlockState(origin);
        placed.updateNeighbourShapes(level, origin, Block.UPDATE_ALL);
        super.setPlacedBy(level, origin, placed, placer, stack);
        keepSourceData(level, origin, originData, carried);
        for (BlockPos spawnedPos : spawned) {
            BlockState framed = level.getBlockState(spawnedPos);
            if (framed.is(this)) {
                framed.updateNeighbourShapes(level, spawnedPos, Block.UPDATE_ALL);
                super.setPlacedBy(level, spawnedPos, framed, placer, stack);
                keepSourceData(level, spawnedPos, spawnedData.get(spawnedPos), null);
            }
        }
    }

    @Override
    public BlockState rotate(BlockState state, Direction side, Rotation rotation) {
        // The FramedBlocks wrench cycles an axis instead of only swapping the horizontal ones.
        if (rotation != Rotation.NONE) {
            for (Property<?> property : state.getProperties()) {
                if ("axis".equals(property.getName())) {
                    return cycleProperty(state, property);
                }
            }
        }
        return super.rotate(state, side, rotation);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        int part = level.isClientSide ? 0 : PartTargets.take(player, pos);
        if (stack.getItem() == FramedRegistry.NBT_WAND) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
                boolean enabled = framed.toggleInheritNbt();
                player.displayClientMessage(Component.translatable(enabled
                        ? "message.framed_additions.nbt_wand.on"
                        : "message.framed_additions.nbt_wand.off"), true);
                syncBlockEntity(level, pos);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && part > 0 && level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
            ItemInteractionResult partResult = applyTexturePart(framed, part, stack, state, level, pos, player, hand, hit);
            if (partResult != null) {
                return partResult;
            }
        }
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        ItemInteractionResult composted = compost(stack, state, level, pos, player);
        if (composted != null) {
            return composted;
        }
        if (source instanceof CampfireBlock && level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
            ItemInteractionResult cooked = framed.offerCampfire(stack, player);
            if (cooked != null) {
                return cooked;
            }
        }
        // Bone meal never grows a plant from a framed block, and it is not camouflage.
        if (stack.is(Items.BONE_MEAL)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // A fluid bucket fills or empties a cauldron. It never applies camouflage.
        if (stack.getItem() instanceof BucketItem) {
            if (source instanceof AbstractCauldronBlock) {
                ItemInteractionResult poured = useCopiedItem(stack, state, level, pos, player, hand, hit);
                if (poured != null && poured.consumesAction()) {
                    return poured;
                }
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Potions and blaze powder still use the stand. A block item is camouflage.
        if (RedstoneStandIn.opensWithItem(source) && !(source instanceof BrewingStandBlock && stack.getItem() instanceof BlockItem)) {
            ItemInteractionResult sourceResult = useCopiedItem(stack, state, level, pos, player, hand, hit);
            if (sourceResult != null && sourceResult.consumesAction()) {
                return sourceResult;
            }
        }
        // A block item still applies camouflage. Any other item uses the copied control,
        // so a repeater, lever, or button changes while the hand is not holding a block.
        if (RedstoneStandIn.participates(source) && !(stack.getItem() instanceof BlockItem)) {
            InteractionResult[] used = new InteractionResult[1];
            RedstoneStandIn.runSingle(level, pos, sourceState -> used[0] = sourceState.useWithoutItem(level, player, hit));
            if (used[0] != null && used[0].consumesAction()) {
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        // FramedBlocks applies the default state of a block item and drops its block entity data.
        // Read the stack first: a survival apply shrinks it inside the super call.
        Block camoBlock = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        BlockState richer = camoBlock == null ? null : camoState(camoBlock, player, hand, stack, hit);
        CompoundTag incoming = richer == null ? null : CopiedFramedBlockEntity.captureItemData(level, pos, richer, stack);
        CamoContainer<?, ?> before = level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed ? framed.getCamo() : null;
        ItemInteractionResult result = super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (level.isClientSide || !result.consumesAction()) {
            return result;
        }
        if (!(level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed)) {
            return result;
        }
        CamoContainer<?, ?> after = framed.getCamo();
        if (after.isEmpty()) {
            if (before != null && !before.isEmpty()) {
                framed.setCamoData(null);
                framed.clearPartCamos();
                syncBlockEntity(level, pos);
            }
            return result;
        }
        if (camoBlock == null || !(after instanceof AbstractBlockCamoContainer<?> container) || !container.getState().is(camoBlock)) {
            return result;
        }
        // A wrench rotates camouflage that is already this block. Replacing the state would undo that.
        if (richer == null || (before instanceof AbstractBlockCamoContainer<?> previous && previous.getState().is(camoBlock))) {
            return result;
        }
        AbstractBlockCamoContainer<?> updated = container;
        if (!richer.equals(container.getState())) {
            try {
                AbstractBlockCamoContainer<?> copied = container.copyWithState(richer);
                if (copied != null && copied.getFactory().isValidBlockInternal(copied.getState())) {
                    updated = copied;
                }
            } catch (RuntimeException ignored) {
                // The copied state is not one this camouflage factory can store. Keep the default state.
            }
        }
        framed.setCamoData(incoming);
        if (updated != container) {
            framed.setCamo(updated, false);
        } else {
            syncBlockEntity(level, pos);
        }
        return result;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isSpectator() || !(level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed)) {
            return InteractionResult.PASS;
        }
        BlockState sourceState = copiedType().toSourceState(state);
        if (source instanceof ComposterBlock && state.hasProperty(ComposterBlock.LEVEL) && state.getValue(ComposterBlock.LEVEL) == 8) {
            ComposterBlock.extractProduce(player, state, level, pos);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (opensContainer(sourceState)) {
            if (level.isClientSide || RedstoneStandIn.openHeldScreen(level, pos, player, hit)) {
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            return InteractionResult.PASS;
        }
        if (!RedstoneStandIn.opensWithItem(source)) {
            return InteractionResult.PASS;
        }
        InteractionResult[] sourceResult = new InteractionResult[1];
        RedstoneStandIn.runSingle(level, pos, swapped -> sourceResult[0] = swapped.useWithoutItem(level, player, hit));
        return sourceResult[0] == null ? InteractionResult.PASS : sourceResult[0];
    }

    /**
     * A lectern opens only while its copied state has a book. Other copied block entities open
     * through the real block, which stays in place until that screen closes.
     */
    private boolean opensContainer(BlockState sourceState) {
        if (source instanceof LecternBlock) {
            return sourceState.hasProperty(LecternBlock.HAS_BOOK) && sourceState.getValue(LecternBlock.HAS_BOOK);
        }
        // The enchanting table, anvil, and grindstone open a menu without a block entity menu provider.
        if (source instanceof EnchantingTableBlock || source instanceof AnvilBlock || source instanceof GrindstoneBlock) {
            return true;
        }
        if (!(source instanceof EntityBlock entityBlock)) {
            return false;
        }
        try {
            BlockEntity created = entityBlock.newBlockEntity(BlockPos.ZERO, sourceState);
            return created instanceof MenuProvider;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Fills from the block that is in the world, through level 7, then the ready tick reaches 8.
     * The first compost always adds a layer. Later composts add a layer by the item's chance.
     */
    @Nullable
    private ItemInteractionResult compost(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player) {
        if (!(source instanceof ComposterBlock) || !state.hasProperty(ComposterBlock.LEVEL)) {
            return null;
        }
        BlockState live = level.getBlockState(pos);
        if (!live.is(this) || !live.hasProperty(ComposterBlock.LEVEL)) {
            live = state;
        }
        int compostLevel = live.getValue(ComposterBlock.LEVEL);
        if (compostLevel >= 8 || ComposterBlock.getValue(stack) <= 0.0F) {
            return null;
        }
        if (!level.isClientSide && compostLevel < 7) {
            float chance = ComposterBlock.getValue(stack);
            boolean grew = compostLevel == 0 || level.getRandom().nextDouble() < chance;
            if (grew) {
                level.setBlock(pos, live.setValue(ComposterBlock.LEVEL, compostLevel + 1), Block.UPDATE_ALL);
                if (compostLevel + 1 == 7) {
                    level.scheduleTick(pos, this, 20);
                }
            }
            level.levelEvent(1500, pos, grew ? 1 : 0);
            player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
            if (!player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    private ItemInteractionResult useCopiedItem(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemInteractionResult[] sourceResult = new ItemInteractionResult[1];
        RedstoneStandIn.runSingle(level, pos, sourceState -> sourceResult[0] = sourceState.useItemOn(stack, level, player, hand, hit));
        return sourceResult[0];
    }

    @Nullable
    private ItemInteractionResult applyTexturePart(CopiedFramedBlockEntity framed, int part, ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        CamoContainer<?, ?> current = framed.partCamo(part);
        if (!current.isEmpty() && CamoContainerHelper.isValidRemovalTool(current, stack)) {
            CamoContainerHelper.removeCamo(current, level, pos, player, stack);
            framed.setPartCamo(part, EmptyCamoContainer.EMPTY, null);
            syncBlockEntity(level, pos);
            return ItemInteractionResult.sidedSuccess(false);
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState appearance = current.isEmpty() ? null : current.getContent().getAppearanceState();
        if (appearance != null && appearance.is(blockItem.getBlock())) {
            return ItemInteractionResult.sidedSuccess(false);
        }
        BlockState placed = camoState(blockItem.getBlock(), player, hand, stack, hit);
        CompoundTag incoming = CopiedFramedBlockEntity.captureItemData(level, pos, placed, stack);
        CamoContainerFactory<?> factory = CamoContainerHelper.findCamoFactory(stack);
        if (factory == null) {
            return null;
        }
        CamoContainer<?, ?> applied = applyFactory(factory, level, pos, player, stack);
        if (applied == null || applied.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        framed.setPartCamo(part, withAppliedState(applied, blockItem.getBlock(), player, hand, stack, hit), incoming);
        syncBlockEntity(level, pos);
        return ItemInteractionResult.sidedSuccess(false);
    }

    private static CamoContainer<?, ?> withAppliedState(CamoContainer<?, ?> applied, Block block, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        if (!(applied instanceof AbstractBlockCamoContainer<?> container) || !container.getState().is(block)) {
            return applied;
        }
        BlockState richer = camoState(block, player, hand, stack, hit);
        if (richer.equals(container.getState())) {
            return applied;
        }
        try {
            AbstractBlockCamoContainer<?> copied = container.copyWithState(richer);
            if (copied != null && copied.getFactory().isValidBlockInternal(copied.getState())) {
                return copied;
            }
        } catch (RuntimeException ignored) {
            // Keep the default state this camouflage factory already accepted.
        }
        return applied;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState cycleProperty(BlockState state, Property property) {
        return state.cycle(property);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CamoContainer<?, ?> applyFactory(CamoContainerFactory factory, Level level, BlockPos pos, Player player, ItemStack stack) {
        return factory.applyCamo(level, pos, player, stack);
    }

    private static BlockState camoState(Block block, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        BlockState state = null;
        try {
            state = block.getStateForPlacement(new BlockPlaceContext(player, hand, stack, hit));
        } catch (RuntimeException ignored) {
            // The block refused this click. Its default state is still valid camouflage.
        }
        if (state == null || !state.is(block)) {
            state = block.defaultBlockState();
        }
        BlockItemStateProperties properties = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
        if (!properties.isEmpty()) {
            state = properties.apply(state);
        }
        return state;
    }

    @Nullable
    private static CompoundTag blockEntityData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag();
    }

    private static void keepSourceData(Level level, BlockPos pos, @Nullable CompoundTag captured, @Nullable CompoundTag carried) {
        if (!(level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed)) {
            return;
        }
        if (framed.applyPlacementData(captured, carried)) {
            syncBlockEntity(level, pos);
        }
    }

    private static void syncBlockEntity(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }
        BlockState current = level.getBlockState(pos);
        level.sendBlockUpdated(pos, current, current, Block.UPDATE_CLIENTS);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative breaks do not drop the block the player mined, but the other half would
        // still drop when its shape update removes it. Remove that half without telling neighbors.
        if (!level.isClientSide && player.isCreative()) {
            BlockPos other = otherHalf(pos, state);
            if (other != null && level.getBlockState(other).is(this)) {
                level.setBlock(other, Blocks.AIR.defaultBlockState(), PLACED_QUIETLY | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        try {
            BlockState sourceUpdated = copiedType().toSourceState(updated).updateShape(direction, asSourceNeighbor(neighborState), level, pos, neighborPos);
            updated = mapSourceUpdate(updated, sourceUpdated, level, pos);
        } catch (RuntimeException ignored) {
            // Keep the framed state when the copied block cannot update from this neighbor.
        }
        if (source instanceof PointedDripstoneBlock && (direction == Direction.UP || direction == Direction.DOWN)) {
            updated = refreshDripstone(updated, level, pos);
        }
        return updated;
    }

    private BlockState mapSourceUpdate(BlockState framed, BlockState sourceUpdated, LevelAccessor level, BlockPos pos) {
        if (sourceUpdated.is(source)) {
            return copiedType().applySourceState(framed, sourceUpdated);
        }
        CopiedFramedBlock replacement = FramedRegistry.copyOf(sourceUpdated.getBlock());
        if (replacement == null) {
            // A failed survival check would turn the copy into air or another untracked block.
            // The framed block stays where it was placed.
            return framed;
        }
        stashFramedData(level, pos);
        BlockState next = RedstoneStandIn.carryFramedFlags(framed, replacement.defaultBlockState());
        return replacement.copiedType().applySourceState(next, sourceUpdated);
    }

    private void stashFramedData(LevelAccessor level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
            FRAMED_HANDOFF.get().put(pos.asLong(), framed.copyExtra());
        }
    }

    private static void applyFramedHandoff(Level level, BlockPos pos) {
        if (!FRAMED_HANDOFF.get().containsKey(pos.asLong())) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
            CompoundTag carried = FRAMED_HANDOFF.get().remove(pos.asLong());
            if (carried != null) {
                framed.readCarried(carried);
            }
        }
    }

    private BlockState orientDripstone(BlockState framed, BlockPlaceContext context) {
        if (!framed.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) || !framed.hasProperty(BlockStateProperties.DRIPSTONE_THICKNESS)) {
            return framed;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction preferred = context.getNearestLookingVerticalDirection().getOpposite();
        Direction tip = dripstoneTip(level, pos, preferred);
        boolean merge = !context.isSecondaryUseActive();
        DripstoneThickness thickness = thicknessAt(level, pos, tip, merge);
        framed = framed.setValue(BlockStateProperties.VERTICAL_DIRECTION, tip).setValue(BlockStateProperties.DRIPSTONE_THICKNESS, thickness);
        if (framed.hasProperty(BlockStateProperties.WATERLOGGED)) {
            framed = framed.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
        }
        return framed;
    }

    private BlockState refreshDripstone(BlockState framed, LevelReader level, BlockPos pos) {
        if (!framed.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) || !framed.hasProperty(BlockStateProperties.DRIPSTONE_THICKNESS)) {
            return framed;
        }
        Direction tip = framed.getValue(BlockStateProperties.VERTICAL_DIRECTION);
        boolean merge = framed.getValue(BlockStateProperties.DRIPSTONE_THICKNESS) == DripstoneThickness.TIP_MERGE;
        DripstoneThickness thickness = thicknessAt(level, pos, tip, merge);
        if (framed.getValue(BlockStateProperties.DRIPSTONE_THICKNESS) == thickness) {
            return framed;
        }
        return framed.setValue(BlockStateProperties.DRIPSTONE_THICKNESS, thickness);
    }

    private Direction dripstoneTip(LevelReader level, BlockPos pos, Direction preferred) {
        if (dripstoneSupport(level, pos, preferred)) {
            return preferred;
        }
        Direction opposite = preferred.getOpposite();
        if (dripstoneSupport(level, pos, opposite)) {
            return opposite;
        }
        return preferred;
    }

    private boolean dripstoneSupport(LevelReader level, BlockPos pos, Direction tip) {
        BlockPos supportPos = pos.relative(tip.getOpposite());
        BlockState support = level.getBlockState(supportPos);
        if (support.isFaceSturdy(level, supportPos, tip)) {
            return true;
        }
        BlockState dripstone = sameDripstone(support);
        return dripstone != null && dripstone.getValue(BlockStateProperties.VERTICAL_DIRECTION) == tip;
    }

    private DripstoneThickness thicknessAt(LevelReader level, BlockPos pos, Direction tip, boolean merge) {
        Direction opposite = tip.getOpposite();
        BlockState forward = sameDripstone(level.getBlockState(pos.relative(tip)));
        if (points(forward, opposite)) {
            if (!merge && forward.getValue(BlockStateProperties.DRIPSTONE_THICKNESS) != DripstoneThickness.TIP_MERGE) {
                return DripstoneThickness.TIP;
            }
            return DripstoneThickness.TIP_MERGE;
        }
        if (!points(forward, tip)) {
            return DripstoneThickness.TIP;
        }
        DripstoneThickness forwardThickness = forward.getValue(BlockStateProperties.DRIPSTONE_THICKNESS);
        if (forwardThickness != DripstoneThickness.TIP && forwardThickness != DripstoneThickness.TIP_MERGE) {
            BlockState behind = sameDripstone(level.getBlockState(pos.relative(opposite)));
            return points(behind, tip) ? DripstoneThickness.MIDDLE : DripstoneThickness.BASE;
        }
        return DripstoneThickness.FRUSTUM;
    }

    @Nullable
    private BlockState sameDripstone(BlockState state) {
        BlockState resolved = state;
        if (state.getBlock() instanceof CopiedFramedBlock copied && copied.source == source) {
            resolved = copied.copiedType().toSourceState(state);
        }
        if (resolved.is(source) && resolved.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) && resolved.hasProperty(BlockStateProperties.DRIPSTONE_THICKNESS)) {
            return resolved;
        }
        return null;
    }

    private static boolean points(@Nullable BlockState state, Direction direction) {
        return state != null && state.getValue(BlockStateProperties.VERTICAL_DIRECTION) == direction;
    }

    private BlockState copyDripleafFacing(BlockState framed, BlockPlaceContext context) {
        if (!framed.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return framed;
        }
        BlockState below = dripleafState(context.getLevel().getBlockState(context.getClickedPos().below()));
        if (below != null && below.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return framed.setValue(BlockStateProperties.HORIZONTAL_FACING, below.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return framed;
    }

    @Nullable
    private static BlockState dripleafState(BlockState state) {
        BlockState resolved = state;
        if (state.getBlock() instanceof CopiedFramedBlock copied
                && (copied.source() instanceof BigDripleafBlock || copied.source() instanceof BigDripleafStemBlock)) {
            resolved = copied.copiedType().toSourceState(state);
        }
        if (resolved.getBlock() instanceof BigDripleafBlock || resolved.getBlock() instanceof BigDripleafStemBlock) {
            return resolved;
        }
        return null;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return copiedType().applySourceState(state, copiedType().toSourceState(state).rotate(rotation));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return copiedType().applySourceState(state, copiedType().toSourceState(state).mirror(mirror));
    }

    private static BlockState asSourceNeighbor(BlockState neighborState) {
        if (neighborState.getBlock() instanceof CopiedFramedBlock copied) {
            return copied.copiedType().toSourceState(neighborState);
        }
        return neighborState;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        CopiedFramedBlockEntity entity = new CopiedFramedBlockEntity(FramedRegistry.BLOCK_ENTITY, pos, state);
        CompoundTag carried = FRAMED_HANDOFF.get().remove(pos.asLong());
        if (carried != null) {
            entity.readCarried(carried);
        }
        return entity;
    }

    @Override
    @Nullable
    public <T extends BlockEntity> GameEventListener getListener(ServerLevel level, T blockEntity) {
        if (source instanceof SculkSensorBlock && blockEntity instanceof CopiedFramedBlockEntity framed) {
            return framed.sculkListener();
        }
        return null;
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != FramedRegistry.BLOCK_ENTITY) {
            return null;
        }
        if (source instanceof CampfireBlock campfire) {
            return (tickLevel, tickPos, tickState, blockEntity) -> {
                if (!(blockEntity instanceof CopiedFramedBlockEntity framed)) {
                    return;
                }
                BlockState current = copiedType().toSourceState(tickState);
                if (tickLevel.isClientSide) {
                    framed.tickCampfireParticles(tickLevel, current, campfire);
                    return;
                }
                framed.tickCampfire(tickLevel, current);
            };
        }
        if (source instanceof SculkSensorBlock) {
            if (level.isClientSide) {
                return null;
            }
            return (tickLevel, tickPos, tickState, blockEntity) -> {
                if (blockEntity instanceof CopiedFramedBlockEntity framed) {
                    framed.tickSculk(tickLevel);
                }
            };
        }
        if (!(source instanceof BaseEntityBlock entityBlock)) {
            return null;
        }
        BlockState sourceState = copiedType().toSourceState(state);
        BlockEntityType<?> sourceType = sourceEntityType(sourceState);
        if (sourceType == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        BlockEntityType<BlockEntity> tickerType = (BlockEntityType<BlockEntity>) sourceType;
        try {
            if (entityBlock.getTicker(level, sourceState, tickerType) == null) {
                return null;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (!(blockEntity instanceof CopiedFramedBlockEntity framed)) {
                return;
            }
            BlockState current = copiedType().toSourceState(tickState);
            BlockEntityTicker<BlockEntity> live;
            try {
                live = entityBlock.getTicker(tickLevel, current, tickerType);
            } catch (RuntimeException ignored) {
                return;
            }
            if (live != null) {
                framed.tickFromSource(tickLevel, current, live);
            }
        };
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        CopiedBlockType type = typeNow();
        if (type == null) {
            return false;
        }
        try {
            return type.toSourceState(state).isRandomlyTicking();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (source == null || RedstoneStandIn.isSwapping()) {
            return;
        }
        // A framed cactus, bamboo, vine, kelp, chorus flower, or other plant keeps the shape it was placed with.
        if (growsAsPlant(source)) {
            return;
        }
        RedstoneStandIn.runSingle(level, pos, sourceState -> sourceState.randomTick(level, pos, random));
    }

    /**
     * Blocks whose random tick places more of the plant or a grown form.
     * Nylium and rooted dirt stay out of this check so a covered nylium can still become netherrack.
     */
    static boolean growsAsPlant(Block block) {
        if (block instanceof NyliumBlock || block instanceof RootedDirtBlock) {
            return false;
        }
        return block instanceof BonemealableBlock
                || block instanceof SpreadingSnowyDirtBlock
                || block instanceof CactusBlock
                || block instanceof SugarCaneBlock
                || block instanceof ChorusFlowerBlock
                || block instanceof VineBlock
                || block instanceof GrowingPlantBlock
                || block instanceof BushBlock
                || block instanceof BambooStalkBlock
                || block instanceof BambooSaplingBlock;
    }

    private CopiedBlockType typeNow() {
        if (source != null) {
            return copiedType();
        }
        return BUILDING.get();
    }

    @Nullable
    private BlockEntityType<?> sourceEntityType(BlockState sourceState) {
        if (sourceEntityTypeReady) {
            return sourceEntityType;
        }
        if (!(source instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) {
            sourceEntityTypeReady = true;
            return null;
        }
        try {
            BlockEntity created = entityBlock.newBlockEntity(BlockPos.ZERO, sourceState);
            if (created == null) {
                return null;
            }
            sourceEntityType = created.getType();
            sourceEntityTypeReady = true;
            return sourceEntityType;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction) {
        try {
            return source.canConnectRedstone(copiedType().toSourceState(state), level, pos, direction);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        try {
            return copiedType().toSourceState(state).isSignalSource();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        BlockState sourceState = copiedType().toSourceState(state);
        if (source instanceof ComparatorBlock) {
            if (!sourceState.getValue(DiodeBlock.POWERED) || sourceState.getValue(HorizontalDirectionalBlock.FACING) != direction) {
                return 0;
            }
            if (level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
                return framed.sourceOutputSignal();
            }
            return 0;
        }
        try {
            return sourceState.getSignal(level, pos, direction);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (source instanceof ComparatorBlock || source instanceof DiodeBlock) {
            return getSignal(state, level, pos, direction);
        }
        try {
            return copiedType().toSourceState(state).getDirectSignal(level, pos, direction);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        try {
            return copiedType().toSourceState(state).hasAnalogOutputSignal();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!hasAnalogOutputSignal(state)) {
            return 0;
        }
        if (source instanceof SculkSensorBlock && state.hasProperty(SculkSensorBlock.PHASE)) {
            if (state.getValue(SculkSensorBlock.PHASE) != SculkSensorPhase.ACTIVE) {
                return 0;
            }
            if (level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
                return framed.lastVibrationFrequency();
            }
            return 0;
        }
        int[] signal = new int[1];
        RedstoneStandIn.runSingle(level, pos, sourceState -> signal[0] = sourceState.getAnalogOutputSignal(level, pos));
        return signal[0];
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return true;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (source instanceof CactusBlock) {
            try {
                return copiedType().toSourceState(state).getCollisionShape(level, pos, context);
            } catch (RuntimeException ignored) {
                // The cactus collision could not be read. The framed outline still blocks movement.
            }
        }
        return super.getCollisionShape(state, level, pos, context);
    }

    @Override
    public boolean hasDynamicLightEmission(BlockState state) {
        if (super.hasDynamicLightEmission(state)) {
            return true;
        }
        // The framed properties bake light as 0. The copied state still has the source light.
        if (source == null) {
            return false;
        }
        try {
            BlockState sourceState = copiedType().toSourceState(state);
            return source.hasDynamicLightEmission(sourceState) || sourceState.getLightEmission() > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        int glowing = 0;
        if (state.getValue(FramedProperties.GLOWING)) {
            glowing = super.getLightEmission(state, level, pos);
        }
        if (source == null) {
            return glowing;
        }
        try {
            int copied = copiedType().toSourceState(state).getLightEmission(level, pos);
            return Math.max(glowing, copied);
        } catch (RuntimeException ignored) {
            return glowing;
        }
    }

    @Override
    @Nullable
    public BlockState getToolModifiedState(BlockState state, UseOnContext context, ItemAbility ability, boolean simulate) {
        if (source == null) {
            return null;
        }
        BlockState modified;
        try {
            // Flint, fire charges, and shovels decide from the real campfire or candle state.
            // A result that is a different block would replace the framed copy, so it is ignored.
            modified = source.getToolModifiedState(copiedType().toSourceState(state), context, ability, simulate);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (modified == null || !modified.is(source)) {
            return null;
        }
        return copiedType().applySourceState(state, modified);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        try {
            source.animateTick(copiedType().toSourceState(state), level, pos, random);
        } catch (RuntimeException ignored) {
            // The copied block has no ambient particles for this state.
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moved) {
        if (RedstoneStandIn.isSwapping()) {
            return;
        }
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moved);
        if (RedstoneStandIn.participates(source)) {
            RedstoneStandIn.runConnected(level, pos, sourceState -> sourceState.handleNeighborChanged(level, pos, neighbor, neighborPos, moved));
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        if (RedstoneStandIn.isSwapping()) {
            return;
        }
        applyFramedHandoff(level, pos);
        super.onPlace(state, level, pos, oldState, moved);
        if (!RedstoneStandIn.participates(source)) {
            return;
        }
        BlockState previous = oldState;
        if (oldState.getBlock() instanceof CopiedFramedBlock copied && copied.source == source) {
            previous = copied.copiedType().toSourceState(oldState);
        }
        BlockState oldSource = previous;
        RedstoneStandIn.runConnected(level, pos, sourceState -> sourceState.onPlace(level, pos, oldSource, moved));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (RedstoneStandIn.isSwapping()) {
            return;
        }
        if (!state.is(newState.getBlock())
                && !FRAMED_HANDOFF.get().containsKey(pos.asLong())
                && level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
            framed.dropStoredContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (source instanceof ComposterBlock && state.hasProperty(ComposterBlock.LEVEL) && state.getValue(ComposterBlock.LEVEL) == 7) {
            level.setBlock(pos, state.cycle(ComposterBlock.LEVEL), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.COMPOSTER_READY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return;
        }
        if (source instanceof SculkSensorBlock && state.hasProperty(SculkSensorBlock.PHASE)) {
            // setBlock notifies neighbors, and a neighbor can schedule this tick again on the same thread.
            if (SCULK_TICK.get()) {
                return;
            }
            SCULK_TICK.set(true);
            try {
                SculkSensorPhase phase = state.getValue(SculkSensorBlock.PHASE);
                if (phase == SculkSensorPhase.ACTIVE) {
                    BlockState cooldown = state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.COOLDOWN);
                    if (cooldown.hasProperty(SculkSensorBlock.POWER)) {
                        cooldown = cooldown.setValue(SculkSensorBlock.POWER, 0);
                    }
                    level.setBlock(pos, cooldown, Block.UPDATE_ALL);
                    level.scheduleTick(pos, this, 10);
                } else if (phase == SculkSensorPhase.COOLDOWN) {
                    level.setBlock(pos, state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.INACTIVE), Block.UPDATE_ALL);
                    if (!state.hasProperty(SculkSensorBlock.WATERLOGGED) || !state.getValue(SculkSensorBlock.WATERLOGGED)) {
                        level.playSound(null, pos, SoundEvents.SCULK_CLICKING_STOP, SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.2F + 0.8F);
                    }
                }
            } finally {
                SCULK_TICK.set(false);
            }
            return;
        }
        if (RedstoneStandIn.participates(source)) {
            RedstoneStandIn.runConnected(level, pos, sourceState -> sourceState.tick(level, pos, random));
        }
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        if (!RedstoneStandIn.participates(source)) {
            return super.triggerEvent(state, level, pos, id, param);
        }
        boolean[] handled = new boolean[1];
        RedstoneStandIn.runConnected(level, pos, sourceState -> handled[0] = sourceState.triggerEvent(level, pos, id, param));
        return handled[0];
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (source instanceof SculkSensorBlock
                && !level.isClientSide
                && entity.getType() != EntityType.WARDEN
                && state.hasProperty(SculkSensorBlock.PHASE)
                && SculkSensorBlock.canActivate(state)
                && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framed) {
            framed.receiveStep(server, entity);
        }
        super.stepOn(level, pos, state, entity);
    }

    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (source instanceof CactusBlock) {
            entity.hurt(level.damageSources().cactus(), 1.0F);
            return;
        }
        // Swapping in a real campfire here would remove this block entity on every tick an entity touches it.
        if (source instanceof CampfireBlock) {
            hurtOnCampfire(level, state, entity);
            return;
        }
        if (RedstoneStandIn.reactsToEntities(source)) {
            RedstoneStandIn.runSingle(level, pos, sourceState -> sourceState.entityInside(level, pos, entity));
        }
    }

    private void hurtOnCampfire(Level level, BlockState state, Entity entity) {
        BlockState sourceState = copiedType().toSourceState(state);
        if (!sourceState.hasProperty(CampfireBlock.LIT) || !sourceState.getValue(CampfireBlock.LIT)) {
            return;
        }
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        entity.hurt(level.damageSources().campfire(), campfireHurt());
    }

    private int campfireHurt() {
        if (campfireHurt >= 0) {
            return campfireHurt;
        }
        int damage = source == Blocks.SOUL_CAMPFIRE ? 2 : 1;
        try {
            Field field = CampfireBlock.class.getDeclaredField("fireDamage");
            field.setAccessible(true);
            damage = field.getInt(source);
        } catch (ReflectiveOperationException ignored) {
            // Soul campfires deal 2. Other campfires deal 1.
        }
        campfireHurt = damage;
        return damage;
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (!RedstoneStandIn.reactsToProjectiles(source)) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        RedstoneStandIn.runSingle(level, pos, sourceState -> sourceState.onProjectileHit(level, sourceState, hit, projectile));
    }

    @Override
    public BlockState getItemModelSource() {
        return defaultBlockState();
    }

    @Override
    public BlockState getJadeRenderState(BlockState state) {
        return state;
    }

    @Override
    public boolean doesBlockOccludeBeaconBeam(BlockState state, LevelReader level, BlockPos pos) {
        // The base class caches this from shapes during construction, before source shapes are safe to read.
        VoxelShape shape = copiedType().shapeFor(state);
        VoxelShape intersection = Shapes.joinUnoptimized(shape, BEACON_BEAM, BooleanOp.AND);
        return intersection.min(Direction.Axis.X) <= BEACON_BEAM.min(Direction.Axis.X)
                && intersection.min(Direction.Axis.Z) <= BEACON_BEAM.min(Direction.Axis.Z)
                && intersection.max(Direction.Axis.X) >= BEACON_BEAM.max(Direction.Axis.X)
                && intersection.max(Direction.Axis.Z) >= BEACON_BEAM.max(Direction.Axis.Z);
    }

    @Override
    public MutableComponent getName() {
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(source);
        String path = sourceId == null ? "" : sourceId.getPath();
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
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = new ArrayList<>();
        // Doors and tall plants drop from the lower half. Beds drop from the head.
        // The other half is removed by the shape update and must not drop a second item.
        if (asItem() != Items.AIR && !isSecondaryPart(state)) {
            drops.add(new ItemStack(this));
        }
        return getCamoDrops(drops, builder);
    }

    @Nullable
    private BlockPos otherHalf(BlockPos pos, BlockState state) {
        Direction facing = null;
        String half = null;
        String part = null;
        for (Property<?> property : state.getProperties()) {
            Object value = state.getValue(property);
            if ("facing".equals(property.getName()) && value instanceof Direction direction) {
                facing = direction;
            } else if ("half".equals(property.getName()) && propertyHas(property, "upper", "lower")) {
                half = valueName(value);
            } else if ("part".equals(property.getName()) && propertyHas(property, "head", "foot")) {
                part = valueName(value);
            }
        }
        if (part != null && facing != null) {
            return "foot".equals(part) ? pos.relative(facing) : pos.relative(facing.getOpposite());
        }
        if ("upper".equals(half)) {
            return pos.below();
        }
        if ("lower".equals(half)) {
            return pos.above();
        }
        return null;
    }

    private static boolean isSecondaryPart(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            Object value = state.getValue(property);
            if ("half".equals(property.getName()) && propertyHas(property, "upper", "lower")) {
                return "upper".equals(valueName(value));
            }
            if ("part".equals(property.getName()) && propertyHas(property, "head", "foot")) {
                return "foot".equals(valueName(value));
            }
        }
        return false;
    }

    private static boolean propertyHas(Property<?> property, String first, String second) {
        boolean hasFirst = false;
        boolean hasSecond = false;
        for (Comparable<?> possible : property.getPossibleValues()) {
            String name = valueName(possible);
            hasFirst |= first.equals(name);
            hasSecond |= second.equals(name);
        }
        return hasFirst && hasSecond;
    }

    private static String valueName(Object value) {
        return value instanceof StringRepresentable named ? named.getSerializedName() : String.valueOf(value);
    }
}
