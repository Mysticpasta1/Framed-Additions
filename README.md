# Framed Additions

Framed Additions adds framed block shapes for Minecraft and installed mods. Uncamouflaged blocks use the Framed Cube texture from FramedBlocks. Apply camouflage to give a shape the appearance of another block. Liquids, bubble columns, and blocks drawn only by special block entity renderers (such as signs, heads, banners, and chests) are excluded because their geometry cannot be copied as regular block models. A framed bell keeps the swinging bell.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.265 or newer in the NeoForge 21.1 series
- FramedBlocks 10.6.0 or newer, before 11.0

Install Framed Additions, FramedBlocks, and their dependencies in the Minecraft `mods` folder.

## Blocks

Browse the **Framed Additions** creative tab. Names describe the shape where possible, such as **Framed Slab**; other entries use the source block's name. Named shape categories register once across source variants, and the creative tab shows one entry for each collision shape. Every registered framed block has a stonecutter recipe, unless its source block is on the FramedBlocks blacklist. Blocks without a collision shape remain separate. Air, liquid blocks, and blocks drawn only by special renderers are excluded. The bell is copied.

Copied blocks retain supported source state variants, such as facing, half, and axis. Placement also keeps block entity data the source block writes, and that data is used when the copied model is drawn. Camouflage keeps the state of the block you apply: the facing and other properties chosen for that click, any blockstate properties stored on the item, and the item's block entity data. Rotating camouflage that is already applied leaves that data in place. Use camouflage on these blocks as you would on other FramedBlocks blocks.

Each distinct texture in a copied model can take its own camouflage. The first click applies to the texture you are looking at and uses the normal FramedBlocks camouflage slot. A later click on a different texture stores another camouflage block. Removing the first camouflage also clears the others. Each applied texture is mapped onto the whole texture of that part. Parts with no camouflage keep the original texture once any other part is camouflaged. With nothing applied, the block uses the framed texture.

The FramedBlocks wrench rotates an `axis` property through x, y, and z, the same way it rotates a framed pillar. Facing still rotates with the block.

The NBT Wand toggles inherited block entity data on one framed block. It is on by default. While it is on, a framed block keeps the full block entity snapshot of the block it copies: the entity tag, data components, and attachments. Camouflage stores the same snapshot from the item you apply. The block entity renderer for that block draws with this data, so a pot keeps its sherds, a lectern keeps its book, and a campfire keeps its items. While the wand is off, the copied model and the camouflage model ignore that data and the block entity renderer does not draw. Blockstate properties still apply. Craft the wand from a book above a stick. It is in the creative tab.

A framed copy gives off the light of the block state it copies. A lantern, glowstone, lit lamp, lit candle, or lit sea pickle lights the area, and the unlit state does not. A framed campfire keeps a block entity. It shows food placed on it and cooks that food while the copied state is lit. When the food finishes, the cooked item drops, the same as a campfire. Flint and steel or a fire charge lights it, and a shovel puts it out. Other copied blocks that tick, such as a furnace, keep ticking, and the result is saved on the framed block. Framed repeaters, comparators, levers, buttons, pressure plates, observers, lamps, dispensers, droppers, trapdoors, and fence gates keep their signal behavior. A framed sculk sensor and a framed calibrated sculk sensor hear vibrations and give off a redstone signal. A framed composter takes compostable items the same way a composter does: the first item adds a layer, later items add a layer by their compost chance, and an empty hand takes bone meal once it is ready. A framed stonecutter keeps the saw blade's original texture. A framed bell keeps its swinging bell when a different block is applied to the support. A fluid bucket does not apply camouflage. Using one on a framed cauldron fills or empties the cauldron.

A framed copy of a block that checks where it can survive can be placed on any block. A framed cactus damages an entity that touches it, the same as a cactus. A framed plant does not grow: cactus, sugar cane, bamboo, vines, kelp, chorus flowers, and the other copied plants stay at the size they were placed. Bone meal does not grow plants on a framed block, and a plant does not grow while the block under it is framed. Pointed dripstone changes thickness when another framed pointed dripstone of that same block is above or below it. A framed big dripleaf becomes the framed big dripleaf stem when another framed big dripleaf is placed above it.

Tinted camouflage keeps its color. A grass or leaf texture uses the biome tint, and a tinted overlay such as the grass side is drawn on top of the solid face. An empty right-click opens the copied block entity's screen, such as a furnace, smoker, hopper, or dispenser, and it opens an anvil or a grindstone. A framed lectern opens only while a book is on it. Item changes in that screen are saved back onto the framed block. The copied block is the real block while the screen is open, then it becomes the framed block again when the screen closes.

Blocks that use the cross model are never copied, including ones that are not plants. Flowers, saplings, grasses, roots, fungi, seagrass, and cobwebs are in that group. Azalea, hanging roots, and spore blossom stay available because their models are not the cross model. Crops and spawns are never copied: wheat, carrots, potatoes, beetroot, torchflowers, pitcher pods, nether wart, sweet berries, cocoa, pumpkin and melon stems, cave vines, frogspawn, turtle eggs, and sniffer eggs. Torches are never copied: standing torches, wall torches, soul torches, and redstone torches. Lily pads, redstone wire, ladders, brewing stands, and doors are never copied. Trapdoors are still copied.

A framed copy random-ticks when the block state it copies random-ticks. A framed campfire cooks on the block entity tick, the same as a campfire: every game tick while it is lit, and the cooked item drops when that cook finishes.

Source models can be excluded in `config/framed_additions-common.toml` under `modelBlacklist`. Each entry is a Java regular expression matched against the whole source block id (`namespace:path`) and the whole source model id (`namespace:block/path`). `.*_fence` skips every fence. `minecraft:block/stonecutter` skips that one model. The game must be restarted after the list changes. A pattern that is not valid Java regex is ignored and reported in the log.

`textureBlacklist` in the same file drops individual faces whose source texture matches. Each pattern is matched against the whole texture id (`minecraft:block/stonecutter_saw`) and the whole texture file (`minecraft:textures/block/stonecutter_saw.png`). `.*stonecutter_saw` removes the saw from the framed stonecutter and leaves the rest of the block. The block, item, and recipe are still registered. Reload resources after changing this list.

## Crafting

Put one **Framed Cube** in a stonecutter. Every framed block this mod adds is one of the results, unless its source block is on the FramedBlocks blacklist (`framedblocks:blacklisted`).

## Build

Build with Java 21:

```shell
./gradlew build
```

On Windows, run `gradlew.bat build`. The mod jar is created in `build/libs`.

## License

MIT. See [LICENSE](LICENSE).
