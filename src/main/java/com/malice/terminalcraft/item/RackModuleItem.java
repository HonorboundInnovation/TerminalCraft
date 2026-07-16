package com.malice.terminalcraft.item;

import com.malice.terminalcraft.server.RackModuleType;
import net.minecraft.world.item.Item;

import java.util.Objects;

/** A slab-height blade installed into one of a rack block's two bays. */
public final class RackModuleItem extends Item {
    private final RackModuleType moduleType;

    public RackModuleItem(RackModuleType moduleType) {
        super(new Item.Properties().stacksTo(16));
        if (moduleType == RackModuleType.EMPTY) {
            throw new IllegalArgumentException("an empty bay is not an item");
        }
        this.moduleType = Objects.requireNonNull(moduleType, "moduleType");
    }

    public RackModuleType moduleType() {
        return moduleType;
    }
}
