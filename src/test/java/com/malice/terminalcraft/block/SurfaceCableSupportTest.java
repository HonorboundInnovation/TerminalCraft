package com.malice.terminalcraft.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Headless contract checks for centered one-cable-per-face routing and color/channel mapping. */
public final class SurfaceCableSupportTest {
    private SurfaceCableSupportTest() {}

    public static void main(String[] args) throws Exception {
        com.malice.terminalcraft.testsupport.HeadlessMinecraftBootstrap.initialize();
        require(DyeColor.values().length == 16, "Minecraft must expose exactly sixteen cable colors");
        for (DyeColor color : DyeColor.values()) {
            require(SurfaceCableSupport.defaultChannel(color) == color.getId(),
                    "dye IDs must map directly to data/control channels");
            require(SurfaceCableSupport.dyeColor(color.getId()) == color,
                    "channel-to-color mapping must be reversible");
        }

        var floorCore = SurfaceCableSupport.centeredRunShape(Direction.UP, 0).bounds();
        require(floorCore.minX == 7.0D / 16.0D && floorCore.maxX == 9.0D / 16.0D
                        && floorCore.minZ == 7.0D / 16.0D && floorCore.maxZ == 9.0D / 16.0D,
                "an unconnected floor cable must be centered instead of occupying a matrix cell");
        int northEast = SurfaceCableRouting.port(Direction.NORTH) | SurfaceCableRouting.port(Direction.EAST);
        var floorTurn = SurfaceCableSupport.centeredRunShape(Direction.UP, northEast).bounds();
        require(floorTurn.minZ == 0.0D && floorTurn.maxX == 1.0D,
                "automatic floor routing must extend centered arms to exact block boundaries");
        int vertical = SurfaceCableRouting.port(Direction.DOWN) | SurfaceCableRouting.port(Direction.UP);
        var wallRun = SurfaceCableSupport.centeredRunShape(Direction.NORTH, vertical).bounds();
        require(wallRun.minY == 0.0D && wallRun.maxY == 1.0D,
                "automatic wall routing must support full centered vertical runs");
        require(!SurfaceCableSupport.centeredMarkerShape(Direction.WEST).isEmpty(),
                "ordinary-cable placement must expose one centered face marker");
        assertFaceHalf(Direction.UP, 0, 0, 0, 1, 0.5D, 1);
        assertFaceHalf(Direction.DOWN, 0, 0.5D, 0, 1, 1, 1);
        assertFaceHalf(Direction.NORTH, 0, 0, 0.5D, 1, 1, 1);
        assertFaceHalf(Direction.SOUTH, 0, 0, 0, 1, 1, 0.5D);
        assertFaceHalf(Direction.WEST, 0.5D, 0, 0, 1, 1, 1);
        assertFaceHalf(Direction.EAST, 0, 0, 0, 0.5D, 1, 1);

        int northSouth = SurfaceCableRouting.straight(Direction.UP, Direction.NORTH);
        int rightTurn = SurfaceCableRouting.forPlacement(Direction.UP, Direction.EAST,
                SurfaceCableRouting.port(Direction.SOUTH));
        require(SurfaceCableRouting.hasPort(northSouth, Direction.NORTH)
                        && SurfaceCableRouting.hasPort(northSouth, Direction.SOUTH)
                        && !SurfaceCableRouting.hasPort(northSouth, Direction.EAST),
                "floor straight routes must expose only reciprocal front/back ports");
        // Former row/matrix helpers remain only so 1.0.58-1.0.60 NBT can be read and collapsed.
        int allStraightPoints = 0;
        for (int lane = 0; lane < 4; lane++) {
            int points = SurfaceCableSupport.latticeMask(Direction.UP, lane, northSouth);
            require(Integer.bitCount(points) == 4, "each legacy row must migrate into four explicit points");
            allStraightPoints |= points;
        }
        require(allStraightPoints == 0xFFFF,
                "four legacy rows must migrate into all sixteen independently stored points");
        require(Integer.bitCount(SurfaceCableSupport.latticeMask(Direction.UP, 0, rightTurn)) == 4
                        && Integer.bitCount(SurfaceCableSupport.latticeMask(Direction.UP, 1, rightTurn)) == 3
                        && Integer.bitCount(SurfaceCableSupport.latticeMask(Direction.UP, 2, rightTurn)) == 2
                        && Integer.bitCount(SurfaceCableSupport.latticeMask(Direction.UP, 3, rightTurn)) == 1,
                "legacy turns must migrate into four-, three-, two-, or one-point paths");
        require(SurfaceCableRouting.sanitize(Direction.UP,
                        SurfaceCableRouting.port(Direction.UP) | SurfaceCableRouting.port(Direction.NORTH))
                        == SurfaceCableRouting.port(Direction.NORTH),
                "route persistence must reject ports normal to the mounting face");
        require(SurfaceCableRouting.planeMask(Direction.UP) == (SurfaceCableRouting.port(Direction.NORTH)
                        | SurfaceCableRouting.port(Direction.SOUTH) | SurfaceCableRouting.port(Direction.WEST)
                        | SurfaceCableRouting.port(Direction.EAST)),
                "legacy runs must migrate to the four in-plane ports without losing connectivity");
        require(!SurfaceCableSupport.bundledMarkerShape(Direction.NORTH).isEmpty(),
                "bundled cable placement preview must expose a centered face marker");
        for (Direction input : Direction.values()) {
            require(RedAlloyCapacitorBlock.outputForInput(input) == input.getOpposite(),
                    "capacitor input " + input.getName() + " must map only to its opposing face");
        }
        require(RedAlloyCapacitorBlock.restoredStrength(0) == 0
                        && RedAlloyCapacitorBlock.restoredStrength(1) == 15
                        && RedAlloyCapacitorBlock.restoredStrength(15) == 15,
                "capacitor must convert any nonzero input into restored strength 15");

        require(Files.exists(Path.of("src/main/resources/data/terminalcraft/recipes/cable_dyeing.json")),
                "survival cable recoloring recipe must be packaged");
        require(Files.exists(Path.of("src/main/resources/data/terminalcraft/recipes/red_alloy_wire.json"))
                        && Files.exists(Path.of("src/main/resources/data/terminalcraft/recipes/network_cable.json")),
                "unshielded Red Alloy Wire and base Network Cable must both have survival recipes");
        require(Files.exists(Path.of("src/main/resources/data/terminalcraft/recipes/red_alloy_capacitor.json"))
                        && Files.exists(Path.of("src/main/resources/data/terminalcraft/loot_tables/blocks/"
                        + "red_alloy_capacitor.json")),
                "the Red Alloy Capacitor must be craftable and must drop itself");
        require(Files.exists(Path.of("src/main/resources/data/terminalcraft/recipes/bundled_network_cable.json")),
                "bundled network trunk recipe must be packaged");
        String language = Files.readString(Path.of("src/main/resources/assets/terminalcraft/lang/en_us.json"));
        require(language.contains("Bundled Red Alloy Cable") && language.contains("Bundled Network Cable"),
                "both channel-preserving trunk families must be named for players");
        require(language.contains("preview.terminalcraft.cable.red_alloy")
                        && language.contains("preview.terminalcraft.cable.red_alloy_unshielded")
                        && language.contains("preview.terminalcraft.cable.network_bundle")
                        && language.contains("Unshielded Red Alloy Wire"),
                "placement preview readouts must be localized");

        assertDetailedNeutralTintTexture(Path.of("src/main/resources/assets/terminalcraft/textures/block/"
                + "red_alloy_wire_custom.png"));
        assertDetailedNeutralTintTexture(Path.of("src/main/resources/assets/terminalcraft/textures/block/"
                + "network_cable_custom.png"));
        assertDetailedNeutralTintTexture(Path.of("src/main/resources/assets/terminalcraft/textures/item/"
                + "red_alloy_wire_custom.png"));
        assertDetailedNeutralTintTexture(Path.of("src/main/resources/assets/terminalcraft/textures/item/"
                + "network_cable_custom.png"));

        Path bundlePlate = Path.of("src/main/resources/assets/terminalcraft/textures/gui/guide/bundled_cable_families.png");
        require(ImageIO.read(bundlePlate.toFile()).getHeight() == 512,
                "field-manual bundled-cable plate must retain its tested render dimensions");
        require(Files.readString(Path.of("src/main/resources/assets/terminalcraft/models/item/"
                        + "bundled_network_cable.json")).contains("bundled_network_cable_inventory"),
                "bundled network item must use its visually distinct model family");
        require(Files.readString(Path.of("src/main/resources/assets/terminalcraft/models/block/"
                        + "bundled_network_cable_up_core.json")).contains("bundled_network_cable_custom"),
                "bundled network world model must use its own navy/cyan texture");
        String previewSource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/client/"
                + "CablePlacementPreviewRenderer.java"));
        require(previewSource.contains("RenderHighlightEvent.Block")
                        && previewSource.contains("VanillaGuiOverlay.CROSSHAIR")
                        && !previewSource.contains("POINTS_PER_FACE")
                        && previewSource.contains("centeredMarkerShape"),
                "client preview must render one centered cable target and no matrix grid");
        String redEntitySource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/blockentity/"
                + "RedAlloyWireBlockEntity.java"));
        String networkEntitySource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/blockentity/"
                + "NetworkCableBlockEntity.java"));
        require(redEntitySource.contains("RunRoutes") && networkEntitySource.contains("RunRoutes")
                        && redEntitySource.contains("RunShielded")
                        && redEntitySource.contains("collapseToSingleRuns")
                        && networkEntitySource.contains("collapseToSingleRuns")
                        && redEntitySource.contains("latticeMask(face, lane, legacyRoute)")
                        && networkEntitySource.contains("latticeMask(face, lane, legacyRoute)"),
                "both cable families must retain legacy readers but collapse old matrices to one run per face");
        String creativeSource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/TerminalCraftMod.java"));
        require(creativeSource.contains("event.accept(com.malice.terminalcraft.registry.ModRegistries."
                        + "RED_ALLOY_WIRE_ITEM.get());"),
                "the unshielded Red Alloy stack must remain visible alongside all colored variants");
        String redRenderer = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/client/"
                + "RedAlloyWireBlockEntityRenderer.java"));
        String networkRenderer = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/client/"
                + "NetworkCableBlockEntityRenderer.java"));
        require(redRenderer.contains("renderSingleBlock(")
                        && networkRenderer.contains("renderSingleBlock(")
                        && !redRenderer.contains("SurfaceCableGeometryRenderer")
                        && !networkRenderer.contains("SurfaceCableGeometryRenderer"),
                "placed cables must use baked route models instead of overlapping textured boxes");
        String redBlockSource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/block/"
                + "RedAlloyWireBlock.java"));
        String networkBlockSource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/block/"
                + "NetworkCableBlock.java"));
        String bundledBlockSource = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/block/"
                + "BundledCableBlock.java"));
        require(!redBlockSource.contains("IntegerProperty.create(\"point\", 0, 15)")
                        && !networkBlockSource.contains("IntegerProperty.create(\"point\", 0, 15)")
                        && !redBlockSource.contains("IntegerProperty.create(\"lane\"")
                        && !networkBlockSource.contains("IntegerProperty.create(\"lane\"")
                        && !redBlockSource.contains("pointForHit")
                        && !networkBlockSource.contains("pointForHit")
                        && redBlockSource.contains("centeredRunShape")
                        && networkBlockSource.contains("centeredRunShape")
                        && redBlockSource.contains("wire.hasFace(face)")
                        && networkBlockSource.contains("cable.hasFace(face)")
                        && redBlockSource.contains("placementFace")
                        && networkBlockSource.contains("placementFace"),
                "both families must admit and render only one centered cable per face");
        require(redBlockSource.contains("SurfaceCableSupport.faceHalfShape")
                        && networkBlockSource.contains("SurfaceCableSupport.faceHalfShape")
                        && bundledBlockSource.contains("SurfaceCableSupport.faceHalfShape"),
                "red alloy, network, and bundled cable selection must use face-oriented half blocks");
        String guide = Files.readString(Path.of("docs/TERMINALCRAFT_GUIDE.md"));
        require(guide.contains("one centered cable per face")
                        && guide.contains("automatically connects")
                        && guide.contains("half-slab or half-wall selection volume"),
                "the in-game Guide Book must teach the restored single-cable routing model");
        System.out.println("Surface cable support tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFaceHalf(Direction face, double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        var bounds = SurfaceCableSupport.faceHalfShape(face).bounds();
        require(bounds.minX == minX && bounds.minY == minY && bounds.minZ == minZ
                        && bounds.maxX == maxX && bounds.maxY == maxY && bounds.maxZ == maxZ,
                face.getName() + " cable hitbox must occupy exactly its mounted half block");
    }

    private static void assertDetailedNeutralTintTexture(Path path) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        require(image.getWidth() == 16 && image.getHeight() == 16,
                path + " must remain a native 16x16 Minecraft texture");
        java.util.Set<Integer> shades = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;
                int channelSpread = Math.max(red, Math.max(green, blue))
                        - Math.min(red, Math.min(green, blue));
                require((pixel >>> 24) == 0xFF && channelSpread <= 16,
                        path + " must be an opaque near-neutral base for dye tinting");
                shades.add(pixel);
            }
        }
        require(shades.size() >= 32,
                path + " must retain enough pixel variation to avoid flat slab rendering");
    }
}
