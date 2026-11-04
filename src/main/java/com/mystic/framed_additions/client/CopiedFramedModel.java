package com.mystic.framed_additions.client;

import com.mystic.framed_additions.CopiedFramedBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.model.BakedModelProxy;
import xfacthd.framedblocks.api.model.data.FramedBlockData;
import xfacthd.framedblocks.api.model.util.ModelCache;
import xfacthd.framedblocks.api.model.util.ModelUtils;

import java.util.ArrayList;
import java.util.List;

public class CopiedFramedModel extends BakedModelProxy {
    private static final ResourceLocation FRAME_TEXTURE = new ResourceLocation("framedblocks", "block/framed_block");
    private static final RandomSource STABLE_RANDOM = RandomSource.create(0L);

    private final CopiedFramedBlock block;

    public CopiedFramedModel(CopiedFramedBlock block) {
        super(Minecraft.getInstance().getModelManager().getMissingModel());
        this.block = block;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        FramedBlockData framedData = data.get(FramedBlockData.PROPERTY);
        if (side != null && framedData != null && framedData.isSideHidden(side)) {
            return List.of();
        }

        BlockState framedState = state == null ? block.defaultBlockState() : state;
        BlockState sourceState = block.copiedType().toSourceState(framedState);
        BlockState camo = framedData == null ? null : framedData.getCamoState();
        boolean bare = camo == null || camo.isAir();
        ChunkRenderTypeSet camoLayers = bare ? ModelUtils.CUTOUT : ModelCache.getCamoRenderTypes(camo, rand, data);
        RenderType layer = renderType == null ? (bare ? RenderType.cutout() : primaryLayer(camo, data)) : renderType;
        if (renderType != null && !camoLayers.contains(renderType)) {
            return List.of();
        }

        BakedModel variant = sourceModel(sourceState);
        if (variant == null) {
            return List.of();
        }
        // The random source selects weighted model variants. The same seed is used for every face.
        List<BakedQuad> geometry = variant.getQuads(sourceState, side, rand);
        if (geometry.isEmpty()) {
            return List.of();
        }

        TextureAtlasSprite sprite = bare ? frameSprite() : null;
        BakedModel camoModel = bare ? null : Minecraft.getInstance().getBlockRenderer().getBlockModel(camo);
        List<BakedQuad> quads = new ArrayList<>(geometry.size());
        int[] faceQuadIndices = new int[Direction.values().length + 1];
        for (BakedQuad quad : geometry) {
            if (quad.getDirection() != null && framedData != null && framedData.isSideHidden(quad.getDirection())) {
                continue;
            }
            if (bare) {
                quads.add(retexture(quad, sprite, -1));
            } else {
                int faceIndex = quad.getDirection() == null
                        ? Direction.values().length
                        : quad.getDirection().ordinal();
                BakedQuad camoQuad = camoModel == null ? null
                        : sampleQuad(camoModel, camo, quad.getDirection(), layer, faceQuadIndices[faceIndex], ModelUtils.getCamoModelData(data));
                faceQuadIndices[faceIndex]++;
                TextureAtlasSprite camoSprite = camoQuad == null
                        ? (camoModel == null ? frameSprite() : camoModel.getParticleIcon())
                        : camoQuad.getSprite();
                int tintIndex = camoQuad == null ? -1 : camoQuad.getTintIndex();
                quads.add(retexture(quad, camoSprite, tintIndex));
            }
        }
        return quads;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        FramedBlockData framedData = data.get(FramedBlockData.PROPERTY);
        BlockState camo = framedData == null ? null : framedData.getCamoState();
        if (camo == null || camo.isAir()) {
            return ModelUtils.CUTOUT;
        }
        return ModelCache.getCamoRenderTypes(camo, rand, data);
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        return List.of(RenderType.cutout());
    }

    @Override
    public boolean isGui3d() {
        BakedModel model = sourceModel(block.copiedType().toSourceState(block.defaultBlockState()));
        return model == null || model.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        BakedModel model = sourceModel(block.copiedType().toSourceState(block.defaultBlockState()));
        return model == null || model.usesBlockLight();
    }

    @Override
    public boolean useAmbientOcclusion() {
        BakedModel model = sourceModel(block.copiedType().toSourceState(block.defaultBlockState()));
        return model == null || model.useAmbientOcclusion();
    }

    @Override
    public boolean useAmbientOcclusion(BlockState state) {
        return sourceAmbientOcclusion(block.copiedType().toSourceState(state));
    }

