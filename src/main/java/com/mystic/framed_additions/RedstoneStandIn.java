package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import xfacthd.framedblocks.api.block.FramedProperties;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs a copied block's own redstone logic without leaving that block in the world.
 * Vanilla dust and repeaters ask the level for the real block, so the connected framed
 * copies are swapped for the source block, the vanilla method runs, and the resulting
 * properties and block entity are copied back. Scheduled ticks are moved onto the framed block.
 */
final class RedstoneStandIn {
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int MAX_BLOCKS = 512;
    private static final ThreadLocal<Set<Long>> ACTIVE = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Map<ServerPlayer, Snapshot> OPEN_SCREENS = new HashMap<>();

    private RedstoneStandIn() {
    }

    static boolean isSwapping() {
        return DEPTH.get() > 0;
    }

    static boolean participates(Block block) {
        if (block instanceof RedStoneWireBlock
                || block instanceof DiodeBlock
                || block instanceof RedstoneLampBlock
                || block instanceof DispenserBlock
                || block instanceof PistonBaseBlock
                || block instanceof HopperBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof ButtonBlock
                || block instanceof TargetBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof BaseRailBlock
                || block instanceof TntBlock
                || block instanceof NoteBlock
                || block instanceof TripWireBlock
                || block instanceof TripWireHookBlock) {
            return true;
        }
        try {
            return block.defaultBlockState().isSignalSource();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean reactsToEntities(Block block) {
        return participates(block) || block instanceof CampfireBlock;
    }

    static boolean reactsToProjectiles(Block block) {
        return block instanceof CampfireBlock || block instanceof TargetBlock || block instanceof ButtonBlock;
    }

    static boolean opensWithItem(Block block) {
        return participates(block) || block instanceof EntityBlock || block instanceof CampfireBlock;
    }

    /**
     * Swaps in the copied block so its own screen can open, and leaves it there until that screen closes.
     * A wrapped menu fails the real block's checks, so furnaces and the other containers never stay open.
     */
    static boolean openHeldScreen(Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer server)) {
            return false;
        }
        releaseScreen(server);
        Snapshot snapshot = Snapshot.capture(level, pos);
        if (snapshot == null) {
            return false;
        }
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        boolean opened = false;
        try {
            snapshot.materialize(level);
            try {
                level.getBlockState(pos).useWithoutItem(level, player, hit);
            } catch (RuntimeException ignored) {
                // The source block did not open a screen.
            }
            opened = server.containerMenu != server.inventoryMenu;
            if (opened) {
                OPEN_SCREENS.put(server, snapshot);
            }
        } finally {
            DEPTH.set(depth);
        }
        if (!opened) {
            restoreHeld(level, snapshot);
        }
        return opened;
    }

    static void releaseScreen(ServerPlayer player) {
        Snapshot snapshot = OPEN_SCREENS.remove(player);
        if (snapshot == null) {
            return;
        }
        restoreHeld(player.level(), snapshot);
    }

    private static void restoreHeld(Level level, Snapshot snapshot) {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        try {
            snapshot.restore(level);
        } finally {
            DEPTH.set(depth);
        }
    }

    static void runSingle(Level level, BlockPos pos, Consumer<BlockState> action) {
        execute(level, pos, false, action);
    }

    static void runConnected(Level level, BlockPos pos, Consumer<BlockState> action) {
        execute(level, pos, true, action);
    }

