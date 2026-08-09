package com.malice.terminalcraft.client;

/** Headless checks for source-preserving ladder projection and editing operations. */
public final class PlcLadderModelTest {
    private PlcLadderModelTest() {}

    public static void main(String[] args) {
        PlcLadderModel model = PlcLadderModel.fromSource("SCAN 1\nIN START REDSTONE NORTH\n"
                + "OUT MOTOR REDSTONE EAST\nRUNG MOTOR = START\nRUNG AUX = ON");
        require(model.rungs().size() == 2, "rungs are projected from source");
        require(model.addContact(0, "START"), "contact can be added");
        require(model.rungs().get(0).expression().contains("AND START"), "contact updates expression");
        require(model.moveRung(0, 1), "rung can be reordered");
        String source = model.toSource();
        require(source.contains("SCAN 1") && source.contains("RUNG AUX"), "declarations survive projection");
        require(source.indexOf("RUNG AUX") < source.indexOf("RUNG MOTOR"), "reorder is serialized");
        System.out.println("PLC ladder model tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
