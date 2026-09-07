package com.malice.terminalcraft.block;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        System.out.println("Machine block visual contract tests: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
