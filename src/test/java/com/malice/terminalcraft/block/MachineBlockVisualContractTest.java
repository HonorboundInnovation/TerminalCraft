package com.malice.terminalcraft.block;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Regression coverage for inset machine models that must not cull neighboring block faces. */
public final class MachineBlockVisualContractTest {
    private MachineBlockVisualContractTest() {}

    public static void main(String[] args) throws IOException {
        // Direct block construction is unavailable after vanilla's headless bootstrap freezes its
        // intrusive registry, so keep this asset/code contract test independent of that registry.
        String source = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/block/"
                + "ProgrammableLogicControllerBlock.java"));
        check(source.contains(".noOcclusion()"),
                "inset PLC cabinet must preserve adjacent block faces");
        String rack = Files.readString(Path.of("src/main/java/com/malice/terminalcraft/block/ServerRackBlock.java"));
        check(rack.contains(".noOcclusion()"), "recessed rack must preserve neighboring faces");
        for (String model : new String[]{"network_router", "wireless_display_link", "sensor_compact", "sensor_array_compact", "materializer", "network_access_storage", "refined_storage_bridge", "applied_energistics_bridge",
                "disk_drive", "programmable_logic_controller", "server_rack", "server_rack_lower_server",
                "server_rack_upper_server", "server_rack_lower_router", "server_rack_upper_router"}) {
            var json = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/terminalcraft/models/block/" + model + ".json"))).getAsJsonObject();
            for (var elementValue : json.getAsJsonArray("elements")) {
                var element = elementValue.getAsJsonObject();
                var from = element.getAsJsonArray("from");
                var to = element.getAsJsonArray("to");
                for (int axis = 0; axis < 3; axis++) {
                    check(from.get(axis).getAsDouble() >= 0 && to.get(axis).getAsDouble() <= 16
                            && from.get(axis).getAsDouble() < to.get(axis).getAsDouble(),
                            model + " geometry must fit the cabinet block");
                }
                check(element.getAsJsonObject("faces").size() == 6,
                        model + " must have solid elements, not single floating faces");
            }
        }
        var itemRoot = Path.of("src/main/resources/assets/terminalcraft/models/item");
        var blade = com.google.gson.JsonParser.parseString(Files.readString(
                itemRoot.resolve("rack_blade.json"))).getAsJsonObject();
        check(blade.getAsJsonArray("elements").size() == 3,
                "loose rack blade must have a body, front panel and rear connector");
        for (var value : blade.getAsJsonArray("elements")) {
            var element = value.getAsJsonObject();
            check(element.getAsJsonObject("faces").size() == 6, "blade parts must be solid");
            for (int axis = 0; axis < 3; axis++) {
                double low = element.getAsJsonArray("from").get(axis).getAsDouble();
                double high = element.getAsJsonArray("to").get(axis).getAsDouble();
                check(low >= 0 && high <= 16 && low < high, "blade geometry must fit item bounds");
            }
        }
        for (String item : new String[]{"server_blade", "router_blade"}) {
            var model = com.google.gson.JsonParser.parseString(Files.readString(
                    itemRoot.resolve(item + ".json"))).getAsJsonObject();
            check(model.get("parent").getAsString().equals("terminalcraft:item/rack_blade"),
                    "rack blades must share their cartridge geometry");
            String front = model.getAsJsonObject("textures").get("front").getAsString();
            check(Files.isRegularFile(Path.of("src/main/resources/assets/terminalcraft/textures/"
                    + front.substring("terminalcraft:".length()) + ".png")), "blade front artwork must exist");
        }
        bundledNetworkMatchesBundledRedAlloy();
        ordinaryCablesUseDetailedBakedModels();
        System.out.println("Machine block visual contract tests: OK");
    }

    private static void bundledNetworkMatchesBundledRedAlloy() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/malice/terminalcraft");
        String blockSource = Files.readString(sourceRoot.resolve("block/BundledNetworkCableBlock.java"));
        check(blockSource.contains("return RenderShape.MODEL;"),
                "bundled network cable must render through its Red Alloy-equivalent block model");

        String rendererSource = Files.readString(sourceRoot.resolve("client/NetworkCableBlockEntityRenderer.java"));
        check(rendererSource.contains("renderSingleBlock(")
                        && rendererSource.contains("if (run.face() == primary) continue;"),
                "bundled network multipart faces must use the bundled Red Alloy model renderer");

        Path models = Path.of("src/main/resources/assets/terminalcraft/models/block");
        int compared = 0;
        try (var redModels = Files.list(models)) {
            for (Path red : redModels.filter(path -> path.getFileName().toString()
                    .startsWith("bundled_cable_")).toList()) {
                String redName = red.getFileName().toString();
                Path network = models.resolve(redName.replace("bundled_cable_", "bundled_network_cable_"));
                check(Files.exists(network), "missing bundled network model parity file: " + network);
                String expected = Files.readString(red)
                        .replace("bundled_cable", "bundled_network_cable");
                check(expected.equals(Files.readString(network)),
                        "bundled network model geometry differs from bundled Red Alloy: " + network);
                compared++;
            }
        }
        check(compared >= 30, "expected the complete bundled cable model family to be compared");

        Path blockstates = Path.of("src/main/resources/assets/terminalcraft/blockstates");
        String expectedState = Files.readString(blockstates.resolve("bundled_cable.json"))
                .replace("bundled_cable", "bundled_network_cable");
        check(expectedState.equals(Files.readString(blockstates.resolve("bundled_network_cable.json"))),
                "bundled network multipart blockstate must match bundled Red Alloy");

        Path textures = Path.of("src/main/resources/assets/terminalcraft/textures");
        BufferedImage blockTexture = ImageIO.read(textures.resolve("block/bundled_network_cable_custom.png").toFile());
        BufferedImage itemTexture = ImageIO.read(textures.resolve("item/bundled_network_cable_custom.png").toFile());
        check(blockTexture != null && blockTexture.getWidth() == 16 && blockTexture.getHeight() == 16,
                "bundled network block texture must be an exact 16x16 Minecraft texture");
        check(itemTexture != null && itemTexture.getWidth() == 16 && itemTexture.getHeight() == 16,
                "bundled network item texture must be an exact 16x16 Minecraft texture");
        check(Arrays.equals(blockTexture.getRGB(0, 0, 16, 16, null, 0, 16),
                        itemTexture.getRGB(0, 0, 16, 16, null, 0, 16)),
                "bundled network block and item textures must stay identical");
    }

    private static void ordinaryCablesUseDetailedBakedModels() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/malice/terminalcraft");
        for (String block : new String[]{"RedAlloyWireBlock.java", "NetworkCableBlock.java"}) {
            String source = Files.readString(sourceRoot.resolve("block").resolve(block));
            check(source.contains("return RenderShape.MODEL;"),
                    block + " must let its primary face use the baked multipart model");
        }

        for (String renderer : new String[]{"RedAlloyWireBlockEntityRenderer.java",
                "NetworkCableBlockEntityRenderer.java"}) {
            String source = Files.readString(sourceRoot.resolve("client").resolve(renderer));
            check(source.contains("renderSingleBlock(")
                            && source.contains("if (run.face() == primary) continue;")
                            && !source.contains("SurfaceCableGeometryRenderer")
                            && !source.contains("renderColoredRun"),
                    renderer + " must render only secondary faces as baked models without box overlap");
        }

        String modSource = Files.readString(sourceRoot.resolve("TerminalCraftMod.java"));
        check(modSource.contains("RegisterColorHandlersEvent.Block")
                        && modSource.contains("CableColorHandlers.redAlloy")
                        && modSource.contains("CableColorHandlers.network"),
                "dye-selected baked cable models must retain Red Alloy and network block tinting");

        Path textures = Path.of("src/main/resources/assets/terminalcraft/textures");
        assertDetailedTexturePair(textures, "red_alloy_wire_custom.png");
        assertDetailedTexturePair(textures, "network_cable_custom.png");
    }

    private static void assertDetailedTexturePair(Path textures, String name) throws IOException {
        BufferedImage block = ImageIO.read(textures.resolve("block").resolve(name).toFile());
        BufferedImage item = ImageIO.read(textures.resolve("item").resolve(name).toFile());
        check(block != null && block.getWidth() == 16 && block.getHeight() == 16,
                name + " block texture must be an exact 16x16 Minecraft texture");
        check(item != null && item.getWidth() == 16 && item.getHeight() == 16,
                name + " item texture must be an exact 16x16 Minecraft texture");
        int[] blockPixels = block.getRGB(0, 0, 16, 16, null, 0, 16);
        int[] itemPixels = item.getRGB(0, 0, 16, 16, null, 0, 16);
        // The owner-approved ordinary cables deliberately use solid dye masks, unlike
        // the detailed bundled trunks. Their block and inventory silhouettes can differ.
        check(Arrays.stream(blockPixels).anyMatch(pixel -> (pixel >>> 24) != 0)
                        && Arrays.stream(itemPixels).anyMatch(pixel -> (pixel >>> 24) != 0),
                name + " block and item tint masks must have visible pixels");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
