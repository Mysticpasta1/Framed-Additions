package com.mystic.framed_additions.client;

import com.mystic.framed_additions.CopiedFramedBlock;
import com.mystic.framed_additions.CopiedFramedBlockEntity;
import com.mystic.framed_additions.FramedAdditionsConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.util.TriState;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.camo.CamoClientHandler;
import xfacthd.framedblocks.api.camo.CamoContent;
import xfacthd.framedblocks.api.model.data.FramedBlockData;
import xfacthd.framedblocks.api.model.util.ModelUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class CopiedFramedModel extends BakedModelWrapper<BakedModel> {
    private static final ResourceLocation FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath("framedblocks", "block/framed_block");
    private static final ThreadLocal<RandomSource> RANDOM = ThreadLocal.withInitial(() -> RandomSource.create(0L));
    private static final Map<TextureAtlasSprite, Boolean> MOSTLY_TRANSPARENT = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<LadderKey, List<BakedQuad>> LADDER_QUADS = new ConcurrentHashMap<>();
    private final CopiedFramedBlock block;
    private final TextureAtlasSprite particle;
    private final Map<BlockState, List<ResourceLocation>> spriteParts = new ConcurrentHashMap<>();

    public CopiedFramedModel(CopiedFramedBlock block, TextureAtlasSprite particle) {
        super(EmptyModel.BAKED);
        this.block = block;
        this.particle = particle;
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
        BakedModel variant = sourceModel(sourceState);
        if (variant == null) {
            return List.of();
        }
        ModelData sourceData = sourceModelData(data);
        // A null render type asks the source for every face, including parts stored on another chunk layer.
        // Source model data carries block entity state such as a decorated pot's pattern.
        List<BakedQuad> geometry = variant.getQuads(sourceState, side, rand, sourceData, null);
        if (geometry.isEmpty()) {
            return List.of();
        }

        List<ResourceLocation> sprites = partSprites(sourceState, variant, sourceData);
        int partCount = visibleCount(sprites);
        boolean anyAssigned = false;
        boolean anyOpen = partCount == 0;
        boolean[] assigned = new boolean[partCount];
        for (int index = 0; index < partCount; index++) {
            CamoContent<?> part = camoAt(data, index);
            boolean filled = part != null && !part.isEmpty();
            assigned[index] = filled;
            if (filled) {
                anyAssigned = true;
            } else {
                anyOpen = true;
            }
        }
        ChunkRenderTypeSet layers = partLayers(data, rand, assigned, anyAssigned, anyOpen);
        if (renderType != null && !layers.contains(renderType)) {
            return List.of();
        }

        Map<SampleKey, List<Sample>> samples = new HashMap<>();
        List<BakedQuad> quads = new ArrayList<>(geometry.size());
        boolean ladder = block.source() instanceof LadderBlock;
        for (BakedQuad quad : geometry) {
            if (quad.getDirection() != null && framedData != null && framedData.isSideHidden(quad.getDirection())) {
                continue;
            }
            TextureAtlasSprite sourceSprite = quad.getSprite();
            ResourceLocation spriteName = sourceSprite == null ? null : sourceSprite.contents().name();
            if (spriteName != null && FramedAdditionsConfig.excludesTexture(spriteName)) {
                continue;
            }
            int index = spriteName == null ? 0 : indexOf(sprites, spriteName);
            if (index < 0) {
                index = 0;
            }
            boolean filled = anyAssigned && index < assigned.length && assigned[index];
            if (sourceSprite != null && sawSprite(spriteName)) {
                // The blade keeps the stonecutter saw texture, with or without camouflage on the block.
                if (renderType != null && anyAssigned && !ModelUtils.CUTOUT.contains(renderType)) {
                    continue;
                }
                quads.add(quad);
                continue;
            }
            if (sourceSprite != null && brewingSprite(spriteName)) {
                if (brewingCard(quad, spriteName)) {
                    continue;
                }
                if (!filled) {
                    if (anyAssigned && renderType != null && !ModelUtils.CUTOUT.contains(renderType)) {
                        continue;
                    }
                    quads.add(retextureSpatial(quad, frameSprite(), -1));
                    continue;
                }
                CamoContent<?> brewingCamo = camoAt(data, index);
                ModelData brewingData = modelDataAt(data, index);
                ChunkRenderTypeSet brewingLayers = renderTypes(brewingCamo, rand, brewingData);
                if (renderType != null && !brewingLayers.contains(renderType)) {
                    continue;
                }
                RenderType brewingLayer = renderType == null ? primaryLayer(brewingCamo, brewingData) : renderType;
                SampleKey brewingKey = new SampleKey(brewingCamo, quad.getDirection(), brewingLayer);
                List<Sample> brewingSamples = samples.get(brewingKey);
                if (brewingSamples == null) {
                    brewingSamples = sampleLayers(brewingCamo, quad.getDirection(), brewingLayer, brewingData);
                    samples.put(brewingKey, brewingSamples);
                }
                for (Sample sample : brewingSamples) {
                    TextureAtlasSprite camoSprite = sample.sprite == null ? frameSprite() : sample.sprite;
                    quads.add(retextureSpatial(quad, camoSprite, encodeTint(index, sample.tint)));
                }
                continue;
            }
            if (ladder && sourceSprite != null) {
                if (!filled) {
                    if (anyAssigned && renderType != null && !ModelUtils.CUTOUT.contains(renderType)) {
                        continue;
                    }
                    // The ladder texture is the shape. Replacing it with the frame sprite fills the gaps.
                    quads.add(quad);
                    continue;
                }
                CamoContent<?> ladderCamo = camoAt(data, index);
                ModelData ladderData = modelDataAt(data, index);
                ChunkRenderTypeSet ladderLayers = renderTypes(ladderCamo, rand, ladderData);
                if (renderType != null && !ladderLayers.contains(renderType)) {
                    continue;
                }
                // The rungs are the camouflage. The frame texture fills the holes on this same pass,
                // including when the camouflage is drawn as a solid layer.
                quads.add(retexture(quad, frameSprite(), -1));
                RenderType ladderLayer = renderType == null ? primaryLayer(ladderCamo, ladderData) : renderType;
                SampleKey ladderKey = new SampleKey(ladderCamo, quad.getDirection(), ladderLayer);
                List<Sample> ladderSamples = samples.get(ladderKey);
                if (ladderSamples == null) {
                    ladderSamples = sampleLayers(ladderCamo, quad.getDirection(), ladderLayer, ladderData);
                    samples.put(ladderKey, ladderSamples);
                }
                for (Sample sample : ladderSamples) {
                    TextureAtlasSprite camoSprite = sample.sprite == null ? frameSprite() : sample.sprite;
                    quads.addAll(shiftOut(ladderCamo(quad, sourceSprite, camoSprite, encodeTint(index, sample.tint)), quad.getDirection(), 0.002F));
                }
                continue;
            }
            if (!filled) {
                if (anyAssigned && renderType != null && !ModelUtils.CUTOUT.contains(renderType)) {
                    continue;
                }
                // Bare blocks use the frame. Once another part has camouflage, an open part keeps the source texture.
                TextureAtlasSprite sprite = anyAssigned && sourceSprite != null ? sourceSprite : frameSprite();
                int tint = anyAssigned ? encodeTint(index, quad.getTintIndex()) : -1;
                quads.add(retexture(quad, sprite, tint));
                continue;
            }
            CamoContent<?> part = camoAt(data, index);
            ModelData partData = modelDataAt(data, index);
            ChunkRenderTypeSet partLayer = renderTypes(part, rand, partData);
            if (renderType != null && !partLayer.contains(renderType)) {
                continue;
            }
            RenderType sampleLayer = renderType == null ? primaryLayer(part, partData) : renderType;
            SampleKey key = new SampleKey(part, quad.getDirection(), sampleLayer);
            List<Sample> partLayers = samples.get(key);
            if (partLayers == null) {
                partLayers = sampleLayers(part, quad.getDirection(), sampleLayer, partData);
                samples.put(key, partLayers);
            }
            for (Sample sample : partLayers) {
                TextureAtlasSprite camoSprite = sample.sprite == null ? frameSprite() : sample.sprite;
                quads.add(retexture(quad, camoSprite, encodeTint(index, sample.tint)));
            }
        }
        return quads;
    }

    /**
     * The texture part under a click. Part 0 is the FramedBlocks camouflage slot.
     * Each later part is another source sprite, in sorted order.
     */
    public int partAt(BlockState framedState, BlockHitResult hit) {
        BlockState sourceState = block.copiedType().toSourceState(framedState);
        BakedModel variant = sourceModel(sourceState);
        if (variant == null) {
            return 0;
        }
        List<ResourceLocation> sprites = partSprites(sourceState, variant, ModelData.EMPTY);
        Vec3 location = hit.getLocation();
        BlockPos pos = hit.getBlockPos();
        double px = location.x - pos.getX();
        double py = location.y - pos.getY();
        double pz = location.z - pos.getZ();
        RandomSource random = random();
        Closest closest = new Closest(hit.getDirection());
        consider(variant, sourceState, hit.getDirection(), random, sprites, px, py, pz, closest);
        consider(variant, sourceState, null, random, sprites, px, py, pz, closest);
        return closest.part;
    }

    public static int targetPart(BlockState state, BlockHitResult hit) {
        if (!(state.getBlock() instanceof CopiedFramedBlock block)) {
            return 0;
        }
        TextureAtlasSprite particle = Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
        return new CopiedFramedModel(block, particle).partAt(state, hit);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState framedState = state == null ? block.defaultBlockState() : state;
        BlockState sourceState = block.copiedType().toSourceState(framedState);
        BakedModel variant = sourceModel(sourceState);
        if (variant == null) {
            return ModelUtils.CUTOUT;
        }
        List<ResourceLocation> sprites = partSprites(sourceState, variant, sourceModelData(data));
        int partCount = visibleCount(sprites);
        boolean anyAssigned = false;
        boolean anyOpen = partCount == 0;
        boolean[] assigned = new boolean[partCount];
        for (int index = 0; index < partCount; index++) {
            CamoContent<?> part = camoAt(data, index);
            boolean filled = part != null && !part.isEmpty();
            assigned[index] = filled;
            if (filled) {
                anyAssigned = true;
            } else {
                anyOpen = true;
            }
        }
        ChunkRenderTypeSet layers = partLayers(data, rand, assigned, anyAssigned, anyOpen);
        for (ResourceLocation sprite : sprites) {
            if (sprite != null && sawSprite(sprite)) {
                return ChunkRenderTypeSet.union(layers, ModelUtils.CUTOUT);
            }
        }
        return layers;
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this);
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        // Item drawing only accepts entity-format sheets. The chunk cutout type writes nothing in a GUI.
        return List.of(RenderTypeHelper.getEntityRenderType(RenderType.cutout(), fabulous));
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
    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        // The render type here belongs to the applied camouflage, not the copied
        // source model. Passing it through can make AO change between render passes.
        return sourceAmbientOcclusion(block.copiedType().toSourceState(state));
    }

    private TriState sourceAmbientOcclusion(BlockState sourceState) {
        BakedModel model = sourceModel(sourceState);
        if (model == null) {
            return TriState.DEFAULT;
        }
        return model.useAmbientOcclusion(sourceState, ModelData.EMPTY, null);
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
        return particle;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        FramedBlockData framedData = data.get(FramedBlockData.PROPERTY);
        CamoContent<?> camo = framedData == null ? null : framedData.getCamoContent();
        if (camo != null && !camo.isEmpty()) {
            BakedModel model = camoModel(camo);
            if (model != null) {
                return model.getParticleIcon(camoModelData(data));
            }
        }
        return particle;
    }

    @Nullable
    private BakedModel sourceModel(BlockState sourceState) {
        try {
            BakedModel model = ModelUtils.getModel(sourceState);
            if (model != null && model != Minecraft.getInstance().getModelManager().getMissingModel()) {
                return model;
            }
        } catch (RuntimeException ignored) {
            // Model lookup can run before the bakery is ready; retry on the next render call.
        }
        return null;
    }

    private TextureAtlasSprite frameSprite() {
        try {
            return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(FRAME_TEXTURE);
        } catch (IllegalStateException exception) {
            return particle;
        }
    }

    private static ModelData sourceModelData(ModelData data) {
        ModelData stored = data.get(CopiedFramedBlockEntity.SOURCE_MODEL_DATA);
        return stored == null ? ModelData.EMPTY : stored;
    }

    private static ModelData camoModelData(ModelData data) {
        ModelData stored = data.get(CopiedFramedBlockEntity.CAMO_MODEL_DATA);
        return stored == null ? ModelUtils.getCamoModelData(data) : stored;
    }

    private static <C extends CamoContent<C>> ChunkRenderTypeSet renderTypes(CamoContent<?> raw, RandomSource rand, ModelData data) {
        C camo = cast(raw);
        return camo.getClientHandler().getRenderTypes(camo, rand, data);
    }

    @Nullable
    private static <C extends CamoContent<C>> BakedModel camoModel(CamoContent<?> raw) {
        C camo = cast(raw);
        CamoClientHandler<C> handler = camo.getClientHandler();
        return handler.getOrCreateModel(camo);
    }

    @SuppressWarnings("unchecked")
    private static <C extends CamoContent<C>> C cast(CamoContent<?> raw) {
        return (C) raw;
    }

    private List<ResourceLocation> partSprites(BlockState sourceState, BakedModel variant, ModelData sourceData) {
        return spriteParts.computeIfAbsent(sourceState, state -> {
            TreeSet<ResourceLocation> names = new TreeSet<>();
            RandomSource random = random();
            for (Direction side : Direction.values()) {
                collectSprites(variant, state, side, sourceData, random, names);
            }
            collectSprites(variant, state, null, sourceData, random, names);
            return List.copyOf(names);
        });
    }

    private static void collectSprites(BakedModel variant, BlockState state, @Nullable Direction side, ModelData sourceData, RandomSource random, TreeSet<ResourceLocation> names) {
        List<BakedQuad> quads;
        try {
            quads = variant.getQuads(state, side, random, sourceData, null);
        } catch (RuntimeException ignored) {
            return;
        }
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite != null) {
                names.add(sprite.contents().name());
            }
        }
    }

    private static int visibleCount(List<ResourceLocation> sprites) {
        int count = 0;
        for (ResourceLocation sprite : sprites) {
            if (!FramedAdditionsConfig.excludesTexture(sprite)) {
                count++;
            }
        }
        return count;
    }

    private static int indexOf(List<ResourceLocation> sprites, ResourceLocation name) {
        if (FramedAdditionsConfig.excludesTexture(name)) {
            return -1;
        }
        int index = 0;
        for (ResourceLocation sprite : sprites) {
            if (FramedAdditionsConfig.excludesTexture(sprite)) {
                continue;
            }
            if (sprite.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    @Nullable
    private static CamoContent<?> camoAt(ModelData data, int index) {
        if (index == 0) {
            FramedBlockData framed = data.get(FramedBlockData.PROPERTY);
            return framed == null ? null : framed.getCamoContent();
        }
        List<CamoContent<?>> extras = data.get(CopiedFramedBlockEntity.PART_CONTENTS);
        int slot = index - 1;
        if (extras == null || slot < 0 || slot >= extras.size()) {
            return null;
        }
        return extras.get(slot);
    }

    private static ModelData modelDataAt(ModelData data, int index) {
        if (index == 0) {
            return camoModelData(data);
        }
        List<ModelData> extras = data.get(CopiedFramedBlockEntity.PART_MODEL_DATA);
        int slot = index - 1;
        if (extras == null || slot < 0 || slot >= extras.size() || extras.get(slot) == null) {
            return ModelData.EMPTY;
        }
        return extras.get(slot);
    }

    private static ChunkRenderTypeSet partLayers(ModelData data, RandomSource rand, boolean[] assigned, boolean anyAssigned, boolean anyOpen) {
        if (!anyAssigned) {
            return ModelUtils.CUTOUT;
        }
        ChunkRenderTypeSet union = ChunkRenderTypeSet.none();
        for (int index = 0; index < assigned.length; index++) {
            if (!assigned[index]) {
                continue;
            }
            CamoContent<?> part = camoAt(data, index);
            if (part == null || part.isEmpty()) {
                continue;
            }
            union = ChunkRenderTypeSet.union(union, renderTypes(part, rand, modelDataAt(data, index)));
        }
        if (anyOpen) {
            union = ChunkRenderTypeSet.union(union, ModelUtils.CUTOUT);
        }
        return union.isEmpty() ? ModelUtils.CUTOUT : union;
    }

    private static List<Sample> sampleLayers(CamoContent<?> camo, @Nullable Direction side, RenderType layer, ModelData modelData) {
        BakedModel model = camoModel(camo);
        BlockState state = camo.getAppearanceState();
        if (model == null || state == null) {
            return List.of(Sample.EMPTY);
        }
        List<BakedQuad> quads = faceQuads(model, state, side, layer, modelData);
        Sample base = null;
        List<Sample> overlays = new ArrayList<>();
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite == null) {
                continue;
            }
            // A tinted overlay is drawn as well as the solid face, so grass stays green and the solid texture remains.
            if (quad.getTintIndex() >= 0 && mostlyTransparent(sprite)) {
                overlays.add(new Sample(sprite, quad.getTintIndex()));
            } else if (base == null && !mostlyTransparent(sprite)) {
                base = new Sample(sprite, quad.getTintIndex());
            }
        }
        if (base == null && overlays.isEmpty() && !quads.isEmpty() && quads.get(0).getSprite() != null) {
            base = new Sample(quads.get(0).getSprite(), quads.get(0).getTintIndex());
        }
        if (base == null && overlays.isEmpty()) {
            return List.of(new Sample(model.getParticleIcon(modelData), -1));
        }
        List<Sample> layers = new ArrayList<>(overlays.size() + 1);
        if (base != null) {
            layers.add(base);
        }
        layers.addAll(overlays);
        return layers;
    }

    private static int encodeTint(int part, int tint) {
        if (tint < 0) {
            return -1;
        }
        return part * 16 + tint;
    }

    private void consider(BakedModel variant, BlockState sourceState, @Nullable Direction side, RandomSource random, List<ResourceLocation> sprites, double px, double py, double pz, Closest closest) {
        List<BakedQuad> quads;
        try {
            quads = variant.getQuads(sourceState, side, random, ModelData.EMPTY, null);
        } catch (RuntimeException ignored) {
            return;
        }
        Direction face = closest.face;
        for (BakedQuad quad : quads) {
            if (side == null && quad.getDirection() != face) {
                continue;
            }
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite == null || FramedAdditionsConfig.excludesTexture(sprite.contents().name())) {
                continue;
            }
            int index = indexOf(sprites, sprite.contents().name());
            if (index < 0) {
                continue;
            }
            double distance = distanceToQuad(px, py, pz, quad);
            if (distance < closest.best) {
                closest.best = distance;
                closest.part = index;
            }
        }
    }

    private static double distanceToQuad(double px, double py, double pz, BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        if (stride < 3) {
            return Double.MAX_VALUE;
        }
        double[] x = new double[4];
        double[] y = new double[4];
        double[] z = new double[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            x[vertex] = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION]);
            y[vertex] = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 1]);
            z[vertex] = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 2]);
        }
        return Math.min(
                distanceToTriangle(px, py, pz, x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]),
                distanceToTriangle(px, py, pz, x[0], y[0], z[0], x[2], y[2], z[2], x[3], y[3], z[3])
        );
    }

    private static double distanceToTriangle(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double acx = cx - ax;
        double acy = cy - ay;
        double acz = cz - az;
        double apx = px - ax;
        double apy = py - ay;
        double apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0.0D && d2 <= 0.0D) {
            return distanceSquared(px, py, pz, ax, ay, az);
        }
        double bpx = px - bx;
        double bpy = py - by;
        double bpz = pz - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz;
        double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0.0D && d4 <= d3) {
            return distanceSquared(px, py, pz, bx, by, bz);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0D && d1 >= 0.0D && d3 <= 0.0D) {
            double v = d1 / (d1 - d3);
            return distanceSquared(px, py, pz, ax + abx * v, ay + aby * v, az + abz * v);
        }
        double cpx = px - cx;
        double cpy = py - cy;
        double cpz = pz - cz;
        double d5 = abx * cpx + aby * cpy + abz * cpz;
        double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0.0D && d5 <= d6) {
            return distanceSquared(px, py, pz, cx, cy, cz);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0D && d2 >= 0.0D && d6 <= 0.0D) {
            double w = d2 / (d2 - d6);
            return distanceSquared(px, py, pz, ax + acx * w, ay + acy * w, az + acz * w);
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0.0D && (d4 - d3) >= 0.0D && (d5 - d6) >= 0.0D) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return distanceSquared(px, py, pz, bx + (cx - bx) * w, by + (cy - by) * w, bz + (cz - bz) * w);
        }
        double denom = 1.0D / (va + vb + vc);
        double v = vb * denom;
        double w = vc * denom;
        return distanceSquared(px, py, pz, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
    }

    private static double distanceSquared(double px, double py, double pz, double x, double y, double z) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static RandomSource random() {
        RandomSource random = RANDOM.get();
        random.setSeed(0L);
        return random;
    }

    private static RenderType primaryLayer(CamoContent<?> camo, ModelData data) {
        RandomSource random = random();
        ChunkRenderTypeSet layers = renderTypes(camo, random, data);
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
        List<BakedQuad> quads = faceQuads(model, state, side, layer, modelData);
        if (quads.isEmpty()) {
            return null;
        }
        // Grass and nylium draw a tinted overlay on top of the solid face. That overlay is
        // mostly empty, so a saw sampled from it disappears. Prefer the opaque face texture.
        List<BakedQuad> opaque = new ArrayList<>();
        for (BakedQuad quad : quads) {
            if (!mostlyTransparent(quad.getSprite())) {
                opaque.add(quad);
            }
        }
        List<BakedQuad> usable = opaque.isEmpty() ? quads : opaque;
        return usable.get(Math.min(faceIndex, usable.size() - 1));
    }

    private static List<BakedQuad> faceQuads(BakedModel model, BlockState state, @Nullable Direction side, RenderType layer, ModelData modelData) {
        RandomSource random = random();
        List<BakedQuad> quads = model.getQuads(state, side, random, modelData, layer);
        if (quads.isEmpty()) {
            random = random();
            quads = model.getQuads(state, side, random);
        }
        if (!quads.isEmpty() || side == null) {
            return quads;
        }
        random = random();
        List<BakedQuad> general = model.getQuads(state, null, random, modelData, layer);
        if (general.isEmpty()) {
            random = random();
            general = model.getQuads(state, null, random);
        }
        List<BakedQuad> matching = new ArrayList<>();
        for (BakedQuad quad : general) {
            if (quad.getDirection() == side) {
                matching.add(quad);
            }
        }
        return matching;
    }

    private static boolean mostlyTransparent(TextureAtlasSprite sprite) {
        Boolean cached = MOSTLY_TRANSPARENT.get(sprite);
        if (cached != null) {
            return cached;
        }
        boolean transparent = false;
        try {
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            int total = width * height;
            if (total > 0) {
                int empty = 0;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (sprite.contents().isTransparent(0, x, y)) {
                            empty++;
                        }
                    }
                }
                transparent = empty * 10 >= total;
            }
        } catch (RuntimeException ignored) {
            transparent = false;
        }
        MOSTLY_TRANSPARENT.put(sprite, transparent);
        return transparent;
    }

    private static boolean sawSprite(@Nullable ResourceLocation sprite) {
        return sprite != null && sprite.getPath().endsWith("stonecutter_saw");
    }

    private static boolean brewingSprite(@Nullable ResourceLocation sprite) {
        if (sprite == null) {
            return false;
        }
        String path = sprite.getPath();
        return path.endsWith("brewing_stand") || path.endsWith("brewing_stand_base");
    }

    /**
     * Bottle and empty-slot planes are drawings on the brewing-stand texture, not the stand.
     * The post is the thin piece of that same texture and stays.
     */
    private static boolean brewingCard(BakedQuad quad, ResourceLocation sprite) {
        if (sprite.getPath().endsWith("brewing_stand_base")) {
            return false;
        }
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        if (stride < IQuadTransformer.POSITION + 3) {
            return false;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            float x = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION]);
            float y = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 1]);
            float z = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 2]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        float largest = Math.max(dx, Math.max(dy, dz));
        float middle = dx + dy + dz - largest - Math.min(dx, Math.min(dy, dz));
        return largest > 0.2F && middle > 0.2F;
    }

    private static List<BakedQuad> shiftOut(List<BakedQuad> quads, @Nullable Direction direction, float amount) {
        if (direction == null || quads.isEmpty() || amount == 0.0F) {
            return quads;
        }
        float dx = direction.getStepX() * amount;
        float dy = direction.getStepY() * amount;
        float dz = direction.getStepZ() * amount;
        List<BakedQuad> shifted = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            int[] vertices = quad.getVertices().clone();
            int stride = vertices.length / 4;
            if (stride < IQuadTransformer.POSITION + 3) {
                shifted.add(quad);
                continue;
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                int index = vertex * stride;
                float x = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION]) + dx;
                float y = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 1]) + dy;
                float z = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 2]) + dz;
                vertices[index + IQuadTransformer.POSITION] = Float.floatToRawIntBits(x);
                vertices[index + IQuadTransformer.POSITION + 1] = Float.floatToRawIntBits(y);
                vertices[index + IQuadTransformer.POSITION + 2] = Float.floatToRawIntBits(z);
            }
            shifted.add(new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion()));
        }
        return shifted;
    }

    private static List<BakedQuad> ladderCamo(BakedQuad quad, TextureAtlasSprite mask, TextureAtlasSprite fill, int tint) {
        int direction = quad.getDirection() == null ? 0 : quad.getDirection().ordinal() + 1;
        LadderKey key = new LadderKey(mask, fill, tint, Arrays.hashCode(quad.getVertices()) * 31 + direction);
        List<BakedQuad> cached = LADDER_QUADS.get(key);
        if (cached != null) {
            return cached;
        }
        List<BakedQuad> built = buildLadderCamo(quad, mask, fill, tint);
        LADDER_QUADS.put(key, built);
        return built;
    }

    private static List<BakedQuad> buildLadderCamo(BakedQuad quad, TextureAtlasSprite mask, TextureAtlasSprite fill, int tint) {
        int[] source = quad.getVertices();
        int stride = source.length / 4;
        if (stride < IQuadTransformer.UV0 + 2) {
            return List.of(retexture(quad, fill, tint));
        }
        int width;
        int height;
        try {
            width = mask.contents().width();
            height = mask.contents().height();
        } catch (RuntimeException ignored) {
            return List.of(retexture(quad, fill, tint));
        }
        if (width <= 0 || height <= 0) {
            return List.of(retexture(quad, fill, tint));
        }
        int stepX = Math.max(1, width / 16);
        int stepY = Math.max(1, height / 16);
        float[] localU = new float[4];
        float[] localV = new float[4];
        float[] px = new float[4];
        float[] py = new float[4];
        float[] pz = new float[4];
        float minU = Float.MAX_VALUE;
        float maxU = -Float.MAX_VALUE;
        float minV = Float.MAX_VALUE;
        float maxV = -Float.MAX_VALUE;
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            px[vertex] = Float.intBitsToFloat(source[index + IQuadTransformer.POSITION]);
            py[vertex] = Float.intBitsToFloat(source[index + IQuadTransformer.POSITION + 1]);
            pz[vertex] = Float.intBitsToFloat(source[index + IQuadTransformer.POSITION + 2]);
            float u = Float.intBitsToFloat(source[index + IQuadTransformer.UV0]);
            float v = Float.intBitsToFloat(source[index + IQuadTransformer.UV0 + 1]);
            localU[vertex] = unmap(u, mask.getU0(), mask.getU1());
            localV[vertex] = unmap(v, mask.getV0(), mask.getV1());
            minU = Math.min(minU, localU[vertex]);
            maxU = Math.max(maxU, localU[vertex]);
            minV = Math.min(minV, localV[vertex]);
            maxV = Math.max(maxV, localV[vertex]);
        }
        int origin = 0;
        for (int vertex = 1; vertex < 4; vertex++) {
            if (localU[vertex] < localU[origin] - 1.0E-4F || (Math.abs(localU[vertex] - localU[origin]) <= 1.0E-4F && localV[vertex] < localV[origin])) {
                origin = vertex;
            }
        }
        int uEdge = -1;
        float bestDu = -1.0F;
        float bestDv = Float.MAX_VALUE;
        for (int vertex = 0; vertex < 4; vertex++) {
            if (vertex == origin) {
                continue;
            }
            float du = Math.abs(localU[vertex] - localU[origin]);
            float dv = Math.abs(localV[vertex] - localV[origin]);
            if (du > bestDu + 1.0E-4F || (Math.abs(du - bestDu) <= 1.0E-4F && dv < bestDv)) {
                bestDu = du;
                bestDv = dv;
                uEdge = vertex;
            }
        }
        int vEdge = -1;
        bestDv = -1.0F;
        float bestDuOnV = Float.MAX_VALUE;
        for (int vertex = 0; vertex < 4; vertex++) {
            if (vertex == origin || vertex == uEdge) {
                continue;
            }
            float du = Math.abs(localU[vertex] - localU[origin]);
            float dv = Math.abs(localV[vertex] - localV[origin]);
            if (dv > bestDv + 1.0E-4F || (Math.abs(dv - bestDv) <= 1.0E-4F && du < bestDuOnV)) {
                bestDv = dv;
                bestDuOnV = du;
                vEdge = vertex;
            }
        }
        if (uEdge < 0 || vEdge < 0 || bestDu < 1.0E-4F || bestDv < 1.0E-4F) {
            return List.of(retexture(quad, fill, tint));
        }
        List<BakedQuad> built = new ArrayList<>();
        for (int y = 0; y < height; y += stepY) {
            int x = 0;
            while (x < width) {
                if (pixelClear(mask, x, y)) {
                    x += stepX;
                    continue;
                }
                int start = x;
                x += stepX;
                while (x < width && !pixelClear(mask, x, y)) {
                    x += stepX;
                }
                built.add(ladderPiece(quad, source, stride, fill, tint, localU, localV, px, py, pz, origin, uEdge, vEdge, minU, maxU, minV, maxV, start, x, y, Math.min(height, y + stepY), width, height));
            }
        }
        if (built.isEmpty()) {
            return List.of(retexture(quad, fill, tint));
        }
        return List.copyOf(built);
    }

    private static boolean pixelClear(TextureAtlasSprite sprite, int x, int y) {
        try {
            return sprite.contents().isTransparent(0, x, y);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static BakedQuad ladderPiece(BakedQuad quad, int[] source, int stride, TextureAtlasSprite fill, int tint, float[] localU, float[] localV, float[] px, float[] py, float[] pz, int origin, int uEdge, int vEdge, float minU, float maxU, float minV, float maxV, int x0, int x1, int y0, int y1, int width, int height) {
        float startU = x0 / (float) width;
        float endU = x1 / (float) width;
        float startV = y0 / (float) height;
        float endV = y1 / (float) height;
        float uSpan = localU[uEdge] - localU[origin];
        float vSpan = localV[vEdge] - localV[origin];
        int[] vertices = new int[source.length];
        float midU = (minU + maxU) * 0.5F;
        float midV = (minV + maxV) * 0.5F;
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            System.arraycopy(source, index, vertices, index, stride);
            float spriteU = localU[vertex] >= midU ? endU : startU;
            float spriteV = localV[vertex] >= midV ? endV : startV;
            float alongU = (spriteU - localU[origin]) / uSpan;
            float alongV = (spriteV - localV[origin]) / vSpan;
            float x = px[origin] + alongU * (px[uEdge] - px[origin]) + alongV * (px[vEdge] - px[origin]);
            float y = py[origin] + alongU * (py[uEdge] - py[origin]) + alongV * (py[vEdge] - py[origin]);
            float z = pz[origin] + alongU * (pz[uEdge] - pz[origin]) + alongV * (pz[vEdge] - pz[origin]);
            vertices[index + IQuadTransformer.POSITION] = Float.floatToRawIntBits(x);
            vertices[index + IQuadTransformer.POSITION + 1] = Float.floatToRawIntBits(y);
            vertices[index + IQuadTransformer.POSITION + 2] = Float.floatToRawIntBits(z);
            vertices[index + IQuadTransformer.COLOR] = 0xFFFFFFFF;
            vertices[index + IQuadTransformer.UV0] = Float.floatToRawIntBits(fill.getU(spriteU));
            vertices[index + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(fill.getV(spriteV));
        }
        return new BakedQuad(vertices, tint, quad.getDirection(), fill, quad.isShade(), quad.hasAmbientOcclusion());
    }

    private static float unmap(float coord, float min, float max) {
        float span = max - min;
        if (span == 0.0F) {
            return 0.0F;
        }
        return (coord - min) / span;
    }

    private static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite sprite, int tintIndex) {
        TextureAtlasSprite old = quad.getSprite();
        if (old == sprite && quad.getTintIndex() == tintIndex) {
            return quad;
        }

        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            // Item rendering multiplies by this color. A zero from the source quad blanks the icon.
            // Cutout pieces use this same sprite as the solid faces, so a saw is frame or camo, not the source texture.
            vertices[index + IQuadTransformer.COLOR] = 0xFFFFFFFF;
            if (old != sprite) {
                float u = Float.intBitsToFloat(vertices[index + IQuadTransformer.UV0]);
                float v = Float.intBitsToFloat(vertices[index + IQuadTransformer.UV0 + 1]);
                vertices[index + IQuadTransformer.UV0] = Float.floatToRawIntBits(remap(u, old.getU0(), old.getU1(), sprite.getU0(), sprite.getU1()));
                vertices[index + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(remap(v, old.getV0(), old.getV1(), sprite.getV0(), sprite.getV1()));
            }
        }
        return new BakedQuad(vertices, tintIndex, quad.getDirection(), sprite, quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * Samples the framed or camouflage texture from where the face sits on the block.
     * Brewing-stand faces pack their UVs into the vanilla art, which is the wrong part of this texture.
     */
    private static BakedQuad retextureSpatial(BakedQuad quad, TextureAtlasSprite sprite, int tintIndex) {
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        Direction direction = quad.getDirection();
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            vertices[index + IQuadTransformer.COLOR] = 0xFFFFFFFF;
            if (stride < IQuadTransformer.POSITION + 3 || stride < IQuadTransformer.UV0 + 2) {
                continue;
            }
            float x = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION]) * 16.0F;
            float y = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 1]) * 16.0F;
            float z = Float.intBitsToFloat(vertices[index + IQuadTransformer.POSITION + 2]) * 16.0F;
            float u = x;
            float v = 16.0F - y;
            if (direction == Direction.DOWN) {
                u = x;
                v = z;
            } else if (direction == Direction.UP) {
                u = x;
                v = 16.0F - z;
            } else if (direction == Direction.NORTH) {
                u = 16.0F - x;
                v = 16.0F - y;
            } else if (direction == Direction.SOUTH) {
                u = x;
                v = 16.0F - y;
            } else if (direction == Direction.WEST) {
                u = z;
                v = 16.0F - y;
            } else if (direction == Direction.EAST) {
                u = 16.0F - z;
                v = 16.0F - y;
            }
            vertices[index + IQuadTransformer.UV0] = Float.floatToRawIntBits(sprite.getU(u));
            vertices[index + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(sprite.getV(v));
        }
        return new BakedQuad(vertices, tintIndex, direction, sprite, quad.isShade(), quad.hasAmbientOcclusion());
    }

    private static float remap(float coord, float fromMin, float fromMax, float toMin, float toMax) {
        float span = fromMax - fromMin;
        if (span == 0.0F) {
            return toMin;
        }
        return toMin + ((coord - fromMin) / span) * (toMax - toMin);
    }

    private record LadderKey(TextureAtlasSprite mask, TextureAtlasSprite fill, int tint, int geometry) {
    }

    private static final class SampleKey {
        private final CamoContent<?> camo;
        @Nullable
        private final Direction side;
        @Nullable
        private final RenderType layer;

        private SampleKey(CamoContent<?> camo, @Nullable Direction side, @Nullable RenderType layer) {
            this.camo = camo;
            this.side = side;
            this.layer = layer;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SampleKey key)) {
                return false;
            }
            return camo == key.camo && side == key.side && layer == key.layer;
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(camo) * 31 + Objects.hashCode(side)) * 31 + Objects.hashCode(layer);
        }
    }

    private static final class Sample {
        private static final Sample EMPTY = new Sample(null, -1);

        @Nullable
        private final TextureAtlasSprite sprite;
        private final int tint;

        private Sample(@Nullable TextureAtlasSprite sprite, int tint) {
            this.sprite = sprite;
            this.tint = tint;
        }
    }

    private static final class Closest {
        private final Direction face;
        private double best = 0.25D;
        private int part;

        private Closest(Direction face) {
            this.face = face;
        }
    }
}
