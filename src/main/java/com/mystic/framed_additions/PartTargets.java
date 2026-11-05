package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The texture part a player just clicked. The client sends this before the use packet,
 * and the server reads it while handling that click.
 */
public final class PartTargets {
    private static final ConcurrentHashMap<UUID, Target> PENDING = new ConcurrentHashMap<>();

    private PartTargets() {
    }

    public static void offer(Player player, BlockPos pos, int part) {
        if (part < 0) {
            part = 0;
        }
        if (part > 16) {
            part = 16;
        }
        PENDING.put(player.getUUID(), new Target(pos.immutable(), part, System.currentTimeMillis()));
    }

    public static int take(Player player, BlockPos pos) {
        Target target = PENDING.remove(player.getUUID());
        if (target == null || !target.pos.equals(pos) || System.currentTimeMillis() - target.time > 2000L) {
            return 0;
        }
        return target.part;
    }

    private record Target(BlockPos pos, int part, long time) {
    }
}
