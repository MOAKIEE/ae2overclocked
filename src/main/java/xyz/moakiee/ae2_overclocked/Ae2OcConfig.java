package xyz.moakiee.ae2_overclocked;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * AE2 Overclocked common configuration.
 *
 * Notes:
 * - NeoForge will automatically write the default values defined here if the config file does not exist.
 * - All defaults preserve the original mod behaviour.
 */
public final class Ae2OcConfig {

    public static final int DEFAULT_CAPACITY_SLOT_LIMIT = Integer.MAX_VALUE;
    public static final double DEFAULT_SUPER_ENERGY_BUFFER_FE = 2_000_000_000.0;
    public static final int DEFAULT_PARALLEL_MAX_MULTIPLIER = Integer.MAX_VALUE;
    public static final int DEFAULT_SUPER_SPEED_CARD_MULTIPLIER = 512;
    public static final int DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD = 1000;
    public static final double DEFAULT_FE_PER_AE = 2.0;

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue CAPACITY_SLOT_LIMIT;
    private static final ModConfigSpec.DoubleValue SUPER_ENERGY_BUFFER_FE;
    private static final ModConfigSpec.IntValue PARALLEL_MAX_MULTIPLIER;
    private static final ModConfigSpec.IntValue SUPER_SPEED_CARD_MULTIPLIER;
    private static final ModConfigSpec.IntValue BREAK_PROTECTION_ITEM_THRESHOLD;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_MACHINE_IDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.translation("config.ae2_overclocked.cards").push("cards");

        CAPACITY_SLOT_LIMIT = builder
                .translation("config.ae2_overclocked.cards.capacityCardSlotLimit")
                .comment("Maximum slot capacity (item count and fluid amount in mB) when a Capacity Card is installed. Default: Integer.MAX_VALUE.")
                .defineInRange("capacityCardSlotLimit", DEFAULT_CAPACITY_SLOT_LIMIT, 64, Integer.MAX_VALUE);

        SUPER_ENERGY_BUFFER_FE = builder
                .translation("config.ae2_overclocked.cards.superEnergyCardBufferFE")
                .comment("Internal energy buffer limit (in FE) when a Super Energy Card is installed. Default: 2,000,000,000 FE.")
                .defineInRange("superEnergyCardBufferFE", DEFAULT_SUPER_ENERGY_BUFFER_FE, 2.0, Double.MAX_VALUE);

        PARALLEL_MAX_MULTIPLIER = builder
                .translation("config.ae2_overclocked.cards.parallelCardMaxMultiplier")
                .comment("Multiplier applied by the Parallel Card (Max tier). Default: Integer.MAX_VALUE.")
                .defineInRange("parallelCardMaxMultiplier", DEFAULT_PARALLEL_MAX_MULTIPLIER, 2, Integer.MAX_VALUE);

        SUPER_SPEED_CARD_MULTIPLIER = builder
                .translation("config.ae2_overclocked.cards.superSpeedCardMultiplier")
                .comment("Multiplier applied when Super Speed Card is active on I/O buses and I/O ports. Default: 512.")
                .defineInRange("superSpeedCardMultiplier", DEFAULT_SUPER_SPEED_CARD_MULTIPLIER, 1, Integer.MAX_VALUE);

        builder.pop();
        builder.translation("config.ae2_overclocked.protection").push("protection");

        BREAK_PROTECTION_ITEM_THRESHOLD = builder
                .translation("config.ae2_overclocked.protection.breakProtectionItemThreshold")
                .comment("Break-protection threshold. When the total item count inside a machine exceeds this value, Shift must be held to break it.")
                .defineInRange("breakProtectionItemThreshold", DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD, 1, Integer.MAX_VALUE);

        builder.pop();
        builder.translation("config.ae2_overclocked.machines").push("machines");

        DISABLED_MACHINE_IDS = builder
                .translation("config.ae2_overclocked.machines.disabledMachineIds")
                .comment("List of block IDs (namespace:path) whose machines should be excluded from all upgrade card effects,\ne.g. ae2:inscriber or ae2cs:crystal_pulverizer.")
                .defineListAllowEmpty(
                        List.of("disabledMachineIds"),
                        List::of,
                        entry -> entry instanceof String
                );

