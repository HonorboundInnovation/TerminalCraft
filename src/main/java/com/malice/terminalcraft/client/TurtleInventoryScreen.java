package com.malice.terminalcraft.client;

import com.malice.terminalcraft.menu.TurtleInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class TurtleInventoryScreen extends AbstractContainerScreen<TurtleInventoryMenu> {
    public TurtleInventoryScreen(TurtleInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 212;
        imageHeight = 236;
        inventoryLabelX = 26;
        inventoryLabelY = 141;
    }

    @Override protected void init() {
        super.init();
        button("<", 8, 20, 0, "Previous selected slot");
        button(">", 32, 20, 1, "Next selected slot");
        button("Refuel 1", 60, 80, 2, "Consume one fuel item from the selected slot");
    }

    private void button(String label, int x, int width, int id, String hint) {
        addRenderableWidget(Button.builder(Component.literal(label), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id))
                .bounds(leftPos + x, topPos + 118, width, 20)
                .tooltip(Tooltip.create(Component.literal(hint))).build());
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff17251d);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xff283c30);
        for (int index = 0; index < menu.slots.size(); index++) {
            var slot = menu.slots.get(index);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17,
                    index == menu.selectedSlot() ? 0xffecc86a : 0xff668271);
            graphics.fill(x, y, x + 16, y + 16, 0xff111d16);
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xffe4eee7, false);
        graphics.drawString(font, "Fuel: " + (menu.unlimited() ? "unlimited" : menu.fuel()), 8, 16, 0xffb5d8c0, false);
        graphics.drawString(font, "Selected slot: " + (menu.selectedSlot() + 1), 8, 104, 0xffecc86a, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xffe4eee7, false);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
