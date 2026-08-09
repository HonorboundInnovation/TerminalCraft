package com.malice.terminalcraft.block;

import com.malice.terminalcraft.testsupport.HeadlessMinecraftBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Focused optional-mod-safe wrench recognition coverage. */
public final class WireInteractionSupportTest {
    private WireInteractionSupportTest() {}

    public static void main(String[] args) {
        HeadlessMinecraftBootstrap.initialize();
        check(WireInteractionSupport.acceptsWrenchSignals(false, false, true, false),
                "Forge wrench tool actions must enable inspection");
        check(WireInteractionSupport.acceptsWrenchSignals(true, false, false, false),
                "Forge wrench tags must enable inspection, including Create's wrench");
        check(WireInteractionSupport.acceptsWrenchSignals(false, true, false, false),
                "common wrench tags must enable inspection");
        check(!WireInteractionSupport.isWrench(ItemStack.EMPTY),
                "empty hands must not consume interaction or conflict with Carry On");
        check(!WireInteractionSupport.isWrench(new ItemStack(Items.STICK)),
                "ordinary held items must pass through untouched");
        System.out.println("Wire interaction support tests: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
