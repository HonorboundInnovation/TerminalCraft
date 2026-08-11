package com.malice.terminalcraft.sensor;

import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkRouterBlockEntity;
import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.blockentity.StandaloneSensorBlockEntity;
import com.malice.terminalcraft.network.RednetNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Server-side standard-capability and vanilla-state sensor implementation. */
public final class SensorProbe {
    private static final double ENTITY_RADIUS = 4.0;
    private static final int MAX_ENTITY_MATCHES = 256;
    /** Prevents mutually-facing Redstone Sensors from recursively sampling one another forever. */
    private static final ThreadLocal<Set<BlockPos>> REDSTONE_PROBE_STACK =
            ThreadLocal.withInitial(HashSet::new);

    private SensorProbe() {}

    public static SensorReading read(ServerLevel level, BlockPos sensorPos,
                                     SensorChannel channel, long gameTime) {
        if (level == null || sensorPos == null || channel == null) {
            return SensorReading.unavailable("unknown", SensorKind.BLOCK_STATE, "value",
                    SensorQuality.UNAVAILABLE, "sensor context unavailable", Math.max(0, gameTime));
        }
        BlockPos target = targetPosition(sensorPos, channel.target());
        if (!level.hasChunkAt(target)) {
            return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.CHUNK_UNLOADED, "target chunk is not loaded", gameTime);
        }
        try {
            if (channel.kind() == SensorKind.CHEMICAL) {
                BlockEntity chemicalTarget = level.getBlockEntity(target);
                if (chemicalTarget != null) {
                    java.util.Optional<com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry.ChemicalStorage>
                            chemicalStorage = com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry
                            .resolve(chemicalTarget, accessSide(channel.target()));
                    if (chemicalStorage.isPresent()) {
                        return chemical(channel, chemicalStorage.orElseThrow(), gameTime);
                    }
                }
            }
            java.util.Optional<SensorReading> optional =
                    com.malice.terminalcraft.integration.OptionalSensorProbeRegistry.read(
                            level, target, accessSide(channel.target()), channel, gameTime);
            if (optional.isPresent()) return optional.orElseThrow();
            return switch (channel.kind()) {
                case REDSTONE -> redstone(level, sensorPos, target, channel, gameTime);
                case BLOCK_STATE -> blockState(level, target, channel, gameTime);
                case INVENTORY -> inventory(level, target, accessSide(channel.target()), channel, gameTime);
                case FLUID -> fluid(level, target, accessSide(channel.target()), channel, gameTime);
                case ENERGY -> energy(level, target, accessSide(channel.target()), channel, gameTime);
                case ENTITY -> entity(level, target, channel, gameTime);
                case MACHINE -> machine(level, target, channel, gameTime);
                case ENVIRONMENT -> environment(level, target, channel, gameTime);
                case NETWORK -> network(level, target, channel, gameTime);
                case KINETIC -> kinetic(level, target, channel, gameTime);
                case CHEMICAL -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                        SensorQuality.UNSUPPORTED,
                        "no installed optional integration exposes a chemical capability", gameTime);
            };
        } catch (RuntimeException exception) {
            return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.PARTIAL, "probe failed safely", gameTime);
        }
    }

    private static SensorReading chemical(SensorChannel channel,
            com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry.ChemicalStorage storage,
            long time) {
        long amount = 0;
        long capacity = 0;
        int tanks = 0;
        String unit = "";
        boolean mixedUnits = false;
        String selector = channel.selector() == null ? "" : channel.selector();
        List<com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry.Tank> snapshot =
                List.copyOf(storage.snapshot());
        for (int index = 0; index < Math.min(snapshot.size(), DeviceValueLimit.CHEMICAL_TANKS); index++) {
            com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry.Tank tank = snapshot.get(index);
            if (!matchesChemical(selector, tank)) continue;
            tanks++;
            if (unit.isEmpty()) unit = tank.unit();
            else if (!unit.equals(tank.unit())) mixedUnits = true;
            amount = saturatingAdd(amount, tank.amount());
            capacity = saturatingAdd(capacity, tank.capacity());
        }
        String aggregateUnit = mixedUnits || unit.isEmpty() ? "units" : unit;
        return switch (channel.metric()) {
            case "amount", "stored" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    amount, aggregateUnit, time);
            case "capacity" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    capacity, aggregateUnit, time);
            case "fill", "fill_percent" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    capacity <= 0 ? 0 : amount * 100.0 / capacity, "percent", time);
            case "tanks" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    tanks, "tanks", time);
            case "present" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    amount > 0 ? 1 : 0, "boolean", time);
            default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.UNSUPPORTED, "chemical metric is unsupported", time);
        };
    }

    private static boolean matchesChemical(String selector,
            com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry.Tank tank) {
        if (selector == null || selector.isBlank()) return true;
        String normalized = selector.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals(tank.resourceId())
                || normalized.equals(tank.family() + ":" + tank.resourceId());
    }

    private static long saturatingAdd(long left, long right) {
        long bounded = Math.max(0, right);
        return Long.MAX_VALUE - left < bounded ? Long.MAX_VALUE : left + bounded;
    }

    private static final class DeviceValueLimit {
        private static final int CHEMICAL_TANKS = 256;
    }

    private static BlockPos targetPosition(BlockPos origin, String target) {
        Direction direction = Direction.byName(target);
        return direction == null ? origin : origin.relative(direction);
    }

    private static Direction accessSide(String target) {
        Direction direction = Direction.byName(target);
        return direction == null ? null : direction.getOpposite();
    }

    private static SensorReading redstone(ServerLevel level, BlockPos sensorPos, BlockPos target,
                                          SensorChannel channel, long time) {
        Direction direction = Direction.byName(channel.target());
        if (direction == null) {
            return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    0, "signal", time);
        }
        // Sensor-to-sensor redstone chaining creates a feedback graph rather than measuring
        // an external signal. Reject it before vanilla redstone propagation can re-enter another
        // StandaloneSensorBlockEntity during world startup or block ticking.
        if (level.getBlockEntity(target) instanceof StandaloneSensorBlockEntity) {
            return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.PARTIAL, "redstone sensor chaining is disabled", time);
        }
        Set<BlockPos> active = REDSTONE_PROBE_STACK.get();
        if (!active.add(sensorPos)) {
            return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.PARTIAL, "redstone probe cycle detected", time);
        }
        try {
            int value = level.getSignal(target, direction.getOpposite());
            if ("powered".equals(channel.metric()) || "present".equals(channel.metric())) value = value > 0 ? 1 : 0;
            return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(), value, "signal", time);
        } finally {
            active.remove(sensorPos);
            if (active.isEmpty()) REDSTONE_PROBE_STACK.remove();
        }
    }

    private static SensorReading blockState(ServerLevel level, BlockPos target,
                                             SensorChannel channel, long time) {
        BlockState state = level.getBlockState(target);
        String metric = channel.metric();
        if ("id".equals(metric) || "block".equals(metric)) {
            return SensorReading.text(channel.name(), channel.kind(), metric,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), time);
        }
        if ("solid".equals(metric)) {
            return SensorReading.numeric(channel.name(), channel.kind(), metric,
                    state.isSolid() ? 1 : 0, "boolean", time);
        }
        return stateProperty(channel, state, metric, time);
    }

    private static SensorReading machine(ServerLevel level, BlockPos target,
                                         SensorChannel channel, long time) {
        String metric = channel.metric();
        SensorReading state = stateProperty(channel, level.getBlockState(target), metric, time);
        if (state.quality() == SensorQuality.OK) return state;
        BlockEntity entity = level.getBlockEntity(target);
        if (entity instanceof ProgrammableLogicControllerBlockEntity plc) {
            return switch (metric) {
                case "active", "running" -> SensorReading.numeric(channel.name(), channel.kind(), metric,
                        plc.isRunning() ? 1 : 0, "boolean", time);
                case "fault" -> SensorReading.numeric(channel.name(), channel.kind(), metric,
                        plc.controllerFault().isBlank() && plc.compileError().isBlank() ? 0 : 1, "boolean", time);
                case "scan_count" -> SensorReading.numeric(channel.name(), channel.kind(), metric,
                        plc.dashboardScanCount(), "scans", time);
                default -> SensorReading.unavailable(channel.name(), channel.kind(), metric,
                        SensorQuality.UNAVAILABLE, "machine metric is not exposed by the target", time);
            };
        }
        return SensorReading.unavailable(channel.name(), channel.kind(), metric,
                SensorQuality.UNAVAILABLE, "machine metric is not exposed by the target", time);
    }

    private static SensorReading kinetic(ServerLevel level, BlockPos target,
                                         SensorChannel channel, long time) {
        SensorReading state = stateProperty(channel, level.getBlockState(target), channel.metric(), time);
        return state.quality() == SensorQuality.OK ? state
                : SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                SensorQuality.UNSUPPORTED,
                "kinetic speed is not available through the generic capability surface", time);
    }

    private static SensorReading stateProperty(SensorChannel channel, BlockState state,
                                               String requested, long time) {
        String metric = requested;
        if ("powered".equals(metric) || "active".equals(metric) || "running".equals(metric)
                || "enabled".equals(metric) || "lit".equals(metric) || "progress".equals(metric)
                || "speed".equals(metric) || "rotation_speed".equals(metric)
                || "overstressed".equals(metric)) {
            Property<?> found = state.getProperties().stream()
                    .filter(property -> property.getName().equals(metric))
                    .findFirst().orElse(null);
            if (found == null && ("active".equals(metric) || "running".equals(metric))) {
                found = state.getProperties().stream()
                        .filter(property -> property.getName().equals("working")
                                || property.getName().equals("enabled"))
                        .findFirst().orElse(null);
            }
            if (found == null && "powered".equals(metric)) {
                found = state.getProperties().stream()
                        .filter(property -> property.getName().equals("power")
                                || property.getName().equals("powered"))
                        .findFirst().orElse(null);
            }
            if (found == null) {
                return SensorReading.unavailable(channel.name(), channel.kind(), metric,
                        SensorQuality.UNAVAILABLE, "block has no property named " + metric, time);
            }
            return propertyReading(channel, state, found, time);
        }
        Property<?> found = state.getProperties().stream()
                .filter(property -> property.getName().equals(metric))
                .findFirst().orElse(null);
        return found == null
                ? SensorReading.unavailable(channel.name(), channel.kind(), metric,
                SensorQuality.UNAVAILABLE, "block property is not exposed", time)
                : propertyReading(channel, state, found, time);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SensorReading propertyReading(SensorChannel channel, BlockState state,
                                                 Property<?> property, long time) {
        Comparable value = state.getValue((Property) property);
        Property rawProperty = (Property) property;
        String text = rawProperty.getName(value);
        if (value instanceof Boolean bool) {
            return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    bool ? 1 : 0, "boolean", time);
        }
        if (value instanceof Number number) {
            return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    number.doubleValue(), "value", time);
        }
        return SensorReading.text(channel.name(), channel.kind(), channel.metric(), text, time);
    }

    private static SensorReading inventory(ServerLevel level, BlockPos target, Direction side,
                                           SensorChannel channel, long time) {
        BlockEntity entity = level.getBlockEntity(target);
        IItemHandler handler = entity == null ? null
                : entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve().orElse(null);
        if (handler == null) return unavailableCapability(channel, "item handler", time);
        long count = 0;
        long capacity = 0;
        int nonEmpty = 0;
        for (int slot = 0; slot < Math.min(handler.getSlots(), 128); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            capacity += Math.max(0, handler.getSlotLimit(slot));
            if (stack.isEmpty() || !matchesItem(stack, channel.selector())) continue;
            count += stack.getCount();
            nonEmpty++;
        }
        return switch (channel.metric()) {
            case "count", "amount" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    count, "items", time);
            case "capacity" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    capacity, "items", time);
            case "fill", "fill_percent" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    capacity <= 0 ? 0 : count * 100.0 / capacity, "percent", time);
            case "slots" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    Math.min(handler.getSlots(), 128), "slots", time);
            case "non_empty", "present" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    "present".equals(channel.metric()) ? (count > 0 ? 1 : 0) : nonEmpty, "slots", time);
            default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.UNSUPPORTED, "inventory metric is unsupported", time);
        };
    }

    private static SensorReading fluid(ServerLevel level, BlockPos target, Direction side,
                                       SensorChannel channel, long time) {
        BlockEntity entity = level.getBlockEntity(target);
        IFluidHandler handler = entity == null ? null
                : entity.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve().orElse(null);
        if (handler == null) return unavailableCapability(channel, "fluid handler", time);
        long amount = 0;
        long capacity = 0;
        boolean present = false;
        for (int tank = 0; tank < Math.min(handler.getTanks(), 64); tank++) {
            FluidStack stack = handler.getFluidInTank(tank);
            capacity += Math.max(0, handler.getTankCapacity(tank));
            if (!stack.isEmpty() && matchesFluid(stack, channel.selector())) {
                amount += stack.getAmount();
                present = true;
            }
        }
        return switch (channel.metric()) {
            case "amount", "stored" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    amount, "mb", time);
            case "capacity" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    capacity, "mb", time);
            case "fill", "fill_percent" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    capacity <= 0 ? 0 : amount * 100.0 / capacity, "percent", time);
            case "tanks" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    Math.min(handler.getTanks(), 64), "tanks", time);
            case "present" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    present ? 1 : 0, "boolean", time);
            default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.UNSUPPORTED, "fluid metric is unsupported", time);
        };
    }

    private static SensorReading energy(ServerLevel level, BlockPos target, Direction side,
                                        SensorChannel channel, long time) {
        BlockEntity entity = level.getBlockEntity(target);
        IEnergyStorage storage = entity == null ? null
                : entity.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
        if (storage == null) return unavailableCapability(channel, "energy capability", time);
        return switch (channel.metric()) {
            case "stored", "amount" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    storage.getEnergyStored(), "fe", time);
            case "capacity" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    storage.getMaxEnergyStored(), "fe", time);
            case "fill", "fill_percent" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    storage.getMaxEnergyStored() <= 0 ? 0
                            : storage.getEnergyStored() * 100.0 / storage.getMaxEnergyStored(), "percent", time);
            case "can_receive" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    storage.canReceive() ? 1 : 0, "boolean", time);
            case "can_extract" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    storage.canExtract() ? 1 : 0, "boolean", time);
            default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.UNSUPPORTED, "energy metric is unsupported", time);
        };
    }

    private static SensorReading entity(ServerLevel level, BlockPos target,
                                       SensorChannel channel, long time) {
        String selector = channel.selector();
        double radius = ENTITY_RADIUS;
        if (selector.startsWith("radius=")) {
            int separator = selector.indexOf(';');
            String radiusText = separator < 0 ? selector.substring(7) : selector.substring(7, separator);
            try { radius = Math.max(1, Math.min(16, Double.parseDouble(radiusText))); }
            catch (NumberFormatException ignored) { radius = ENTITY_RADIUS; }
            selector = separator < 0 ? "" : selector.substring(separator + 1);
        }
        String selectorFilter = selector;
        Predicate<Entity> filter = entity -> matchesEntity(entity, selectorFilter);
        AABB box = new AABB(target).inflate(radius);
        int matches = Math.min(MAX_ENTITY_MATCHES, level.getEntitiesOfClass(Entity.class, box, filter).size());
        return switch (channel.metric()) {
            case "count", "amount" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    matches, "entities", time);
            case "present" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    matches > 0 ? 1 : 0, "boolean", time);
            default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.UNSUPPORTED, "entity metric is unsupported", time);
        };
    }

    private static SensorReading environment(ServerLevel level, BlockPos target,
                                             SensorChannel channel, long time) {
        return switch (channel.metric()) {
            case "light" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.getMaxLocalRawBrightness(target), "light", time);
            case "sky_light" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.getBrightness(LightLayer.SKY, target), "light", time);
            case "block_light" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.getBrightness(LightLayer.BLOCK, target), "light", time);
            case "rain" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.isRaining() ? 1 : 0, "boolean", time);
            case "thunder" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.isThundering() ? 1 : 0, "boolean", time);
            case "day" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.isDay() ? 1 : 0, "boolean", time);
            case "time" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.getDayTime() % 24000L, "ticks", time);
            case "temperature" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                    level.getBiome(target).value().getBaseTemperature(), "temperature", time);
            case "dimension" -> SensorReading.text(channel.name(), channel.kind(), channel.metric(),
                    level.dimension().location().toString(), time);
            default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                    SensorQuality.UNSUPPORTED, "environment metric is unsupported", time);
        };
    }

    private static SensorReading network(ServerLevel level, BlockPos target,
                                         SensorChannel channel, long time) {
        BlockEntity entity = level.getBlockEntity(target);
        if (entity instanceof ModemBlockEntity modem) {
            return switch (channel.metric()) {
                case "online" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        modem.getOpenChannels().isEmpty() ? 0 : 1, "boolean", time);
                case "channels" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        modem.getOpenChannels().size(), "channels", time);
                case "pending" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        modem.pendingCount(), "messages", time);
                case "routers" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        RednetNetwork.routerCount(level), "routers", time);
                case "hosts" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        modem.visibleHosts(128).size(), "hosts", time);
                default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                        SensorQuality.UNSUPPORTED, "modem network metric is unsupported", time);
            };
        }
        if (entity instanceof NetworkRouterBlockEntity) {
            return switch (channel.metric()) {
                case "online", "present" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        1, "boolean", time);
                case "routers" -> SensorReading.numeric(channel.name(), channel.kind(), channel.metric(),
                        RednetNetwork.routerCount(level), "routers", time);
                default -> SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                        SensorQuality.UNSUPPORTED, "router network metric is unsupported", time);
            };
        }
        return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                SensorQuality.UNAVAILABLE, "target is not a TerminalCraft network device", time);
    }

    private static SensorReading unavailableCapability(SensorChannel channel, String capability, long time) {
        return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                SensorQuality.UNAVAILABLE, capability + " is not exposed on the target face", time);
    }

    private static boolean matchesItem(ItemStack stack, String selector) {
        if (selector == null || selector.isBlank()) return true;
        ResourceLocation id = ResourceLocation.tryParse(selector);
        return id != null && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static boolean matchesFluid(FluidStack stack, String selector) {
        if (selector == null || selector.isBlank()) return true;
        ResourceLocation id = ResourceLocation.tryParse(selector);
        ResourceLocation actual = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        return id != null && id.equals(actual);
    }

    private static boolean matchesEntity(Entity entity, String selector) {
        if (selector == null || selector.isBlank()) return true;
        if ("players".equals(selector)) return entity instanceof Player;
        if ("items".equals(selector)) return entity instanceof ItemEntity;
        ResourceLocation id = ResourceLocation.tryParse(selector);
        if (id == null) return false;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        return entity.getType() == type;
    }
}
