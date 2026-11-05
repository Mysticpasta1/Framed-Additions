package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CalibratedSculkSensorBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem.Data;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem.Listener;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem.User;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;
import xfacthd.framedblocks.api.camo.CamoContainer;
import xfacthd.framedblocks.api.camo.CamoContainerHelper;
import xfacthd.framedblocks.api.camo.CamoContent;
import xfacthd.framedblocks.api.camo.empty.EmptyCamoContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps block entity data for the copied block and for the camouflage applied to it.
 * FramedBlocks stores only a blockstate on the camouflage, and that state is the block's default.
 */
public class CopiedFramedBlockEntity extends FramedBlockEntity {
    public static final ModelProperty<ModelData> SOURCE_MODEL_DATA = new ModelProperty<>();
    public static final ModelProperty<ModelData> CAMO_MODEL_DATA = new ModelProperty<>();
    public static final ModelProperty<List<CamoContent<?>>> PART_CONTENTS = new ModelProperty<>();
    public static final ModelProperty<List<ModelData>> PART_MODEL_DATA = new ModelProperty<>();

    static final String SOURCE_KEY = "fa_source";
    private static final String CAMO_KEY = "fa_camo";
    private static final String PARTS_KEY = "fa_parts";
    private static final String NBT_KEY = "fa_nbt";
    private static final String FREQUENCY_KEY = "fa_frequency";
    private static final ThreadLocal<Boolean> READING_MODEL_DATA = ThreadLocal.withInitial(() -> false);

    @Nullable
    private CompoundTag sourceData;
    @Nullable
    private CompoundTag camoData;
    private final List<CamoContainer<?, ?>> partCamos = new ArrayList<>();
    private final List<CompoundTag> partData = new ArrayList<>();
    private boolean inheritNbt = true;
    private int lastVibrationFrequency;
    @Nullable
    private SculkHearing sculkHearing;
    @Nullable
    private BlockState cachedSourceState;
    @Nullable
    private CompoundTag cachedSourceTag;
    private ModelData cachedSourceModel = ModelData.EMPTY;
    @Nullable
    private BlockState cachedCamoState;
    @Nullable
    private CompoundTag cachedCamoTag;
    private ModelData cachedCamoModel = ModelData.EMPTY;
    @Nullable
    private BlockEntity visual;
    @Nullable
    private BlockState visualState;
    @Nullable
    private CompoundTag visualTag;
    private boolean visualChecked;

    public CopiedFramedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public void setCamoData(@Nullable CompoundTag tag) {
        camoData = copyOrNull(tag);
        cachedCamoState = null;
        clearVisual();
    }

    public void setSourceData(@Nullable CompoundTag tag) {
        sourceData = copyOrNull(tag);
        cachedSourceState = null;
        clearVisual();
        if (level != null && !level.isClientSide) {
            setChanged();
        }
    }

    public boolean inheritNbt() {
        return inheritNbt;
    }

    public boolean toggleInheritNbt() {
        inheritNbt = !inheritNbt;
        cachedSourceState = null;
        cachedCamoState = null;
        clearVisual();
        setChanged();
        requestModelDataUpdate();
        return inheritNbt;
    }

    public CamoContainer<?, ?> partCamo(int index) {
        int slot = index - 1;
        if (slot < 0 || slot >= partCamos.size()) {
            return EmptyCamoContainer.EMPTY;
        }
        return partCamos.get(slot);
    }

    public void setPartCamo(int index, CamoContainer<?, ?> camo, @Nullable CompoundTag data) {
        int slot = index - 1;
        if (slot < 0) {
            return;
        }
        while (partCamos.size() <= slot) {
            partCamos.add(EmptyCamoContainer.EMPTY);
            partData.add(null);
        }
        partCamos.set(slot, camo == null ? EmptyCamoContainer.EMPTY : camo);
        partData.set(slot, copyOrNull(data));
        trimEmptyParts();
        setChanged();
        requestModelDataUpdate();
    }

