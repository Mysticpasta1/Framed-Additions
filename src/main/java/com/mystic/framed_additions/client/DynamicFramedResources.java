package com.mystic.framed_additions.client;

import com.mystic.framed_additions.CopiedFramedBlock;
import com.mystic.framed_additions.FramedAdditions;
import com.mystic.framed_additions.FramedRegistry;
import net.minecraft.SharedConstants;
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
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DynamicFramedResources extends AbstractPackResources {
    private static final String PACK_ID = "framed_additions_dynamic";
    private static final IoSupplier<InputStream> LANG = supplier("{\"itemGroup.framed_additions\":\"Framed Additions\",\"item.framed_additions.copy\":\"Framed %s\"}");
    private static volatile Map<String, IoSupplier<InputStream>> index;

    private DynamicFramedResources(PackLocationInfo info) {
        super(info);
    }

    public static void add(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        PackLocationInfo info = new PackLocationInfo(
                PACK_ID,
                Component.literal("Framed Additions"),
                PackSource.BUILT_IN,
                Optional.empty()
        );
        Pack pack = Pack.readMetaAndCreate(
                info,
                new Pack.ResourcesSupplier() {
                    @Override
                    public PackResources openPrimary(PackLocationInfo location) {
                        return new DynamicFramedResources(location);
                    }

                    @Override
                    public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                        return openPrimary(location);
                    }
                },
                PackType.CLIENT_RESOURCES,
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
            int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
            return supplier("{\"pack\":{\"description\":\"framed additions\",\"pack_format\":" + format + "}}");
        }
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || !FramedAdditions.MODID.equals(location.getNamespace())) {
            return null;
        }
        return index().get(location.getPath());
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, PackResources.ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !FramedAdditions.MODID.equals(namespace)) {
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
        return type == PackType.CLIENT_RESOURCES ? Set.of(FramedAdditions.MODID) : Set.of();
    }

    @Override
    public void close() {
    }

    private static Map<String, IoSupplier<InputStream>> index() {
        Map<String, IoSupplier<InputStream>> current = index;
        if (current != null) {
            return current;
        }
        synchronized (DynamicFramedResources.class) {
            if (index == null) {
                Map<String, IoSupplier<InputStream>> built = new HashMap<>();
                for (CopiedFramedBlock block : FramedRegistry.BLOCKS) {
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                    ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(block.source());
                    if (id == null || sourceId == null) {
                        continue;
                    }
                    String path = id.getPath();
                    String model = "{\"loader\":\"framed_additions:copy\",\"source\":\"" + sourceId + "\"}";
                    built.put("blockstates/" + path + ".json", supplier("{\"multipart\":[{\"apply\":{\"model\":\"framed_additions:block/" + path + "\"}}]}"));
                    built.put("models/block/" + path + ".json", supplier(model));
                    // A parented item model does not inherit a custom loader, so it bakes with no faces.
                    built.put("models/item/" + path + ".json", supplier(model));
                }
                built.put("lang/en_us.json", LANG);
                index = built;
            }
            return index;
        }
    }

    private static IoSupplier<InputStream> supplier(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }
}
