package com.mystic.framed_additions;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PartTargetPayload(BlockPos pos, int part) implements CustomPacketPayload {
    public static final Type<PartTargetPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FramedAdditions.MODID, "part_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PartTargetPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PartTargetPayload::pos,
            ByteBufCodecs.VAR_INT, PartTargetPayload::part,
            PartTargetPayload::new
    );

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, PartTargetPayload::handle);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PartTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PartTargets.offer(context.player(), payload.pos(), payload.part()));
    }
}