    public void clearPartCamos() {
        if (partCamos.isEmpty()) {
            return;
        }
        partCamos.clear();
        partData.clear();
        setChanged();
        requestModelDataUpdate();
    }

    /**
     * Keeps data the source block wrote while it was placed, then restores a picked-up copy.
     * The picked-up snapshot is the full entity tag, so it wins over the temporary source entity.
     * Returns whether anything was stored.
     */
    public boolean applyPlacementData(@Nullable CompoundTag capturedSource, @Nullable CompoundTag itemData) {
        boolean changed = false;
        if (capturedSource != null) {
            sourceData = capturedSource.copy();
            changed = true;
        }
        if (itemData != null && itemData.contains(SOURCE_KEY, Tag.TAG_COMPOUND)) {
            sourceData = copyOrNull(itemData.getCompound(SOURCE_KEY));
            changed = true;
        }
        if (itemData != null && itemData.contains(CAMO_KEY, Tag.TAG_COMPOUND)) {
            camoData = copyOrNull(itemData.getCompound(CAMO_KEY));
            changed = true;
        }
        if (changed) {
            cachedSourceState = null;
            cachedCamoState = null;
            setChanged();
            requestModelDataUpdate();
        }
        return changed;
    }

    @Nullable
    public static CompoundTag captureForeignData(Level level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity instanceof FramedBlockEntity) {
            return null;
        }
        try {
            // saveCustomOnly drops data components. The full snapshot keeps items, sherds, names, and attachments.
            CompoundTag tag = entity.saveWithoutMetadata(level.registryAccess());
            return tag.isEmpty() ? null : tag.copy();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The block entity data an item would place, including components stored beside the entity tag.
     */
    @Nullable
    public static CompoundTag captureItemData(Level level, BlockPos pos, BlockState state, ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag custom = data == null || data.isEmpty() ? null : data.copyTag();
        if (!(state.getBlock() instanceof EntityBlock entityBlock) || state.getBlock() instanceof CopiedFramedBlock) {
            return copyOrNull(custom);
        }
        try {
            BlockEntity virtual = entityBlock.newBlockEntity(pos, state);
            if (virtual == null || virtual instanceof FramedBlockEntity) {
                return copyOrNull(custom);
            }
            virtual.setLevel(level);
            if (custom != null) {
                virtual.loadWithComponents(custom, level.registryAccess());
            }
            virtual.applyComponentsFromItemStack(stack);
            return copyOrNull(virtual.saveWithoutMetadata(level.registryAccess()));
        } catch (RuntimeException ignored) {
            return copyOrNull(custom);
        }
    }

    /**
     * The source block entity, or the camouflage block entity when one is applied.
     * Empty unless the NBT wand is allowing inherited data.
     */
    @Nullable
    public BlockEntity visualBlockEntity() {
        if (!inheritNbt || level == null) {
            return null;
        }
        BlockState state = null;
        CompoundTag tag = null;
        CamoContainer<?, ?> camo = getCamo();
        // The swinging bell is not in the block model. Camouflage on the top support used to
        // replace this visual, which removed the bell body.
        boolean keepSourceBell = getBlockState().getBlock() instanceof CopiedFramedBlock framed
                && framed.source() instanceof BellBlock;
        if (!keepSourceBell && !camo.isEmpty()) {
            BlockState appearance = camo.getContent().getAppearanceState();
            if (appearance != null && appearance.getBlock() instanceof EntityBlock) {
                state = appearance;
                tag = camoData;
            }
        } else if (getBlockState().getBlock() instanceof CopiedFramedBlock copied) {
            BlockState source = copied.copiedType().toSourceState(getBlockState());
            if (source.getBlock() instanceof EntityBlock) {
                state = source;
                tag = sourceData;
            }
        }
        if (state == null) {
            clearVisual();
            return null;
        }
        if (visualChecked && state.equals(visualState) && Objects.equals(tag, visualTag)) {
            return visual;
        }
        visualState = state;
        visualTag = tag;
        visualChecked = true;
        visual = createLoaded(state, tag);
        return visual;
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeExtra(tag);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readExtra(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        writeExtra(tag);
        return tag;
    }

    @Override
    protected void writeToDataPacket(CompoundTag tag, HolderLookup.Provider registries) {
        // Incremental block updates use this tag, not getUpdateTag.
        super.writeToDataPacket(tag, registries);
        writeExtra(tag);
    }

    @Override
    protected boolean readFromDataPacket(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag previousSource = sourceData;
        CompoundTag previousCamo = camoData;
        List<CamoContainer<?, ?>> previousParts = List.copyOf(partCamos);
        boolean previousInherit = inheritNbt;
        boolean changed = super.readFromDataPacket(tag, registries);
        readExtra(tag);
        return changed
                || previousInherit != inheritNbt
                || !Objects.equals(previousSource, sourceData)
                || !Objects.equals(previousCamo, camoData)
                || !previousParts.equals(partCamos);
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean dropCamo) {
        super.addAdditionalDrops(drops, dropCamo);
        if (!dropCamo) {
            return;
        }
        for (CamoContainer<?, ?> part : partCamos) {
            if (part.isEmpty()) {
                continue;
            }
            ItemStack stack = CamoContainerHelper.dropCamo(part);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        readExtra(tag);
        requestModelDataUpdate();
    }

    @Override
    protected void attachAdditionalModelData(ModelData.Builder builder) {
        if (READING_MODEL_DATA.get()) {
            return;
        }
        BlockState framedState = getBlockState();
        if (inheritNbt && framedState.getBlock() instanceof CopiedFramedBlock copied) {
            ModelData source = modelDataFor(copied.copiedType().toSourceState(framedState), sourceData, true);
            if (source != ModelData.EMPTY) {
                builder.with(SOURCE_MODEL_DATA, source);
            }
        }
        CamoContainer<?, ?> camo = getCamo();
        if (inheritNbt && !camo.isEmpty()) {
            BlockState appearance = camo.getContent().getAppearanceState();
            if (appearance != null) {
                ModelData camoModel = modelDataFor(appearance, camoData, false);
                if (camoModel != ModelData.EMPTY) {
                    builder.with(CAMO_MODEL_DATA, camoModel);
                }
            }
        }
        if (!partCamos.isEmpty()) {
            List<CamoContent<?>> contents = new ArrayList<>(partCamos.size());
            List<ModelData> models = new ArrayList<>(partCamos.size());
            for (int index = 0; index < partCamos.size(); index++) {
                CamoContainer<?, ?> part = partCamos.get(index);
                contents.add(part.getContent());
                ModelData partModel = ModelData.EMPTY;
                if (inheritNbt && !part.isEmpty()) {
                    BlockState appearance = part.getContent().getAppearanceState();
                    if (appearance != null) {
                        partModel = resolveModelData(appearance, partData.get(index));
                    }
                }
                models.add(partModel);
            }
            builder.with(PART_CONTENTS, List.copyOf(contents));
            builder.with(PART_MODEL_DATA, List.copyOf(models));
        }
    }

    CompoundTag copyExtra() {
        CompoundTag tag = new CompoundTag();
        writeExtra(tag);
        return tag;
    }

    void readCarried(CompoundTag tag) {
        readExtra(tag);
        setChanged();
    }

    private void writeExtra(CompoundTag tag) {
        if (sourceData != null) {
            tag.put(SOURCE_KEY, sourceData.copy());
        }
        if (camoData != null) {
            tag.put(CAMO_KEY, camoData.copy());
        }
        if (!inheritNbt) {
            tag.putBoolean(NBT_KEY, false);
        }
        if (lastVibrationFrequency != 0) {
            tag.putInt(FREQUENCY_KEY, lastVibrationFrequency);
        }
        if (!partCamos.isEmpty()) {
            ListTag parts = new ListTag();
            for (int index = 0; index < partCamos.size(); index++) {
                CompoundTag entry = new CompoundTag();
                Tag written = CamoContainerHelper.writeToDisk(partCamos.get(index));
                if (written instanceof CompoundTag compound) {
                    entry.put("camo", compound);
                }
                CompoundTag data = partData.get(index);
                if (data != null) {
                    entry.put("data", data.copy());
                }
                parts.add(entry);
            }
            tag.put(PARTS_KEY, parts);
        }
    }

    private void readExtra(CompoundTag tag) {
        sourceData = tag.contains(SOURCE_KEY, Tag.TAG_COMPOUND) ? copyOrNull(tag.getCompound(SOURCE_KEY)) : null;
        camoData = tag.contains(CAMO_KEY, Tag.TAG_COMPOUND) ? copyOrNull(tag.getCompound(CAMO_KEY)) : null;
        inheritNbt = !tag.contains(NBT_KEY) || tag.getBoolean(NBT_KEY);
        lastVibrationFrequency = tag.contains(FREQUENCY_KEY) ? tag.getInt(FREQUENCY_KEY) : 0;
        partCamos.clear();
        partData.clear();
        if (tag.contains(PARTS_KEY, Tag.TAG_LIST)) {
            ListTag parts = tag.getList(PARTS_KEY, Tag.TAG_COMPOUND);
            for (int index = 0; index < parts.size(); index++) {
                CompoundTag entry = parts.getCompound(index);
                CamoContainer<?, ?> camo = entry.contains("camo", Tag.TAG_COMPOUND)
                        ? CamoContainerHelper.readFromDisk(entry.getCompound("camo"))
                        : EmptyCamoContainer.EMPTY;
                partCamos.add(camo);
                partData.add(entry.contains("data", Tag.TAG_COMPOUND) ? copyOrNull(entry.getCompound("data")) : null);
            }
        }
        cachedSourceState = null;
        cachedCamoState = null;
        clearVisual();
    }

    private void clearVisual() {
        visual = null;
        visualState = null;
        visualTag = null;
        visualChecked = false;
    }

    private void trimEmptyParts() {
        while (!partCamos.isEmpty() && partCamos.get(partCamos.size() - 1).isEmpty() && partData.get(partData.size() - 1) == null) {
            partCamos.remove(partCamos.size() - 1);
            partData.remove(partData.size() - 1);
        }
    }

    private ModelData modelDataFor(BlockState state, @Nullable CompoundTag tag, boolean source) {
        BlockState cachedState = source ? cachedSourceState : cachedCamoState;
        CompoundTag cachedTag = source ? cachedSourceTag : cachedCamoTag;
        if (state.equals(cachedState) && tag == cachedTag) {
            return source ? cachedSourceModel : cachedCamoModel;
        }
        ModelData resolved = resolveModelData(state, tag);
        if (source) {
            cachedSourceState = state;
            cachedSourceTag = tag;
            cachedSourceModel = resolved;
        } else {
            cachedCamoState = state;
            cachedCamoTag = tag;
            cachedCamoModel = resolved;
        }
        return resolved;
    }

    private ModelData resolveModelData(BlockState state, @Nullable CompoundTag tag) {
        if (!(state.getBlock() instanceof EntityBlock) || state.getBlock() instanceof CopiedFramedBlock) {
            return ModelData.EMPTY;
        }
        Level level = getLevel();
        if (level == null) {
            return ModelData.EMPTY;
        }
        READING_MODEL_DATA.set(true);
        try {
            BlockEntity virtual = createLoaded(state, tag);
            if (virtual == null) {
                return ModelData.EMPTY;
            }
            ModelData data = virtual.getModelData();
            return data == null ? ModelData.EMPTY : data;
        } catch (RuntimeException ignored) {
            return ModelData.EMPTY;
        } finally {
            READING_MODEL_DATA.set(false);
        }
    }

    /**
     * Runs one tick of the copied block's block entity and stores the result.
     * Campfire cooking and furnace smelting both go through this path.
     */
    public void tickFromSource(Level level, BlockState sourceState, BlockEntityTicker<BlockEntity> ticker) {
        BlockEntity virtual = createLoaded(sourceState, sourceData);
        if (virtual == null) {
            return;
        }
        try {
            // copy() snapshots int arrays. The tag from save holds those arrays by reference, so a
            // later cook increment would make the before and after tags look unchanged.
            CompoundTag before = virtual.saveWithoutMetadata(level.registryAccess()).copy();
            ticker.tick(level, worldPosition, sourceState, virtual);
            CompoundTag after = virtual.saveWithoutMetadata(level.registryAccess()).copy();
            if (!after.equals(before)) {
                setSourceData(after);
                if (!level.isClientSide) {
                    BlockState state = getBlockState();
                    level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
                }
            }
        } catch (RuntimeException ignored) {
            // The source tick failed. The previous snapshot remains.
        }
    }

    /**
     * Places one cookable item on this campfire and keeps it on the framed block until it finishes.
     * A finished cook drops the result, the same as a campfire.
     */
    @Nullable
    ItemInteractionResult offerCampfire(ItemStack stack, Player player) {
        Level level = getLevel();
        if (level == null || !(getBlockState().getBlock() instanceof CopiedFramedBlock copied)) {
            return null;
        }
        BlockState sourceState = copied.copiedType().toSourceState(getBlockState());
        BlockEntity virtual = createLoaded(sourceState, sourceData);
        if (!(virtual instanceof CampfireBlockEntity campfire)) {
            return null;
        }
        Optional<RecipeHolder<CampfireCookingRecipe>> recipe = campfire.getCookableRecipe(stack);
        if (recipe.isEmpty()) {
            return null;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.CONSUME;
        }
        if (!campfire.placeFood(player, stack, recipe.get().value().getCookingTime())) {
            return ItemInteractionResult.CONSUME;
        }
        player.awardStat(Stats.INTERACT_WITH_CAMPFIRE);
        storeCampfire(campfire);
        return ItemInteractionResult.SUCCESS;
    }

    void tickCampfire(Level level, BlockState sourceState) {
        BlockEntity virtual = createLoaded(sourceState, sourceData);
        if (!(virtual instanceof CampfireBlockEntity campfire)) {
            return;
        }
        boolean lit = sourceState.hasProperty(CampfireBlock.LIT) && sourceState.getValue(CampfireBlock.LIT);
        try {
            CompoundTag before = campfire.saveCustomOnly(level.registryAccess()).copy();
            BlockState framed = getBlockState();
            if (lit) {
                CampfireBlockEntity.cookTick(level, worldPosition, framed, campfire);
            } else {
                CampfireBlockEntity.cooldownTick(level, worldPosition, framed, campfire);
            }
            CompoundTag after = campfire.saveCustomOnly(level.registryAccess()).copy();
            if (!after.equals(before)) {
                setSourceData(after);
                level.sendBlockUpdated(worldPosition, framed, framed, Block.UPDATE_CLIENTS);
            }
        } catch (RuntimeException ignored) {
            // The cook tick failed. The previous snapshot remains.
        }
    }

    void tickCampfireParticles(Level level, BlockState sourceState, CampfireBlock campfire) {
        BlockEntityTicker<CampfireBlockEntity> particles;
        try {
            particles = campfire.getTicker(level, sourceState, BlockEntityType.CAMPFIRE);
        } catch (RuntimeException ignored) {
            return;
        }
        if (particles == null) {
            return;
        }
        tickFromSource(level, sourceState, (tickLevel, pos, state, blockEntity) -> {
            if (blockEntity instanceof CampfireBlockEntity cooking) {
                particles.tick(tickLevel, pos, state, cooking);
            }
        });
    }

    private void storeCampfire(CampfireBlockEntity campfire) {
        Level level = getLevel();
        if (level == null) {
            return;
        }
        try {
            setSourceData(campfire.saveCustomOnly(level.registryAccess()));
            BlockState framed = getBlockState();
            level.sendBlockUpdated(worldPosition, framed, framed, Block.UPDATE_CLIENTS);
        } catch (RuntimeException ignored) {
            // The placed food could not be stored. The hand still consumed it only if placeFood did.
        }
    }

    GameEventListener sculkListener() {
        return sculkHearing().listener;
    }

    void tickSculk(Level level) {
        SculkHearing hearing = sculkHearing();
        VibrationSystem.Ticker.tick(level, hearing.data, hearing.user);
    }

    void receiveStep(ServerLevel level, Entity entity) {
        SculkHearing hearing = sculkHearing();
        Holder<GameEvent> step = GameEvent.STEP;
        if (!hearing.user.canReceiveVibration(level, worldPosition, step, GameEvent.Context.of(getBlockState()))) {
            return;
        }
        hearing.listener.forceScheduleVibration(level, step, GameEvent.Context.of(entity), entity.position());
    }

    int lastVibrationFrequency() {
        return lastVibrationFrequency;
    }

    private SculkHearing sculkHearing() {
        if (sculkHearing == null) {
            sculkHearing = new SculkHearing(this);
        }
        return sculkHearing;
    }

    public int sourceOutputSignal() {
        if (sourceData == null || !sourceData.contains("OutputSignal")) {
            return 0;
        }
        return sourceData.getInt("OutputSignal");
    }

    public void dropStoredContents(Level level, BlockPos pos) {
        if (!(getBlockState().getBlock() instanceof CopiedFramedBlock copied)) {
            return;
        }
        BlockEntity virtual = createLoaded(copied.copiedType().toSourceState(getBlockState()), sourceData);
        if (virtual instanceof Container container) {
            Containers.dropContents(level, pos, container);
            setSourceData(null);
        } else if (virtual instanceof CampfireBlockEntity campfire) {
            Containers.dropContents(level, pos, campfire.getItems());
            setSourceData(null);
        }
    }

    @Nullable
    private BlockEntity createLoaded(BlockState state, @Nullable CompoundTag tag) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock) || state.getBlock() instanceof CopiedFramedBlock) {
            return null;
        }
        Level level = getLevel();
        if (level == null) {
            return null;
        }
        BlockEntity virtual = entityBlock.newBlockEntity(getBlockPos(), state);
        if (virtual == null || virtual instanceof FramedBlockEntity) {
            return null;
        }
        virtual.setLevel(level);
        if (tag != null) {
            virtual.loadWithComponents(tag, level.registryAccess());
        }
        return virtual;
    }

