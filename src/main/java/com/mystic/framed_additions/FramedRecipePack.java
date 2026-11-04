package com.mystic.framed_additions;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FramedRecipePack extends AbstractPackResources {
    private static final String PACK_ID = "framed_additions_recipes";
    private static final String FRAME_CUBE = "framedblocks:framed_cube";
    private static final IoSupplier<InputStream> PACK_META = supplier("{\"pack\":{\"description\":\"framed additions recipes\",\"pack_format\":15}}");
    private static volatile Map<String, IoSupplier<InputStream>> index;
    private static volatile int indexedBlocks = -1;

    private FramedRecipePack() {
        super(PACK_ID, true);
    }

    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate(
                    PACK_ID,
                    Component.literal("Framed Additions Recipes"),
                    true,
                    id -> new FramedRecipePack(),
                    PackType.SERVER_DATA,
                    Pack.Position.TOP,
                    PackSource.BUILT_IN
            );
            if (pack != null) {
                consumer.accept(pack);
            }
        });
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            return PACK_META;
        }
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.SERVER_DATA || !FramedAdditions.MODID.equals(location.getNamespace())) {
            return null;
        }
        return index().get(location.getPath());
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, PackResources.ResourceOutput output) {
        if (type != PackType.SERVER_DATA || !FramedAdditions.MODID.equals(namespace)) {
            return;
        }
        Map<String, IoSupplier<InputStream>> files = index();
        String path = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        IoSupplier<InputStream> exact = files.get(path);
        if (exact != null) {
            output.accept(new ResourceLocation(FramedAdditions.MODID, path), exact);
            return;
        }
        if (path.endsWith(".json")) {
            return;
        }
        String start = path.isEmpty() ? "" : path + "/";
        for (Map.Entry<String, IoSupplier<InputStream>> entry : files.entrySet()) {
            if (entry.getKey().startsWith(start)) {
                output.accept(new ResourceLocation(FramedAdditions.MODID, entry.getKey()), entry.getValue());
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Set.of(FramedAdditions.MODID) : Set.of();
    }

    @Override
    public void close() {
    }

    private static Map<String, IoSupplier<InputStream>> index() {
        int blocks = FramedRegistry.BLOCKS.size();
        Map<String, IoSupplier<InputStream>> current = index;
        if (current != null && indexedBlocks == blocks) {
            return current;
        }
        synchronized (FramedRecipePack.class) {
            if (index != null && indexedBlocks == blocks) {
                return index;
            }
            Map<String, IoSupplier<InputStream>> built = new HashMap<>();
            for (CopiedFramedBlock block : FramedRegistry.uniqueShapeBlocks()) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                if (id == null) {
                    continue;
                }
                String result = id.getNamespace() + ":" + id.getPath();
                String json = "{\"type\":\"minecraft:stonecutting\",\"ingredient\":{\"item\":\"" + FRAME_CUBE + "\"},\"result\":\"" + result + "\",\"count\":1}";
                built.put("recipes/stonecutting/" + id.getPath() + ".json", supplier(json));
            }
            FramedAdditions.LOGGER.info("Added {} unique-shape stonecutter recipes from {}", built.size(), FRAME_CUBE);
            index = built;
            indexedBlocks = blocks;
            return built;
        }
    }

    private static IoSupplier<InputStream> supplier(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }
}