    @Override
    public boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
        // The render type here belongs to the applied camouflage, not the copied
        // source model. Passing it through can make AO change between render passes.
        return sourceAmbientOcclusion(block.copiedType().toSourceState(state));
    }

    private boolean sourceAmbientOcclusion(BlockState sourceState) {
        BakedModel model = sourceModel(sourceState);
        return model == null || model.useAmbientOcclusion(sourceState);
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        BakedModel model = sourceModel(block.copiedType().toSourceState(block.defaultBlockState()));
        return model == null ? ItemTransforms.NO_TRANSFORMS : model.getTransforms();
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
        BakedModel model = sourceModel(block.copiedType().toSourceState(block.defaultBlockState()));
        if (model != null) {
            model.applyTransform(context, poseStack, applyLeftHandTransform);
        }
        return this;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return frameSprite();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        FramedBlockData framedData = data.get(FramedBlockData.PROPERTY);
        BlockState camo = framedData == null ? null : framedData.getCamoState();
        if (camo != null && !camo.isAir()) {
            return Minecraft.getInstance().getBlockRenderer().getBlockModel(camo).getParticleIcon();
        }
        return frameSprite();
    }

    @Nullable
    private BakedModel sourceModel(BlockState sourceState) {
        try {
            BakedModel model = ModelCache.getModel(sourceState);
            if (model != null && model != Minecraft.getInstance().getModelManager().getMissingModel()) {
                return model;
            }
        } catch (RuntimeException ignored) {
            // Model lookup can run before the bakery is ready; retry on the next render call.
        }
        return null;
    }

    private static TextureAtlasSprite frameSprite() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(FRAME_TEXTURE);
    }

    private static RenderType primaryLayer(BlockState camo, ModelData data) {
        STABLE_RANDOM.setSeed(0L);
        ChunkRenderTypeSet layers = ModelCache.getCamoRenderTypes(camo, STABLE_RANDOM, data);
        if (layers.contains(RenderType.translucent()) && !layers.contains(RenderType.solid())) {
            return RenderType.translucent();
        }
        if (layers.contains(RenderType.solid())) {
            return RenderType.solid();
        }
        if (layers.contains(RenderType.cutoutMipped())) {
            return RenderType.cutoutMipped();
        }
        if (layers.contains(RenderType.cutout())) {
            return RenderType.cutout();
        }
        if (layers.isEmpty()) {
            return RenderType.solid();
        }
        return layers.iterator().next();
    }

    @Nullable
    private static BakedQuad sampleQuad(BakedModel model, BlockState state, @Nullable Direction side, RenderType layer, int faceIndex, ModelData modelData) {
        STABLE_RANDOM.setSeed(0L);
        List<BakedQuad> quads = model.getQuads(state, side, STABLE_RANDOM, modelData, layer);
        if (quads.isEmpty()) {
            quads = model.getQuads(state, side, STABLE_RANDOM);
        }
        if (!quads.isEmpty()) {
            return quads.get(Math.min(faceIndex, quads.size() - 1));
        }
        if (quads.isEmpty() && side != null) {
            quads = model.getQuads(state, null, STABLE_RANDOM, modelData, layer);
            if (quads.isEmpty()) {
                quads = model.getQuads(state, null, STABLE_RANDOM);
            }
            int matchingIndex = 0;
            for (BakedQuad quad : quads) {
                if (quad.getDirection() == side) {
                    if (matchingIndex++ == faceIndex) {
                        return quad;
                    }
                }
            }
        }
        return null;
    }

    private static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite sprite, int tintIndex) {
        TextureAtlasSprite old = quad.getSprite();
        if (old == sprite && quad.getTintIndex() == tintIndex) {
            return quad;
        }

        int[] vertices = quad.getVertices().clone();
        if (old != sprite) {
            int stride = vertices.length / 4;
            for (int vertex = 0; vertex < 4; vertex++) {
                int index = vertex * stride;
                float u = Float.intBitsToFloat(vertices[index + 4]);
                float v = Float.intBitsToFloat(vertices[index + 5]);
                vertices[index + 4] = Float.floatToRawIntBits(sprite.getU(old.getUOffset(u)));
                vertices[index + 5] = Float.floatToRawIntBits(sprite.getV(old.getVOffset(v)));
            }
        }
        return new BakedQuad(vertices, tintIndex, quad.getDirection(), sprite, quad.isShade());
    }
}