    @Nullable
    private static CompoundTag copyOrNull(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        return tag.copy();
    }

    /**
     * Hears vibrations for a framed sculk sensor. The listener stays on this block entity,
     * and activation writes phase and power onto the framed state.
     */
    private static final class SculkHearing implements VibrationSystem {
        private static final ThreadLocal<Boolean> RECEIVING = ThreadLocal.withInitial(() -> false);

        private final CopiedFramedBlockEntity entity;
        private final Data data = new Data();
        private final User user;
        private final Listener listener;

        private SculkHearing(CopiedFramedBlockEntity entity) {
            this.entity = entity;
            this.user = new FramedUser();
            this.listener = new Listener(this);
        }

        @Override
        public Data getVibrationData() {
            return data;
        }

        @Override
        public User getVibrationUser() {
            return user;
        }

        private final class FramedUser implements User {
            private final PositionSource positionSource = new BlockPositionSource(entity.getBlockPos());

            @Override
            public int getListenerRadius() {
                return sourceBlock() instanceof CalibratedSculkSensorBlock ? 16 : 8;
            }

            @Override
            public PositionSource getPositionSource() {
                return positionSource;
            }

            @Override
            public boolean canTriggerAvoidVibration() {
                return true;
            }

            @Override
            public boolean requiresAdjacentChunksToBeTicking() {
                return true;
            }

