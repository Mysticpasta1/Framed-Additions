package com.mystic.framed_additions;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.util.Utils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FramedRecipePack extends AbstractPackResources {
    private static final String PACK_ID = "framed_additions_recipes";
    private static final String FRAME_CUBE = "framedblocks:framed_cube";
    private static volatile Map<String, IoSupplier<InputStream>> index;
    private static volatile int indexedBlocks = -1;
    private static volatile int indexedBlacklist = Integer.MIN_VALUE;

    private FramedRecipePack(PackLocationInfo info) {
        super(info);
    }

    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        PackLocationInfo info = new PackLocationInfo(
                PACK_ID,
                Component.literal("Framed Additions Recipes"),
                PackSource.BUILT_IN,
                Optional.empty()
        );
        Pack pack = Pack.readMetaAndCreate(
                info,
                new Pack.ResourcesSupplier() {
                    @Override
                    public PackResources openPrimary(PackLocationInfo location) {
                        return new FramedRecipePack(location);
                    }

                    @Override
                    public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                        return openPrimary(location);
                    }
                },
                PackType.SERVER_DATA,
                new PackSelectionConfig(true, Pack.Position.TOP, false)
        );
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
            return supplier("{\"pack\":{\"description\":\"framed additions recipes\",\"pack_format\":" + format + "}}");
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
            output.accept(ResourceLocation.fromNamespaceAndPath(FramedAdditions.MODID, path), exact);
            return;
        }
        if (path.endsWith(".json")) {
            return;
        }
        String start = path.isEmpty() ? "" : path + "/";
        for (Map.Entry<String, IoSupplier<InputStream>> entry : files.entrySet()) {
            if (entry.getKey().startsWith(start)) {
                output.accept(ResourceLocation.fromNamespaceAndPath(FramedAdditions.MODID, entry.getKey()), entry.getValue());
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
        int blacklist = blacklistStamp();
        Map<String, IoSupplier<InputStream>> current = index;
        if (current != null && indexedBlocks == blocks && indexedBlacklist == blacklist) {
            return current;
        }
        synchronized (FramedRecipePack.class) {
            if (index != null && indexedBlocks == blocks && indexedBlacklist == blacklist) {
                return index;
            }
            Map<String, IoSupplier<InputStream>> built = new HashMap<>();
            int skipped = 0;
            for (CopiedFramedBlock block : FramedRegistry.BLOCKS) {
                // FramedBlocks rejects these as camouflage. They stay out of the stonecutter too.
                if (block.source().builtInRegistryHolder().is(Utils.BLOCK_BLACKLIST)) {
                    skipped++;
                    continue;
                }
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) {
                    continue;
                }
                String result = id.getNamespace() + ":" + id.getPath();
                // 1.21 loads data/<namespace>/recipe/, not recipes/. One framed cube cuts every framed block.
                String json = "{\"type\":\"minecraft:stonecutting\",\"ingredient\":{\"item\":\"" + FRAME_CUBE + "\"},\"result\":{\"id\":\"" + result + "\",\"count\":1}}";
                built.put("recipe/" + id.getPath() + ".json", supplier(json));
            }
            FramedAdditions.LOGGER.info("Added {} stonecutter recipes from one {}. Skipped {} sources on the FramedBlocks blacklist", built.size(), FRAME_CUBE, skipped);
            index = built;
            indexedBlocks = blocks;
            indexedBlacklist = blacklist;
            return built;
        }
    }

    /**
     * Changes when the FramedBlocks blacklist tag gains or loses entries, including the first time it binds.
     */
    private static int blacklistStamp() {
        var tag = BuiltInRegistries.BLOCK.getTag(Utils.BLOCK_BLACKLIST);
        if (tag.isEmpty()) {
            return 0;
        }
        int stamp = 1;
        for (Holder<Block> holder : tag.get()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(holder.value());
            stamp = 31 * stamp + (id == null ? 0 : id.hashCode());
        }
        return stamp;
    }

    private static IoSupplier<InputStream> supplier(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }
}
