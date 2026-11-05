package com.mystic.framed_additions;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DeadBushBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.FrogspawnBlock;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NetherSproutsBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.RootsBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SnifferEggBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import xfacthd.framedblocks.api.block.IFramedBlock;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod(FramedAdditions.MODID)
public class FramedAdditions {
    public static final String MODID = "framed_additions";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FramedAdditions(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, FramedAdditionsConfig.SPEC);
        modEventBus.register(Registration.class);
        modEventBus.addListener(FramedRecipePack::addPackFinders);
        modEventBus.addListener(PartTargetPayload::register);
    }

    private static final class Registration {
        private static final ResourceLocation OAK_PLANKS = ResourceLocation.fromNamespaceAndPath(MODID, "minecraft/oak_planks");

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onRegister(RegisterEvent event) {
            if (event.getRegistryKey().equals(Registries.BLOCK)) {
                registerBlocks(event);
            } else if (event.getRegistryKey().equals(Registries.ITEM)) {
                registerItems(event);
            } else if (event.getRegistryKey().equals(Registries.BLOCK_ENTITY_TYPE)) {
                registerBlockEntity(event);
            } else if (event.getRegistryKey().equals(Registries.CREATIVE_MODE_TAB)) {
                registerTab(event);
            }
        }

        private static void registerBlocks(RegisterEvent event) {
            List<Block> sources = new ArrayList<>();
            BuiltInRegistries.BLOCK.forEach(sources::add);
            sources.sort(Comparator.comparing(block -> {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                return id == null ? "" : id.toString();
            }));

            int registered = 0;
            int blacklisted = 0;
            Set<String> registeredShapes = new HashSet<>();
            for (Block source : sources) {
                ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(source);
                if (sourceId != null && FramedAdditionsConfig.excludes(sourceId)) {
                    blacklisted++;
                    continue;
                }
                if (sourceId == null || !shouldCopy(source, sourceId)) {
                    continue;
                }
                String shapeCategory = FramedRegistry.categoryKey(source);
                if (registeredShapes.contains(shapeCategory)) {
                    continue;
                }

                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MODID, sourceId.getNamespace() + "/" + sourceId.getPath());
                try {
                    Block framed = CopiedFramedBlock.create(source);
                    event.register(Registries.BLOCK, id, () -> framed);
                    FramedRegistry.BLOCKS.add((CopiedFramedBlock) framed);
                    registeredShapes.add(shapeCategory);
                    registered++;
                } catch (Throwable exception) {
                    LOGGER.warn("Skipped framed copy of {}", sourceId, exception);
                }
            }
            LOGGER.info("Registered {} framed block copies", registered);
            if (blacklisted > 0) {
                LOGGER.info("Skipped {} source models matching the model blacklist", blacklisted);
            }
        }

        private static boolean shouldCopy(Block source, ResourceLocation sourceId) {
            if (MODID.equals(sourceId.getNamespace())) {
                return false;
            }
            if ("bubble_column".equals(sourceId.getPath())) {
                return false;
            }
            if (source instanceof IFramedBlock) {
                return false;
            }
            if (source instanceof LiquidBlock) {
                return false;
            }
            if (isCrossPlant(source) || usesCrossModel(sourceId)) {
                return false;
            }
            if (isCropOrSpawn(source)) {
                return false;
            }
            if (isTorch(source, sourceId)) {
                return false;
            }
            if (isLilyOrRedstoneWire(source, sourceId)) {
                return false;
            }
            if (isLadderOrBrewingStand(source, sourceId)) {
                return false;
            }
            if (isDoor(source, sourceId)) {
                return false;
            }
            BlockState defaultState = source.defaultBlockState();
            // Signs, skulls, and chests are drawn by a block entity renderer and have no baked quads.
            // Bells stay: the support is a block model, and the swinging bell is drawn separately.
            return defaultState.getRenderShape() == RenderShape.MODEL
                    && !defaultState.isAir()
                    && defaultState.getFluidState().isEmpty();
        }

        private static boolean isDoor(Block source, ResourceLocation id) {
            if (source instanceof TrapDoorBlock) {
                return false;
            }
            if (source instanceof DoorBlock) {
                return true;
            }
            String path = id.getPath();
            if (path.endsWith("_trapdoor") || path.endsWith("_trap_door") || "trapdoor".equals(path)) {
                return false;
            }
            return path.endsWith("_door") || "door".equals(path);
        }

        private static boolean isLadderOrBrewingStand(Block source, ResourceLocation id) {
            if (source instanceof LadderBlock || source instanceof BrewingStandBlock) {
                return true;
            }
            String path = id.getPath();
            return "ladder".equals(path) || "brewing_stand".equals(path);
        }

        private static boolean isLilyOrRedstoneWire(Block source, ResourceLocation id) {
            if (source instanceof WaterlilyBlock || source instanceof RedStoneWireBlock) {
                return true;
            }
            String path = id.getPath();
            return "lily_pad".equals(path) || "redstone_wire".equals(path);
        }

        private static boolean isTorch(Block source, ResourceLocation id) {
            if (source instanceof BaseTorchBlock) {
                return true;
            }
            String path = id.getPath();
            return "torch".equals(path) || path.endsWith("_torch");
        }

        private static boolean isCropOrSpawn(Block source) {
            // Age crops and hatchable spawns. Nether wart is a bush, not a crop, and frogspawn is neither.
            return source instanceof CropBlock
                    || source instanceof NetherWartBlock
                    || source instanceof StemBlock
                    || source instanceof AttachedStemBlock
                    || source instanceof CocoaBlock
                    || source instanceof SweetBerryBushBlock
                    || source instanceof CaveVinesBlock
                    || source instanceof CaveVinesPlantBlock
                    || source instanceof PitcherCropBlock
                    || source instanceof FrogspawnBlock
                    || source instanceof TurtleEggBlock
                    || source instanceof SnifferEggBlock;
        }

        private static final Map<ResourceLocation, Boolean> CROSS_MODELS = new HashMap<>();

        private static boolean usesCrossModel(ResourceLocation blockId) {
            Boolean cached = CROSS_MODELS.get(blockId);
            if (cached != null) {
                return cached;
            }
            boolean cross = false;
            JsonObject blockState = readAsset(blockId.getNamespace(), "blockstates/" + blockId.getPath() + ".json");
            if (blockState != null) {
                List<ResourceLocation> models = new ArrayList<>();
                collectModels(blockState, models);
                for (ResourceLocation model : models) {
                    if (isCrossModel(model, new HashSet<>())) {
                        cross = true;
                        break;
                    }
                }
            }
            CROSS_MODELS.put(blockId, cross);
            return cross;
        }

        private static void collectModels(JsonElement element, List<ResourceLocation> models) {
            if (element == null || element.isJsonNull()) {
                return;
            }
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    collectModels(child, models);
                }
                return;
            }
            if (!element.isJsonObject()) {
                return;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("model") && object.get("model").isJsonPrimitive()) {
                ResourceLocation model = ResourceLocation.tryParse(object.get("model").getAsString());
                if (model != null) {
                    models.add(model);
                }
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!"model".equals(entry.getKey())) {
                    collectModels(entry.getValue(), models);
                }
            }
        }

        private static boolean isCrossModel(ResourceLocation model, Set<ResourceLocation> seen) {
            if (!seen.add(model)) {
                return false;
            }
            String path = model.getPath();
            if ("block/cross".equals(path) || "block/tinted_cross".equals(path)) {
                return true;
            }
            JsonObject json = readAsset(model.getNamespace(), "models/" + path + ".json");
            if (json == null || !json.has("parent") || !json.get("parent").isJsonPrimitive()) {
                return false;
            }
            ResourceLocation parent = ResourceLocation.tryParse(json.get("parent").getAsString());
            return parent != null && isCrossModel(parent, seen);
        }

        private static JsonObject readAsset(String namespace, String path) {
            String full = "assets/" + namespace + "/" + path;
            InputStream stream = FramedAdditions.class.getClassLoader().getResourceAsStream(full);
            if (stream == null) {
                ClassLoader context = Thread.currentThread().getContextClassLoader();
                if (context != null && context != FramedAdditions.class.getClassLoader()) {
                    stream = context.getResourceAsStream(full);
                }
            }
            if (stream == null) {
                stream = Block.class.getClassLoader().getResourceAsStream(full);
            }
            if (stream == null) {
                return null;
            }
            try (InputStream in = stream) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonElement element = JsonParser.parseString(text);
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }

        private static boolean isCrossPlant(Block source) {
            // Azalea, hanging roots, and spore blossom are not these classes and are not the cross model.
            return source instanceof FlowerBlock
                    || source instanceof SaplingBlock
                    || source instanceof DeadBushBlock
                    || source instanceof RootsBlock
                    || source instanceof FungusBlock
                    || source instanceof NetherSproutsBlock
                    || source instanceof SeagrassBlock
                    || source instanceof TallGrassBlock
                    || source instanceof DoublePlantBlock;
        }

        private static void registerItems(RegisterEvent event) {
            event.register(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MODID, "nbt_wand"), () -> {
                Item wand = new NbtWandItem();
                FramedRegistry.NBT_WAND = wand;
                return wand;
            });
            for (CopiedFramedBlock block : FramedRegistry.BLOCKS) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) {
                    continue;
                }
                Item item = new CopiedFramedItem(block);
                event.register(Registries.ITEM, id, () -> item);
                FramedRegistry.ITEMS.add(item);
                if (OAK_PLANKS.equals(id)) {
                    FramedRegistry.ICON = item;
                }
            }
            if (FramedRegistry.ICON == null && !FramedRegistry.ITEMS.isEmpty()) {
                FramedRegistry.ICON = FramedRegistry.ITEMS.get(0);
            }
        }

        private static void registerBlockEntity(RegisterEvent event) {
            if (FramedRegistry.BLOCKS.isEmpty()) {
                return;
            }

            event.register(Registries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(MODID, "framed_copy"), () -> {
                @SuppressWarnings("unchecked")
                BlockEntityType<FramedBlockEntity>[] holder = new BlockEntityType[1];
                holder[0] = BlockEntityType.Builder.<FramedBlockEntity>of(
                        (pos, state) -> new CopiedFramedBlockEntity(holder[0], pos, state),
                        FramedRegistry.BLOCKS.toArray(Block[]::new)
                ).build(null);
                FramedRegistry.BLOCK_ENTITY = holder[0];
                return holder[0];
            });
        }

        private static void registerTab(RegisterEvent event) {
            event.register(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath(MODID, "main"), () ->
                    CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.framed_additions"))
                            .icon(() -> FramedRegistry.ICON == null ? new ItemStack(Items.ITEM_FRAME) : new ItemStack(FramedRegistry.ICON))
                            .withSearchBar()
                            .displayItems((parameters, output) -> {
                                if (FramedRegistry.NBT_WAND != null) {
                                    output.accept(FramedRegistry.NBT_WAND);
                                }
                                for (CopiedFramedBlock block : FramedRegistry.uniqueShapeBlocks()) {
                                    output.accept(block);
                                }
                            })
                            .build()
            );
        }
    }

    @EventBusSubscriber(modid = MODID)
    private static final class SourceShapes {
        private SourceShapes() {
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            CopiedBlockType.enableSourceShapes();
        }

        @SubscribeEvent
        public static void onContainerClose(PlayerContainerEvent.Close event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                RedstoneStandIn.releaseScreen(player);
            }
        }

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                RedstoneStandIn.releaseScreen(player);
            }
        }

        @SubscribeEvent
        public static void onCropGrow(CropGrowEvent.Pre event) {
            if (framedPlantSite(event.getLevel(), event.getPos())) {
                event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
            }
        }

        @SubscribeEvent
        public static void onBonemeal(BonemealEvent event) {
            if (framedPlantSite(event.getLevel(), event.getPos())) {
                event.setCanceled(true);
            }
        }

        private static boolean framedPlantSite(LevelAccessor level, BlockPos pos) {
            if (level.getBlockState(pos).getBlock() instanceof CopiedFramedBlock) {
                return true;
            }
            return level.getBlockState(pos.below()).getBlock() instanceof CopiedFramedBlock;
        }
    }
}
