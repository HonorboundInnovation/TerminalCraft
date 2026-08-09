package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional integrations may only narrow mutation access; they never perform the mutation.
 * A provider failure denies mutation rather than bypassing a target mod's protection policy.
 */
public final class OptionalDeviceMutationPolicyRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<ContextualProvider> CONTEXTUAL_PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalDeviceMutationPolicyRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    /** Registers a policy that may inspect the server-authenticated caller before allowing mutation. */
    public static void registerContextual(ContextualProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!CONTEXTUAL_PROVIDERS.contains(provider)) CONTEXTUAL_PROVIDERS.add(provider);
    }

    public static Decision evaluate(BlockEntity blockEntity) {
        return evaluate(blockEntity, DeviceCallContext.readOnly("unbound-mutation-check"));
    }

    /**
     * Evaluates legacy target-only policies followed by caller-aware policies. Every provider is
     * narrowing-only; any exception or linkage failure denies mutation.
     */
    public static Decision evaluate(BlockEntity blockEntity, DeviceCallContext caller) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        Objects.requireNonNull(caller, "caller");
        for (Provider provider : PROVIDERS) {
            try {
                Decision decision = Objects.requireNonNull(provider.evaluate(blockEntity), "decision");
                if (!decision.allowed()) return decision;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional mutation policy failed for {}; denying mutation",
                        blockEntity.getType(), exception);
                return Decision.deny("optional integration mutation policy failed");
            }
        }
        for (ContextualProvider provider : CONTEXTUAL_PROVIDERS) {
            try {
                Decision decision = Objects.requireNonNull(provider.evaluate(blockEntity, caller), "decision");
                if (!decision.allowed()) return decision;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Contextual optional mutation policy failed for {}; denying mutation",
                        blockEntity.getType(), exception);
                return Decision.deny("optional integration mutation policy failed");
            }
        }
        return Decision.allow();
    }

    public record Decision(boolean allowed, String reason) {
        public Decision {
            reason = Objects.requireNonNull(reason, "reason");
            if (reason.length() > 256) throw new IllegalArgumentException("policy reason exceeds limit");
            if (!allowed && reason.isBlank()) throw new IllegalArgumentException("denial reason is required");
        }

        public static Decision allow() { return new Decision(true, ""); }
        public static Decision deny(String reason) { return new Decision(false, reason); }
    }

    @FunctionalInterface
    public interface Provider {
        Decision evaluate(BlockEntity blockEntity);
    }

    @FunctionalInterface
    public interface ContextualProvider {
        Decision evaluate(BlockEntity blockEntity, DeviceCallContext caller);
    }
}