        builder.pop();
        SPEC = builder.build();
    }

    private Ae2OcConfig() {
    }

    public static int getCapacityCardSlotLimit() {
        return Math.max(CAPACITY_SLOT_LIMIT.get(), 64);
    }

    public static double getSuperEnergyBufferAE() {
        double safeFE = Math.max(SUPER_ENERGY_BUFFER_FE.get(), 2.0);
        return Math.max(safeFE / DEFAULT_FE_PER_AE, 1.0);
    }

    public static int getParallelCardMaxMultiplier() {
        return Math.max(PARALLEL_MAX_MULTIPLIER.get(), 2);
    }

    public static int getSuperSpeedCardMultiplier() {
        return Math.max(SUPER_SPEED_CARD_MULTIPLIER.get(), 1);
    }

    public static int getBreakProtectionItemThreshold() {
        return Math.max(BREAK_PROTECTION_ITEM_THRESHOLD.get(), 1);
    }

    /**
     * Returns whether the machine hosting the given object (or the object itself) is on the disabled list.
     *
     * @param hostOrMachine a machine {@link net.minecraft.world.level.block.entity.BlockEntity} or its host object
     * @return {@code true} if the machine's block ID is found in the disabled list
     */
    public static boolean isMachineDisabled(Object hostOrMachine) {
        Object machine = resolveMachine(hostOrMachine, 0);
        if (machine == null) {
            return false;
        }
        return isBlockIdDisabled(machine);
    }

    private static boolean isBlockIdDisabled(Object machine) {
        ResourceLocation blockId = resolveBlockId(machine);
        if (blockId == null) {
            return false;
        }
        String normalizedId = blockId.toString().toLowerCase(Locale.ROOT);
        for (String entry : DISABLED_MACHINE_IDS.get()) {
            if (entry == null) {
                continue;
            }
            String value = entry.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && value.equals(normalizedId)) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation resolveBlockId(Object machine) {
        if (machine instanceof BlockEntity blockEntity) {
            return BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }

        Object byGetBlockEntity = tryInvokeNoArg(machine, "getBlockEntity");
        if (byGetBlockEntity instanceof BlockEntity blockEntity) {
            return BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }

        Object byField = tryGetField(machine, "blockEntity");
        if (byField instanceof BlockEntity blockEntity) {
            return BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }

        return null;
    }

    private static Object resolveMachine(Object target, int depth) {
        if (target == null || depth > 6) {
            return null;
        }
        if (target instanceof BlockEntity) {
            return target;
        }

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            Object resolved = resolveMachine(byGetBlockEntity, depth + 1);
            if (resolved != null) {
                return resolved;
            }
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        if (byGetHost != null && byGetHost != target) {
            Object resolved = resolveMachine(byGetHost, depth + 1);
            if (resolved != null) {
                return resolved;
            }
        }

        Object byHostField = tryGetField(target, "host");
        if (byHostField != null && byHostField != target) {
            Object resolved = resolveMachine(byHostField, depth + 1);
            if (resolved != null) {
                return resolved;
            }
        }

        return target;
    }

    // ── Reflection caches for Ae2OcConfig ──────────────────────────────
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>> CONFIG_CACHE_METHOD = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Field>>  CONFIG_CACHE_FIELD  = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object tryInvokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        String cacheKey = clazz.getName() + "#" + methodName;

        java.util.Optional<Method> opt = CONFIG_CACHE_METHOD.computeIfAbsent(cacheKey, k -> {
            try {
                Method m = clazz.getMethod(methodName);
                m.setAccessible(true);
                return java.util.Optional.of(m);
            } catch (NoSuchMethodException e) {
                return java.util.Optional.empty();
            }
        });

        if (opt.isEmpty()) {
            return null;
        }

        try {
            return opt.get().invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryGetField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        String cacheKey = clazz.getName() + "#" + fieldName;

        java.util.Optional<Field> opt = CONFIG_CACHE_FIELD.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return java.util.Optional.of(f);
            } catch (NoSuchFieldException e) {
                return java.util.Optional.empty();
            }
        });

        if (opt.isEmpty()) {
            return null;
        }

        try {
            return opt.get().get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
