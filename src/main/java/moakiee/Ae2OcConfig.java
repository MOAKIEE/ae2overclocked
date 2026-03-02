package moakiee;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * AE2 Overclocked 通用配置（common）。
 *
 * 说明：
 * - 不填写时，Forge 会自动写入这里定义的默认值。
 * - 三项默认值保持当前项目原有行为。
 */
public final class Ae2OcConfig {

    public static final int DEFAULT_CAPACITY_SLOT_LIMIT = Integer.MAX_VALUE;
    public static final double DEFAULT_SUPER_ENERGY_BUFFER_FE = 2_000_000_000.0;
    public static final int DEFAULT_PARALLEL_MAX_MULTIPLIER = Integer.MAX_VALUE;
    public static final int DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD = 1000;
    public static final double DEFAULT_FE_PER_AE = 2.0;

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue CAPACITY_SLOT_LIMIT;
    private static final ForgeConfigSpec.DoubleValue SUPER_ENERGY_BUFFER_FE;
    private static final ForgeConfigSpec.IntValue PARALLEL_MAX_MULTIPLIER;
    private static final ForgeConfigSpec.IntValue BREAK_PROTECTION_ITEM_THRESHOLD;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED_MACHINE_IDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.translation("config.ae2_overclocked.cards").push("cards");

        CAPACITY_SLOT_LIMIT = builder
            .translation("config.ae2_overclocked.cards.capacityCardSlotLimit")
                .comment("堆叠卡生效时的槽位上限。默认 Integer.MAX_VALUE。")
                .defineInRange("capacityCardSlotLimit", DEFAULT_CAPACITY_SLOT_LIMIT, 64, Integer.MAX_VALUE);

        SUPER_ENERGY_BUFFER_FE = builder
            .translation("config.ae2_overclocked.cards.superEnergyCardBufferFE")
            .comment("超级能源卡生效时的内部能量缓存上限（单位 FE）。默认 2,000,000,000 FE。")
            .defineInRange("superEnergyCardBufferFE", DEFAULT_SUPER_ENERGY_BUFFER_FE, 2.0, Double.MAX_VALUE);

        PARALLEL_MAX_MULTIPLIER = builder
            .translation("config.ae2_overclocked.cards.parallelCardMaxMultiplier")
                .comment("并行卡Max的倍率。默认 Integer.MAX_VALUE。")
                .defineInRange("parallelCardMaxMultiplier", DEFAULT_PARALLEL_MAX_MULTIPLIER, 2, Integer.MAX_VALUE);

        builder.pop();

        builder.translation("config.ae2_overclocked.protection").push("protection");

        BREAK_PROTECTION_ITEM_THRESHOLD = builder
            .translation("config.ae2_overclocked.protection.breakProtectionItemThreshold")
            .comment("机器防误拆阈值。机器内部物品总数大于该值时，必须按住Shift才能拆除。")
            .defineInRange("breakProtectionItemThreshold", DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD, 1, Integer.MAX_VALUE);

        builder.pop();

        builder.translation("config.ae2_overclocked.machines").push("machines");

        DISABLED_MACHINE_IDS = builder
            .translation("config.ae2_overclocked.machines.disabledMachineIds")
            .comment("禁用机器方块ID列表（namespace:path），如 ae2:inscriber、ae2cs:crystal_pulverizer。")
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
        int configured = CAPACITY_SLOT_LIMIT.get();
        return Math.max(configured, 64);
    }

    public static double getSuperEnergyBufferAE() {
        double configuredFE = SUPER_ENERGY_BUFFER_FE.get();
        double safeFE = Math.max(configuredFE, 2.0);
        double ae = safeFE / DEFAULT_FE_PER_AE;
        return Math.max(ae, 1.0);
    }

    public static int getParallelCardMaxMultiplier() {
        int configured = PARALLEL_MAX_MULTIPLIER.get();
        return Math.max(configured, 2);
    }

    public static int getBreakProtectionItemThreshold() {
        int configured = BREAK_PROTECTION_ITEM_THRESHOLD.get();
        return Math.max(configured, 1);
    }

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
            return ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock());
        }

        Object byGetBlockEntity = tryInvokeNoArg(machine, "getBlockEntity");
        if (byGetBlockEntity instanceof BlockEntity blockEntity) {
            return ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock());
        }

        Object byField = tryGetField(machine, "blockEntity");
        if (byField instanceof BlockEntity blockEntity) {
            return ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock());
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

    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryGetField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