            @Override
            public boolean canReceiveVibration(ServerLevel level, BlockPos pos, Holder<GameEvent> event, GameEvent.Context context) {
                BlockState state = entity.getBlockState();
                if (sourceBlock() instanceof CalibratedSculkSensorBlock && state.hasProperty(CalibratedSculkSensorBlock.FACING)) {
                    Direction facing = state.getValue(CalibratedSculkSensorBlock.FACING).getOpposite();
                    int back = level.getSignal(entity.getBlockPos().relative(facing), facing);
                    if (back != 0 && VibrationSystem.getGameEventFrequency(event) != back) {
                        return false;
                    }
                }
                if (RECEIVING.get()) {
                    return false;
                }
                if (pos.equals(entity.getBlockPos())
                        && (event.is(GameEvent.BLOCK_DESTROY)
                        || event.is(GameEvent.BLOCK_PLACE)
                        || event.is(GameEvent.SCULK_SENSOR_TENDRILS_CLICKING))) {
                    return false;
                }
                return state.hasProperty(SculkSensorBlock.PHASE) && SculkSensorBlock.canActivate(state);
            }

            @Override
            public void onReceiveVibration(ServerLevel level, BlockPos pos, Holder<GameEvent> event, @Nullable Entity source, @Nullable Entity projectile, float distance) {
                // Neighbor updates from activation post more game events on this same thread.
                if (RECEIVING.get()) {
                    return;
                }
                BlockState state = entity.getBlockState();
                if (!state.hasProperty(SculkSensorBlock.PHASE) || !SculkSensorBlock.canActivate(state)) {
                    return;
                }
                if (!(state.getBlock() instanceof CopiedFramedBlock copied) || !(copied.source() instanceof SculkSensorBlock sensor)) {
                    return;
                }
                int frequency = VibrationSystem.getGameEventFrequency(event);
                entity.lastVibrationFrequency = frequency;
                // setChanged() would update comparator neighbors before the phase changes.
                entity.setChangedWithoutSignalUpdate();
                int power = VibrationSystem.getRedstoneStrengthForDistance(distance, getListenerRadius());
                RECEIVING.set(true);
                try {
                    activate(sensor, source, level, state, power, frequency);
                } finally {
                    RECEIVING.set(false);
                }
            }

