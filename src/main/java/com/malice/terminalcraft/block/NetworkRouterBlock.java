package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.NetworkRouterBlockEntity;
import com.malice.terminalcraft.network.WiredNetworkTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Collectors;

/** Routing node with persistent per-face logical-network assignments and topology diagnostics. */
public class NetworkRouterBlock extends BaseEntityBlock implements WiredNetworkNode {
    public NetworkRouterBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .strength(2.0f, 4.0f)
                .requiresCorrectToolForDrops());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkRouterBlockEntity(pos, state);
    }

    @Override
    public boolean routesWiredTraffic(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof NetworkRouterBlockEntity router)) {
            return InteractionResult.PASS;
        }
        Direction face = hit.getDirection();
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (held.is(Items.REDSTONE_TORCH)) {
                boolean enabled = !router.isInterfaceEnabled(face);
                router.setInterfaceEnabled(face, enabled);
                player.displayClientMessage(Component.literal(
                        "Router " + face.getName() + " interface: " + (enabled ? "enabled" : "disabled")), true);
            } else if (held.isEmpty()) {
                router.setInterfaceNetwork(face, "");
                player.displayClientMessage(Component.literal(
                        "Router " + face.getName() + " interface: automatic"), true);
            } else if (held.hasCustomHoverName()) {
                String requested = held.getHoverName().getString();
                if (router.setInterfaceNetwork(face, requested)) {
                    player.displayClientMessage(Component.literal(
                            "Router " + face.getName() + " interface: "
                                    + router.getInterfaceNetwork(face)), true);
                } else {
                    player.displayClientMessage(Component.literal(
                            "Invalid RedNet network name: " + requested), true);
                }
            } else {
                player.displayClientMessage(Component.literal(
                        "Rename an item to a RedNet network name, then shift-use it on this face"), true);
            }
            return InteractionResult.CONSUME;
        }

        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level instanceof ServerLevel serverLevel) {
            WiredNetworkTopology.Component component = WiredNetworkTopology.inspect(serverLevel, pos);
            WiredNetworkTopology.RouterView view = WiredNetworkTopology.routerInterfaces(serverLevel, pos);
            String interfaces = WiredNetworkTopology.routerAttachments(serverLevel, pos).stream().limit(6)
                    .map(attachment -> attachment.face().getName()
                            + (attachment.enabled() ? "" : "(disabled)") + "="
                            + (attachment.networkName().isEmpty() ? "automatic" : attachment.networkName())
                            + "->" + attachment.subnet().displayName())
                    .collect(Collectors.joining(", "));
            player.displayClientMessage(Component.literal(
                    "Router network: nodes=" + component.nodeCount()
                            + " modems=" + component.modemCount()
                            + " router_nodes=" + view.routerNodeCount()
                            + " interfaces=" + view.interfaces().size()
                            + (interfaces.isEmpty() ? "" : " [" + interfaces + "]")
                            + (component.truncated() || view.truncated() ? " (scan limit reached)" : "")), false);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
