package moakiee;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;



import java.util.List;
import java.util.Locale;

/**
 * AE2 Overclocked common configuration.
 *
 * Notes:
 * - Forge will automatically write the default values defined here if the config file does not exist.
 * - All defaults preserve the original mod behaviour.
 */
public final class Ae2OcConfig {

    public static final int DEFAULT_CAPACITY_SLOT_LIMIT = Integer.MAX_VALUE;
    public static final double DEFAULT_SUPER_ENERGY_BUFFER_FE = 2_000_000_000.0;
    public static final int DEFAULT_PARALLEL_MAX_MULTIPLIER = Integer.MAX_VALUE;
    public static final int DEFAULT_SUPER_SPEED_CARD_MULTIPLIER = 512;
    public static final int DEFAULT_OVERCLOCK_CARD_PROCESS_TICKS = 5;
    public static final int DEFAULT_BREAK_PROTECTION_ITEM_THRESHOLD = 1000;
    public static final double DEFAULT_FE_PER_AE = 2.0;

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue CAPACITY_SLOT_LIMIT;
    private static final ForgeConfigSpec.DoubleValue SUPER_ENERGY_BUFFER_FE;
    private static final ForgeConfigSpec.IntValue PARALLEL_MAX_MULTIPLIER;
    private static final ForgeConfigSpec.IntValue SUPER_SPEED_CARD_MULTIPLIER;
    private static final ForgeConfigSpec.IntValue OVERCLOCK_CARD_PROCESS_TICKS;
    private static final ForgeConfigSpec.IntValue BREAK_PROTECTION_ITEM_THRESHOLD;
    private static final ForgeConfigSpec.IntValue MAX_RECIPE_OPERATIONS;
    private static final ForgeConfigSpec.LongValue MAX_TRANSFER_AMOUNT;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED_MACHINE_IDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

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

        OVERCLOCK_CARD_PROCESS_TICKS = builder
            .translation("config.ae2_overclocked.cards.overclockCardProcessTicks")
            .comment("Number of ticks to complete a recipe when Overclock Card is installed. Lower = faster. Default: 5.")
            .defineInRange("overclockCardProcessTicks", DEFAULT_OVERCLOCK_CARD_PROCESS_TICKS, 1, 200);

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

        builder.push("performance");
        MAX_RECIPE_OPERATIONS = builder.comment("Maximum recipe or bus operations per machine tick, including Max cards.")
                .defineInRange("maxRecipeOperationsPerMachineTick", 4096, 1, 1048576);
        MAX_TRANSFER_AMOUNT = builder.comment("Maximum resource units transferred per machine tick.")
                .defineInRange("maxTransferAmountPerMachineTick", 1048576L, 1L, Integer.MAX_VALUE);
        builder.pop();
        SPEC = builder.build();
    }

    private Ae2OcConfig() {
    }

    public static int getMaxRecipeOperationsPerMachineTick() { return MAX_RECIPE_OPERATIONS.get(); }
    public static long getMaxTransferAmountPerMachineTick() { return MAX_TRANSFER_AMOUNT.get(); }

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

    public static int getSuperSpeedCardMultiplier() {
        int configured = SUPER_SPEED_CARD_MULTIPLIER.get();
        return Math.max(configured, 1);
    }

    public static int getOverclockCardProcessTicks() {
        int configured = OVERCLOCK_CARD_PROCESS_TICKS.get();
        return Math.max(configured, 1);
    }

    public static int getBreakProtectionItemThreshold() {
        int configured = BREAK_PROTECTION_ITEM_THRESHOLD.get();
        return Math.max(configured, 1);
    }

    private static volatile java.util.Set<ResourceLocation> disabledIds = java.util.Set.of();

    public static void reload(net.minecraftforge.fml.event.config.ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        var parsed = new java.util.HashSet<ResourceLocation>();
        for (String raw : DISABLED_MACHINE_IDS.get()) {
            var id = ResourceLocation.tryParse(raw.trim().toLowerCase(Locale.ROOT));
            if (id == null) org.slf4j.LoggerFactory.getLogger(Ae2OcConfig.class).warn("Invalid disabled machine ID: {}", raw);
            else parsed.add(id);
        }
        disabledIds = java.util.Set.copyOf(parsed);
    }

    public static boolean isMachineDisabled(Object machine) {
        if (!(machine instanceof BlockEntity blockEntity)) return false;
        return disabledIds.contains(ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock()));
    }
}