            private void activate(SculkSensorBlock sensor, @Nullable Entity source, ServerLevel level, BlockState state, int power, int frequency) {
                BlockPos pos = entity.getBlockPos();
                BlockState active = state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.ACTIVE);
                if (active.hasProperty(SculkSensorBlock.POWER)) {
                    active = active.setValue(SculkSensorBlock.POWER, power);
                }
                // One block update. SculkSensorBlock.activate also calls updateNeighbours, which
                // notifies this sensor again through the block below it, and that re-entry froze the game.
                level.setBlock(pos, active, Block.UPDATE_ALL);
                level.scheduleTick(pos, state.getBlock(), sensor.getActiveTicks());
                SculkSensorBlock.tryResonateVibration(source, level, pos, frequency);
                level.gameEvent(source, GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, pos);
                if (!state.hasProperty(SculkSensorBlock.WATERLOGGED) || !state.getValue(SculkSensorBlock.WATERLOGGED)) {
                    level.playSound(
                            null,
                            pos,
                            SoundEvents.SCULK_CLICKING,
                            SoundSource.BLOCKS,
                            1.0F,
                            level.random.nextFloat() * 0.2F + 0.8F
                    );
                }
            }

            @Override
            public void onDataChanged() {
                // Called every tick a vibration is traveling. setChanged also updates comparator
                // neighbors, and that work repeats for every framed block around the sensor.
            }

            @Nullable
            private Block sourceBlock() {
                if (entity.getBlockState().getBlock() instanceof CopiedFramedBlock copied) {
                    return copied.source();
                }
                return null;
            }
        }
    }
}
