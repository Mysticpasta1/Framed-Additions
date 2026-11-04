# Framed Additions

Framed Additions adds framed block shapes for Minecraft and installed mods. Uncamouflaged blocks use the Framed Cube texture from FramedBlocks. Apply camouflage to give a shape the appearance of another block. Liquids, bubble columns, and blocks drawn by special block entity renderers (such as signs, heads, banners, and bells) are excluded because their geometry cannot be copied as regular block models.

## Requirements

- Minecraft 1.20.1
- Minecraft Forge 47.4.28 or newer in the Forge 47 series
- FramedBlocks 9.4.3 or newer

Install Framed Additions, FramedBlocks, and their dependencies in the Minecraft `mods` folder.

## Blocks

Browse the **Framed Additions** creative tab. Names describe the shape where possible, such as **Framed Slab**; other entries use the source block's name. Named shape categories register once across source variants, and the creative tab and recipes include only one entry for each collision shape. Blocks without a collision shape remain separate. Air, liquid blocks, and special-renderer blocks are excluded.

Copied blocks retain supported source state variants. Use camouflage on them as you would on other FramedBlocks blocks.

## Crafting

Use a **Framed Cube** in a stonecutter to craft the available shapes. Recipes are generated from the loaded mod set, with duplicate shapes represented once.

## Build

Build with Java 17:

```shell
./gradlew build
```

On Windows, run `gradlew.bat build`. The mod jar is created in `build/libs`.

## License

MIT. See [LICENSE](LICENSE).
