package xyz.moakiee.ae2_overclocked;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Ae2OcConfig {

    public static final int DEFAULT_CAPACITY_SLOT_LIMIT = Integer.MAX_VALUE;
    public static final double DEFAULT_SUPER_ENERGY_BUFFER_FE = 2_000_000_000.0;
    public static final int DEFAULT_PARALLEL_MAX_MULTIPLIER = Integer.MAX_VALUE;
    public static final int DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD = 1000;
    public static final double DEFAULT_FE_PER_AE = 2.0;

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue CAPACITY_SLOT_LIMIT;
    private static final ModConfigSpec.DoubleValue SUPER_ENERGY_BUFFER_FE;
    private static final ModConfigSpec.IntValue PARALLEL_MAX_MULTIPLIER;
    private static final ModConfigSpec.IntValue BREAK_PROTECTION_ITEM_THRESHOLD;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.translation("config.ae2_overclocked.cards").push("cards");

        CAPACITY_SLOT_LIMIT = builder
                .translation("config.ae2_overclocked.cards.capacityCardSlotLimit")
                .comment("Maximum slot stack size when Capacity Card is installed.")
                .defineInRange("capacityCardSlotLimit", DEFAULT_CAPACITY_SLOT_LIMIT, 64, Integer.MAX_VALUE);

        SUPER_ENERGY_BUFFER_FE = builder
                .translation("config.ae2_overclocked.cards.superEnergyCardBufferFE")
                .comment("Internal energy buffer limit in FE when Super Energy Card is installed.")
                .defineInRange("superEnergyCardBufferFE", DEFAULT_SUPER_ENERGY_BUFFER_FE, 2.0, Double.MAX_VALUE);

        PARALLEL_MAX_MULTIPLIER = builder
                .translation("config.ae2_overclocked.cards.parallelCardMaxMultiplier")
                .comment("Parallel multiplier used by Parallel Card Max.")
                .defineInRange("parallelCardMaxMultiplier", DEFAULT_PARALLEL_MAX_MULTIPLIER, 2, Integer.MAX_VALUE);

        builder.pop();
        builder.translation("config.ae2_overclocked.protection").push("protection");

        BREAK_PROTECTION_ITEM_THRESHOLD = builder
                .translation("config.ae2_overclocked.protection.breakProtectionItemThreshold")
                .comment("If internal item count is above this value, Shift is required to break machine.")
                .defineInRange("breakProtectionItemThreshold", DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD, 1, Integer.MAX_VALUE);

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

    public static int getBreakProtectionItemThreshold() {
        return Math.max(BREAK_PROTECTION_ITEM_THRESHOLD.get(), 1);
    }
}
