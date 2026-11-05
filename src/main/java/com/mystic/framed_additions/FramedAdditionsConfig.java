package com.mystic.framed_additions;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FramedAdditionsConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue BLACKLIST_CROSS_PLANTS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> MODEL_BLACKLIST;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> TEXTURE_BLACKLIST;

    private static volatile Compiled modelPatterns = Compiled.EMPTY;
    private static volatile Compiled texturePatterns = Compiled.EMPTY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment(
                "Cross models are always skipped, including cobwebs and other blocks that are not plants.",
                "This option remains so older config files still load."
        );
        BLACKLIST_CROSS_PLANTS = builder.gameRestart().define("blacklistCrossPlants", true);
        builder.comment(
                "Java regular expressions for source models that should not be copied.",
                "Each pattern is matched against the source block id (namespace:path) and the source model id (namespace:block/path).",
                "The pattern must match the whole id. Examples: .*_door or minecraft:block/stonecutter"
        );
        MODEL_BLACKLIST = builder
                .gameRestart()
                .defineListAllowEmpty("modelBlacklist", List.of(), () -> "", value -> value instanceof String);
        builder.comment(
                "Java regular expressions for source textures that should not be copied onto a framed block.",
                "Each pattern is matched against the texture id (namespace:path) and the texture file (namespace:textures/path.png).",
                "The pattern must match the whole id. A matching face is left out of the framed model. Examples: .*stonecutter_saw or minecraft:block/grass_block_side_overlay"
        );
        TEXTURE_BLACKLIST = builder
                .defineListAllowEmpty("textureBlacklist", List.of(), () -> "", value -> value instanceof String);
        SPEC = builder.build();
    }

    public static boolean blacklistCrossPlants() {
        try {
            return BLACKLIST_CROSS_PLANTS.get();
        } catch (IllegalStateException exception) {
            return true;
        }
    }

    public static boolean excludes(ResourceLocation id) {
        String blockId = id.toString();
        String modelId = id.getNamespace() + ":block/" + id.getPath();
        for (Pattern pattern : patterns(MODEL_BLACKLIST, "model")) {
            if (pattern.matcher(blockId).matches() || pattern.matcher(modelId).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean excludesTexture(ResourceLocation texture) {
        String textureId = texture.toString();
        String file = texture.getNamespace() + ":textures/" + texture.getPath() + ".png";
        for (Pattern pattern : patterns(TEXTURE_BLACKLIST, "texture")) {
            if (pattern.matcher(textureId).matches() || pattern.matcher(file).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> patterns(ModConfigSpec.ConfigValue<List<? extends String>> value, String label) {
        List<? extends String> configured;
        try {
            configured = value.get();
        } catch (IllegalStateException exception) {
            return List.of();
        }
        Compiled current = label.equals("model") ? modelPatterns : texturePatterns;
        if (current.source == configured) {
            return current.patterns;
        }
        List<Pattern> parsed = new ArrayList<>();
        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                parsed.add(Pattern.compile(raw));
            } catch (PatternSyntaxException exception) {
                FramedAdditions.LOGGER.warn("Ignoring invalid {} blacklist pattern '{}': {}", label, raw, exception.getMessage());
            }
        }
        Compiled compiled = new Compiled(configured, List.copyOf(parsed));
        if (label.equals("model")) {
            modelPatterns = compiled;
        } else {
            texturePatterns = compiled;
        }
        return compiled.patterns;
    }

    private record Compiled(List<? extends String> source, List<Pattern> patterns) {
        private static final Compiled EMPTY = new Compiled(List.of(), List.of());
    }

    private FramedAdditionsConfig() {
    }
}
