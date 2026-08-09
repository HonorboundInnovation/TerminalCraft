package com.malice.terminalcraft.client;

import com.malice.terminalcraft.block.VideoCableBlock;
import com.malice.terminalcraft.blockentity.VideoCableBlockEntity;
import com.malice.terminalcraft.blockentity.WirelessDisplayLinkBlockEntity;
import com.malice.terminalcraft.menu.DisplayDiagnosticsMenu;
import com.malice.terminalcraft.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Compact in-world display transport diagnostics and configuration screen. */
public final class DisplayDiagnosticsScreen extends AbstractContainerScreen<DisplayDiagnosticsMenu> {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 220;
    private EditBox channel;
    private Button roleButton;
    private String notice = "";

    public DisplayDiagnosticsScreen(DisplayDiagnosticsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_WIDTH;
        imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;
        if (menu.isLink()) {
            channel = new EditBox(font, leftPos + 18, topPos + 55, 218, 18, Component.literal("Channel"));
            channel.setMaxLength(48);
            channel.setValue(currentChannel());
            channel.setResponder(value -> notice = "Unsaved channel edit");
            addRenderableWidget(channel);
            roleButton = addRenderableWidget(Button.builder(Component.literal(roleLabel()), button ->
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0))
                    .bounds(leftPos + 246, topPos + 53, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Pair / Arm"), button ->
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 1))
                    .bounds(leftPos + 18, topPos + 86, 104, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Clear"), button ->
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 2))
                    .bounds(leftPos + 128, topPos + 86, 70, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Apply Channel"), button -> applyChannel())
                    .bounds(leftPos + 204, topPos + 86, 138, 20).build());
        }
    }

    private void applyChannel() {
        if (channel == null) return;
        ModNetwork.sendDisplayConfig(menu.containerId, menu.targetPosition(), channel.getValue(), currentSource());
        notice = "Configuration sent";
    }

    private String currentChannel() {
        if (minecraft == null || minecraft.level == null) return "";
        if (minecraft.level.getBlockEntity(menu.targetPosition()) instanceof WirelessDisplayLinkBlockEntity link) {
            return link.channel();
        }
        return "";
    }

    private boolean currentSource() {
        return minecraft != null && minecraft.level != null
                && minecraft.level.getBlockEntity(menu.targetPosition()) instanceof WirelessDisplayLinkBlockEntity link
                && link.isSource();
    }

    private String roleLabel() { return currentSource() ? "Role: SOURCE" : "Role: RECEIVER"; }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (roleButton != null) roleButton.setMessage(Component.literal(roleLabel()));
        if (channel != null && !channel.isFocused()) channel.setValue(currentChannel());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos, top = topPos, right = left + imageWidth, bottom = top + imageHeight;
        graphics.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF080A0D);
        graphics.fill(left, top, right, bottom, 0xFF111820);
        graphics.fill(left, top, right, top + 28, 0xFF1E3A4F);
        graphics.drawString(font, menu.isLink() ? "DISPLAY LINK // CONFIGURATION" : "VIDEO CABLE // DIAGNOSTICS",
                left + 12, top + 9, 0xFFE7F4FF, false);
        if (menu.isLink()) renderLink(graphics, left, top);
        else renderCable(graphics, left, top);
        graphics.drawString(font, notice, left + 18, bottom - 25, 0xFF8EA8B8, false);
    }

    private void renderLink(GuiGraphics graphics, int left, int top) {
        if (!(minecraft.level.getBlockEntity(menu.targetPosition()) instanceof WirelessDisplayLinkBlockEntity link)) return;
        graphics.drawString(font, "CHANNEL", left + 18, top + 42, 0xFF8EA8B8, false);
        graphics.drawString(font, link.isSource() ? "SOURCE HOST" : "MONITOR RECEIVER", left + 18, top + 122, 0xFF9DE6B0, false);
        graphics.drawString(font, "Host attached: " + (link.attachedHost() != null), left + 18, top + 141, 0xFFC8D7DF, false);
        graphics.drawString(font, "Monitor attached: " + (link.attachedMonitor() != null), left + 18, top + 157, 0xFFC8D7DF, false);
        graphics.drawString(font, "Same-dimension pairing only; refresh interval: 4 ticks", left + 18, top + 183, 0xFF718A99, false);
    }

    private void renderCable(GuiGraphics graphics, int left, int top) {
        if (!(minecraft.level.getBlockEntity(menu.targetPosition()) instanceof VideoCableBlockEntity cable)) return;
        graphics.drawString(font, "ROUTED COMPONENT", left + 18, top + 44, 0xFF8EA8B8, false);
        StringBuilder connections = new StringBuilder("Connections: ");
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (cable.getBlockState().getValue(VideoCableBlock.property(direction))) {
                if (connections.length() > 13) connections.append(", ");
                connections.append(direction.getName());
            }
        }
        graphics.drawString(font, connections.toString(), left + 18, top + 70, 0xFFC8D7DF, false);
        graphics.drawString(font, "Six-direction routing", left + 18, top + 98, 0xFF9DE6B0, false);
        graphics.drawString(font, "Component limit: 2,048 cable nodes", left + 18, top + 117, 0xFFC8D7DF, false);
        graphics.drawString(font, "Display cells only; no redstone or RedNet traffic", left + 18, top + 145, 0xFF718A99, false);
        graphics.drawString(font, "Add a terminal/PLC and monitor at cable endpoints", left + 18, top + 164, 0xFF718A99, false);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public boolean isPauseScreen() { return false; }
}