    private static void execute(Level level, BlockPos pos, boolean connected, Consumer<BlockState> action) {
        long key = pos.asLong();
        Set<Long> active = ACTIVE.get();
        if (active.contains(key)) {
            try {
                action.accept(level.getBlockState(pos));
            } catch (RuntimeException ignored) {
                // The source block rejected this update. The framed block stays as it was.
            }
            return;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof CopiedFramedBlock)) {
            return;
        }
        List<BlockPos> area = connected ? collect(level, pos) : List.of(pos.immutable());
        if (area.isEmpty()) {
            area = List.of(pos.immutable());
        }
        Map<BlockPos, Snapshot> snapshots = new LinkedHashMap<>();
        for (BlockPos at : area) {
            active.add(at.asLong());
        }
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        try {
            for (BlockPos at : area) {
                Snapshot snapshot = Snapshot.capture(level, at);
                if (snapshot != null) {
                    snapshots.put(at.immutable(), snapshot);
                }
            }
            for (Snapshot snapshot : snapshots.values()) {
                snapshot.materialize(level);
            }
            try {
                action.accept(level.getBlockState(pos));
            } catch (RuntimeException ignored) {
                // The source block rejected this update. Restore the framed blocks below.
            }
            for (Snapshot snapshot : snapshots.values()) {
                snapshot.restore(level);
            }
        } finally {
            DEPTH.set(depth);
            for (BlockPos at : area) {
                active.remove(at.asLong());
            }
        }
    }

    private static List<BlockPos> collect(Level level, BlockPos origin) {
        List<BlockPos> area = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(origin.immutable());
        while (!queue.isEmpty() && area.size() < MAX_BLOCKS) {
            BlockPos at = queue.remove();
            if (!seen.add(at.asLong()) || !level.hasChunkAt(at)) {
                continue;
            }
            if (!(level.getBlockState(at).getBlock() instanceof CopiedFramedBlock copied) || !participates(copied.source())) {
                continue;
            }
            area.add(at.immutable());
            for (Direction direction : Direction.values()) {
                queue.add(at.relative(direction));
            }
            if (copied.source() instanceof RedStoneWireBlock) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    queue.add(at.relative(direction).above());
                    queue.add(at.relative(direction).below());
                }
            }
        }
        return area;
    }

    static BlockState carryFramedFlags(BlockState from, BlockState to) {
        to = carryFlag(from, to, FramedProperties.GLOWING);
        to = carryFlag(from, to, FramedProperties.PROPAGATES_SKYLIGHT);
        return carryFlag(from, to, FramedProperties.SOLID);
    }

    private static BlockState carryFlag(BlockState from, BlockState to, BooleanProperty property) {
        if (from.hasProperty(property) && to.hasProperty(property)) {
            return to.setValue(property, from.getValue(property));
        }
        return to;
    }

    private static void retargetTicks(Level level, BlockPos pos, Block from, Block to) {
        if (level.isClientSide || from == to || !(level.getChunkAt(pos) instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getBlockTicks() instanceof LevelChunkTicks<Block> ticks)) {
            return;
        }
        boolean imminent = level.getBlockTicks().willTickThisTick(pos, from);
        List<ScheduledTick<Block>> moved = new ArrayList<>();
        ticks.removeIf(tick -> {
            if (tick.pos().equals(pos) && tick.type() == from) {
                moved.add(tick);
                return true;
            }
            return false;
        });
        for (ScheduledTick<Block> tick : moved) {
            ticks.schedule(new ScheduledTick<>(to, tick.pos(), tick.triggerTick(), tick.priority(), tick.subTickOrder()));
        }
        if (moved.isEmpty() && imminent) {
            level.scheduleTick(pos, to, 1);
        }
    }

    private static final class Snapshot {
        private final BlockPos pos;
        private final CopiedFramedBlock framed;
        private final BlockState framedState;
        private final CompoundTag framedTag;

        private Snapshot(BlockPos pos, CopiedFramedBlock framed, BlockState framedState, CompoundTag framedTag) {
            this.pos = pos;
            this.framed = framed;
            this.framedState = framedState;
            this.framedTag = framedTag;
        }

        private static Snapshot capture(Level level, BlockPos pos) {
            if (!(level.getBlockState(pos).getBlock() instanceof CopiedFramedBlock framed)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            CompoundTag tag = null;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) {
                try {
                    tag = entity.saveWithoutMetadata(level.registryAccess());
                } catch (RuntimeException ignored) {
                    tag = null;
                }
            }
            return new Snapshot(pos.immutable(), framed, state, tag);
        }

        private void materialize(Level level) {
            BlockState sourceState = framed.copiedType().toSourceState(framedState);
            level.setBlock(pos, sourceState, QUIET);
            if (!(framed.source() instanceof EntityBlock) || framedTag == null || !framedTag.contains(CopiedFramedBlockEntity.SOURCE_KEY)) {
                return;
            }
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity == null || entity instanceof FramedBlockEntity) {
                return;
            }
            try {
                entity.loadWithComponents(framedTag.getCompound(CopiedFramedBlockEntity.SOURCE_KEY), level.registryAccess());
            } catch (RuntimeException ignored) {
                // The source block still runs. It starts from an empty block entity.
            }
        }

        private void restore(Level level) {
            BlockState after = level.getBlockState(pos);
            CopiedFramedBlock target = framed;
            BlockState base = framedState;
            Block carriedFrom = framed.source();
            if (!after.is(framed.source())) {
                // An anvil can become chipped or damaged while its screen is open.
                CopiedFramedBlock replacement = FramedRegistry.copyOf(after.getBlock());
                if (replacement == null) {
                    return;
                }
                target = replacement;
                base = carryFramedFlags(framedState, replacement.defaultBlockState());
                carriedFrom = after.getBlock();
            }
            CompoundTag updated = null;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null && !(entity instanceof FramedBlockEntity)) {
                try {
                    updated = entity.saveWithoutMetadata(level.registryAccess());
                } catch (RuntimeException ignored) {
                    updated = null;
                }
                entity.setRemoved();
                if (level.getChunkAt(pos) instanceof LevelChunk chunk) {
                    chunk.removeBlockEntity(pos);
                }
            }
            retargetTicks(level, pos, carriedFrom, target);
            BlockState restored = target.copiedType().applySourceState(base, after);
            level.setBlock(pos, restored, QUIET);
            if (!(level.getBlockEntity(pos) instanceof CopiedFramedBlockEntity framedEntity)) {
                return;
            }
            if (framedTag != null) {
                try {
                    framedEntity.loadWithComponents(framedTag, level.registryAccess());
                } catch (RuntimeException ignored) {
                    // Camouflage from before the update could not be reloaded.
                }
            }
            if (target.source() instanceof EntityBlock) {
                framedEntity.setSourceData(updated);
            }
        }
    }
}
