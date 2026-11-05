package com.mystic.framed_additions.client;

import com.mystic.framed_additions.CopiedFramedBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.util.TriState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class LazyCopiedModel extends BakedModelWrapper<BakedModel> {
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final CopiedFramedBlock block;
    private final TextureAtlasSprite particle;
    private final ItemTransforms transforms;
    private final boolean gui3d;
    private final boolean ambientOcclusion;
    private final boolean blockLight;
    private volatile CopiedFramedModel resolved;

    public LazyCopiedModel(CopiedFramedBlock block, TextureAtlasSprite particle, ItemTransforms transforms, boolean gui3d, boolean ambientOcclusion, boolean blockLight) {
        super(EmptyModel.BAKED);
        this.block = block;
        this.particle = particle;
        this.transforms = transforms;
        this.gui3d = gui3d;
        this.ambientOcclusion = ambientOcclusion;
        this.blockLight = blockLight;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        CopiedFramedModel model = model();
        return model == null ? List.of() : model.getQuads(state, side, rand);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        CopiedFramedModel model = model();
        return model == null ? List.of() : model.getQuads(state, side, rand, data, renderType);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        CopiedFramedModel model = model();
        return model == null ? super.getRenderTypes(state, rand, data) : model.getRenderTypes(state, rand, data);
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        CopiedFramedModel model = model();
        return model == null ? super.getRenderTypes(stack, fabulous) : model.getRenderTypes(stack, fabulous);
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        // BakedModelWrapper forwards this to EmptyModel, which has no faces.
        CopiedFramedModel model = model();
        return List.of(model == null ? this : model);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return ambientOcclusion;
    }

    @Override
    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        CopiedFramedModel model = model();
        return model == null ? super.useAmbientOcclusion(state, ModelData.EMPTY, null) : model.useAmbientOcclusion(state, data, renderType);
    }

    @Override
    public boolean isGui3d() {
        return gui3d;
    }

    @Override
    public boolean usesBlockLight() {
        return blockLight;
    }

    @Override
    public boolean isCustomRenderer() {
        CopiedFramedModel model = model();
        return model == null ? super.isCustomRenderer() : model.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        // Called while the block-state model is assembled, before the atlas is uploaded.
        return particle;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        CopiedFramedModel model = model();
        return model == null ? super.getParticleIcon(data) : model.getParticleIcon(data);
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        return transforms;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        CopiedFramedModel model = model();
        if (model == null) {
            return super.applyTransform(context, poseStack, applyLeftHandTransform);
        }
        return model.applyTransform(context, poseStack, applyLeftHandTransform);
    }

    @Nullable
    private CopiedFramedModel model() {
        CopiedFramedModel current = resolved;
        if (current != null || RESOLVING.get()) {
            return current;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            current = create();
        } finally {
            RESOLVING.set(Boolean.FALSE);
        }
        resolved = current;
        return current;
    }

    private CopiedFramedModel create() {
        return new CopiedFramedModel(block, particle);
    }
}
