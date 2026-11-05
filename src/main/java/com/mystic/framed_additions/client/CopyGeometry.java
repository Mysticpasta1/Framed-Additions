package com.mystic.framed_additions.client;

import com.mystic.framed_additions.CopiedFramedBlock;
import com.mystic.framed_additions.FramedAdditions;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public final class CopyGeometry implements IUnbakedGeometry<CopyGeometry> {
    private static final ResourceLocation FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath("framedblocks", "block/framed_block");
    static final IGeometryLoader<CopyGeometry> LOADER = (json, context) -> {
        if (!json.has("source")) {
            return new CopyGeometry(null);
        }
        ResourceLocation sourceId;
        try {
            sourceId = ResourceLocation.parse(json.get("source").getAsString());
        } catch (RuntimeException exception) {
            return new CopyGeometry(null);
        }
        ResourceLocation copyId = ResourceLocation.fromNamespaceAndPath(FramedAdditions.MODID, sourceId.getNamespace() + "/" + sourceId.getPath());
        Block copy = BuiltInRegistries.BLOCK.get(copyId);
        return new CopyGeometry(copy instanceof CopiedFramedBlock framed ? framed : null);
    };

    @Nullable
    private final CopiedFramedBlock block;

    private CopyGeometry(@Nullable CopiedFramedBlock block) {
        this.block = block;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        // Particle lookup happens while the atlas is still being stitched. The sprite getter
        // reads the stitch result; the live atlas throws until upload finishes.
        TextureAtlasSprite particle = spriteGetter.apply(new Material(TextureAtlas.LOCATION_BLOCKS, FRAME_TEXTURE));
        if (block == null || particle == null) {
            return net.neoforged.neoforge.client.model.EmptyModel.BAKED;
        }

        ItemTransforms transforms = ItemTransforms.NO_TRANSFORMS;
        boolean gui3d = true;
        boolean ambientOcclusion = true;
        boolean blockLight = true;
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(block.source());
        if (sourceId != null) {
            ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(sourceId.getNamespace(), "block/" + sourceId.getPath());
            try {
                BakedModel source = baker.bake(modelId, modelState, spriteGetter);
                if (source != null) {
                    transforms = source.getTransforms();
                    gui3d = source.isGui3d();
                    ambientOcclusion = source.useAmbientOcclusion();
                    blockLight = source.usesBlockLight();
                }
            } catch (RuntimeException ignored) {
                // The source model is optional. Quads are copied on the first rendered frame.
            }
        }
        return new LazyCopiedModel(block, particle, transforms, gui3d, ambientOcclusion, blockLight);
    }
}
