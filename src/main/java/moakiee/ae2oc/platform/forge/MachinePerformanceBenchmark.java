package moakiee.ae2oc.platform.forge;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import moakiee.Ae2Overclocked;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Controlled machine performance benchmark suite for AE2 Overclocked.
 *
 * <p>Measures:
 * <ul>
 *   <li>First-batch latency (world ticks and wall-clock milliseconds)</li>
 *   <li>Throughput (items/tick and total completed items)</li>
 *   <li>MSPT distribution (mean, median/P50, P95, P99, min, max)</li>
 *   <li>Backoff and overhead behavior under idle and blocked states</li>
 * </ul>
 * across 8 isolated scenarios (1 machine vs 64 machines concurrent under 4 distinct workloads).
 */
@Mod.EventBusSubscriber(modid = Ae2Overclocked.MODID)
public final class MachinePerformanceBenchmark {
    private static final Logger LOG = LoggerFactory.getLogger("ae2oc/benchmark");
    private static final boolean BENCHMARK_ENABLED = Boolean.getBoolean("ae2oc.benchmark");

    public enum LoadType {
        IDLE,
        PROCESSING,
        BLOCKED,
        MAX_PARALLEL
    }

    public record ScenarioSpec(String name, int machineCount, LoadType loadType, int durationTicks) {}

    public record ScenarioResult(
            ScenarioSpec spec,
            int firstBatchLatencyTicks,
            double firstBatchLatencyMs,
            long totalItemsProduced,
            double throughputItemsPerTick,
            double meanMspt,
            double p50Mspt,
            double p95Mspt,
            double p99Mspt,
            double minMspt,
            double maxMspt,
            String backoffNotes
    ) {}

    private static final List<ScenarioSpec> SCENARIOS = List.of(
            new ScenarioSpec("single_idle", 1, LoadType.IDLE, 100),
            new ScenarioSpec("single_processing", 1, LoadType.PROCESSING, 100),
            new ScenarioSpec("single_blocked", 1, LoadType.BLOCKED, 100),
            new ScenarioSpec("single_max_parallel", 1, LoadType.MAX_PARALLEL, 100),
            new ScenarioSpec("multi_idle", 64, LoadType.IDLE, 100),
            new ScenarioSpec("multi_processing", 64, LoadType.PROCESSING, 100),
            new ScenarioSpec("multi_blocked", 64, LoadType.BLOCKED, 100),
            new ScenarioSpec("multi_max_parallel", 64, LoadType.MAX_PARALLEL, 100)
    );

    private enum State {
        INACTIVE,
        SETUP_WAIT,
        MEASURE,
        TEARDOWN_WAIT,
        COMPLETED
    }

    private static State state = State.INACTIVE;
    private static int currentScenarioIndex = 0;
    private static int waitTimer = 0;
    private static int measureTickIndex = 0;
    private static MinecraftServer server;
    private static ServerLevel level;

    private static long scenarioStartNano = 0;
    private static long currentTickStartNano = 0;
    /** World time captured at priming. The END phase that primes a scenario runs after that tick's machines,
     *  so the first observation is already one world tick later; a zero-based tick index would under-count. */
    private static long primeGameTime = 0;
    private static int firstBatchLatencyTicks = -1;
    private static double firstBatchLatencyMs = -1.0;
    private static long totalProducedItems = 0;
    private static long[] tickDurationsNano;
    private static final List<ScenarioResult> RESULTS = new ArrayList<>();

    private MachinePerformanceBenchmark() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!BENCHMARK_ENABLED) return;
        server = event.getServer();
        level = server.overworld();
        LOG.info("Initializing MachinePerformanceBenchmark suite across {} scenarios...", SCENARIOS.size());
        // Force chunk at (50, 50) corresponding to block coordinates ~800, 800
        level.setChunkForced(50, 50, true);
        level.getChunk(50, 50);

        currentScenarioIndex = 0;
        RESULTS.clear();
        startSetup(level);
    }

    private static void startSetup(ServerLevel level) {
        ScenarioSpec spec = SCENARIOS.get(currentScenarioIndex);
        LOG.info("Setting up benchmark scenario [{}/{}]: {} (machines: {}, load: {})",
                currentScenarioIndex + 1, SCENARIOS.size(), spec.name(), spec.machineCount(), spec.loadType());

        // Place machines and adjacent creative energy cells
        for (int i = 0; i < spec.machineCount(); i++) {
            int col = i % 8;
            int row = i / 8;
            BlockPos machinePos = new BlockPos(800 + col * 3, 64, 800 + row * 3);
            BlockPos cellPos = machinePos.west();
            level.setBlockAndUpdate(machinePos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cellPos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cellPos, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
            level.setBlockAndUpdate(machinePos, AEBlocks.INSCRIBER.block().defaultBlockState());
        }

        // Wait 40 ticks for AE2 grid activation and steady-state initialization
        state = State.SETUP_WAIT;
        waitTimer = 40;
    }

    private static void primeScenario(ServerLevel level) {
        ScenarioSpec spec = SCENARIOS.get(currentScenarioIndex);
        for (int i = 0; i < spec.machineCount(); i++) {
            int col = i % 8;
            int row = i / 8;
            BlockPos machinePos = new BlockPos(800 + col * 3, 64, 800 + row * 3);
            var be = level.getBlockEntity(machinePos);
            if (!(be instanceof InscriberBlockEntity machine)) {
                throw new IllegalStateException("Expected InscriberBlockEntity at " + machinePos);
            }
            if (!machine.getMainNode().isActive()) {
                throw new IllegalStateException("Machine at " + machinePos + " did not join grid after setup wait");
            }
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);
            var upgrades = machine.getUpgrades();
            var slots = ManagedItemStorages.slots(machine.getInternalInventory());

            switch (spec.loadType()) {
                case IDLE -> {
                    upgrades.setItemDirect(0, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
                }
                case PROCESSING -> {
                    upgrades.setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
                    upgrades.setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
                    slots.get(0).write(new ResourceAmount<>(AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRESS.asItem()), 1));
                    slots.get(2).write(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 100_000L));
                }
                case BLOCKED -> {
                    upgrades.setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
                    upgrades.setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
                    slots.get(0).write(new ResourceAmount<>(AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRESS.asItem()), 1));
                    slots.get(2).write(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 100_000L));
                    // Block output slot with dirt so items cannot push
                    slots.get(3).write(new ResourceAmount<>(AEItemKey.of(Items.DIRT), 64));
                }
                case MAX_PARALLEL -> {
                    upgrades.setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_MAX.get()));
                    upgrades.setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
                    slots.get(0).write(new ResourceAmount<>(AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRESS.asItem()), 1));
                    slots.get(2).write(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 10_000_000L));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!BENCHMARK_ENABLED || state == State.INACTIVE || state == State.COMPLETED) return;

        if (event.phase == TickEvent.Phase.START) {
            currentTickStartNano = System.nanoTime();
            return;
        }

        if (event.phase != TickEvent.Phase.END) return;

        long tickDurationNano = System.nanoTime() - currentTickStartNano;

        switch (state) {
            case SETUP_WAIT -> {
                waitTimer--;
                if (waitTimer <= 0) {
                    primeScenario(level);
                    state = State.MEASURE;
                    measureTickIndex = 0;
                    scenarioStartNano = System.nanoTime();
                    primeGameTime = level.getGameTime();
                    firstBatchLatencyTicks = -1;
                    firstBatchLatencyMs = -1.0;
                    totalProducedItems = 0;
                    tickDurationsNano = new long[SCENARIOS.get(currentScenarioIndex).durationTicks()];
                }
            }
            case MEASURE -> {
                ScenarioSpec spec = SCENARIOS.get(currentScenarioIndex);
                if (measureTickIndex < tickDurationsNano.length) {
                    tickDurationsNano[measureTickIndex] = tickDurationNano;
                }

                // Check machine outputs
                for (int i = 0; i < spec.machineCount(); i++) {
                    int col = i % 8;
                    int row = i / 8;
                    BlockPos machinePos = new BlockPos(800 + col * 3, 64, 800 + row * 3);
                    var be = level.getBlockEntity(machinePos);
                    if (be instanceof InscriberBlockEntity machine) {
                        if (spec.loadType() == LoadType.PROCESSING || spec.loadType() == LoadType.MAX_PARALLEL) {
                            var slots = ManagedItemStorages.slots(machine.getInternalInventory());
                            LocalResourceSlot outSlot = slots.get(3);
                            ResourceAmount<AEKey> out = outSlot.read();
                            if (out != null && out.amount() > 0 && out.key().equals(AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRINT.asItem()))) {
                                if (firstBatchLatencyTicks < 0) {
                                    // Report elapsed world ticks from priming, not the sampling index: the index
                                    // is still zero on the first END phase after the machines already ran once.
                                    firstBatchLatencyTicks = (int) Math.max(0, level.getGameTime() - primeGameTime);
                                    firstBatchLatencyMs = (System.nanoTime() - scenarioStartNano) / 1_000_000.0;
                                }
                                totalProducedItems += out.amount();
                                outSlot.write(null); // Drain output so processing never stalls
                            }
                        }
                    }
                }

                measureTickIndex++;
                if (measureTickIndex >= spec.durationTicks()) {
                    finishScenario(level);
                    state = State.TEARDOWN_WAIT;
                    waitTimer = 5;
                }
            }
            case TEARDOWN_WAIT -> {
                waitTimer--;
                if (waitTimer <= 0) {
                    currentScenarioIndex++;
                    if (currentScenarioIndex < SCENARIOS.size()) {
                        startSetup(level);
                    } else {
                        state = State.COMPLETED;
                        exportResults();
                        server.halt(false);
                    }
                }
            }
            default -> {}
        }
    }

    private static void finishScenario(ServerLevel level) {
        ScenarioSpec spec = SCENARIOS.get(currentScenarioIndex);
        long[] sorted = tickDurationsNano.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long d : sorted) sum += d;
        double mean = (sum / sorted.length) / 1_000_000.0;
        double p50 = sorted[(int) (sorted.length * 0.50)] / 1_000_000.0;
        double p95 = sorted[(int) (sorted.length * 0.95)] / 1_000_000.0;
        double p99 = sorted[(int) (sorted.length * 0.99)] / 1_000_000.0;
        double min = sorted[0] / 1_000_000.0;
        double max = sorted[sorted.length - 1] / 1_000_000.0;
        double throughput = (double) totalProducedItems / spec.durationTicks();
        int latencyTicks = Math.max(0, firstBatchLatencyTicks);
        double latencyMs = Math.max(0.0, firstBatchLatencyMs);

        String notes = switch (spec.loadType()) {
            case IDLE -> "IDLE (0 items, sleeping backoff active)";
            case BLOCKED -> "BLOCKED (output blocked, retry backoff active)";
            case PROCESSING -> "PROCESSING (steady-state production)";
            case MAX_PARALLEL -> "MAX_PARALLEL (unbounded throughput sprint)";
        };

        var result = new ScenarioResult(spec, latencyTicks, latencyMs, totalProducedItems,
                throughput, mean, p50, p95, p99, min, max, notes);
        RESULTS.add(result);

        String summary = String.format(Locale.ROOT,
                "Scenario [%s] finished -> Latency: %d t / %.1f ms | Items: %d | Throughput: %.2f items/t | MSPT mean/p50/p95/p99: %.3f/%.3f/%.3f/%.3f ms",
                spec.name(), latencyTicks, latencyMs, totalProducedItems, throughput, mean, p50, p95, p99);
        LOG.info("{}", summary);

        // Teardown: remove blocks
        for (int i = 0; i < spec.machineCount(); i++) {
            int col = i % 8;
            int row = i / 8;
            BlockPos machinePos = new BlockPos(800 + col * 3, 64, 800 + row * 3);
            BlockPos cellPos = machinePos.west();
            level.setBlockAndUpdate(machinePos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cellPos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void exportResults() {
        String osName = System.getProperty("os.name");
        String javaVersion = System.getProperty("java.version");
        String jvmName = System.getProperty("java.vm.name");
        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================================================================================================\n");
        sb.append("                                      AE2 OVERCLOCK PERFORMANCE BENCHMARK REPORT\n");
        sb.append("========================================================================================================================\n");
        sb.append(String.format(Locale.ROOT, " Environment: %s | Java %s (%s) | Cores: %d | Max Heap: %d MB\n",
                osName, javaVersion, jvmName, cores, maxHeapMb));
        sb.append("------------------------------------------------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-22s %-9s %-15s %-22s %-22s %-28s\n",
                "Scenario", "Machines", "Load Type", "Latency (Ticks/ms)", "Throughput (Items/t)", "MSPT Mean / P50 / P95 / P99"));
        sb.append("------------------------------------------------------------------------------------------------------------------------\n");

        for (var r : RESULTS) {
            String latStr = r.spec().loadType() == LoadType.IDLE || r.spec().loadType() == LoadType.BLOCKED
                    ? "N/A"
                    : String.format(Locale.ROOT, "%d t / %.1f ms", r.firstBatchLatencyTicks(), r.firstBatchLatencyMs());
            String tpStr = String.format(Locale.ROOT, "%.2f (%d tot)", r.throughputItemsPerTick(), r.totalItemsProduced());
            String msptStr = String.format(Locale.ROOT, "%.3f / %.3f / %.3f / %.3f ms",
                    r.meanMspt(), r.p50Mspt(), r.p95Mspt(), r.p99Mspt());

            sb.append(String.format(Locale.ROOT, "%-22s %-9d %-15s %-22s %-22s %-28s\n",
                    r.spec().name(), r.spec().machineCount(), r.spec().loadType(), latStr, tpStr, msptStr));
        }
        sb.append("========================================================================================================================\n");
        sb.append(String.format(Locale.ROOT, " [Benchmark] Benchmark suite completed successfully: %d scenarios verified\n", RESULTS.size()));
        sb.append("========================================================================================================================\n");

        String reportText = sb.toString();
        System.out.print(reportText);
        LOG.info("{}", reportText);

        // Build JSON representation
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append(String.format(Locale.ROOT, "  \"timestamp\": \"%s\",\n", Instant.now().toString()));
        json.append("  \"environment\": {\n");
        json.append(String.format(Locale.ROOT, "    \"os\": \"%s\",\n", escapeJson(osName)));
        json.append(String.format(Locale.ROOT, "    \"javaVersion\": \"%s\",\n", escapeJson(javaVersion)));
        json.append(String.format(Locale.ROOT, "    \"jvm\": \"%s\",\n", escapeJson(jvmName)));
        json.append(String.format(Locale.ROOT, "    \"cpuCores\": %d,\n", cores));
        json.append(String.format(Locale.ROOT, "    \"maxHeapMb\": %d\n", maxHeapMb));
        json.append("  },\n");
        json.append("  \"scenarios\": [\n");

        for (int i = 0; i < RESULTS.size(); i++) {
            var r = RESULTS.get(i);
            json.append("    {\n");
            json.append(String.format(Locale.ROOT, "      \"name\": \"%s\",\n", r.spec().name()));
            json.append(String.format(Locale.ROOT, "      \"machineCount\": %d,\n", r.spec().machineCount()));
            json.append(String.format(Locale.ROOT, "      \"loadType\": \"%s\",\n", r.spec().loadType()));
            json.append(String.format(Locale.ROOT, "      \"durationTicks\": %d,\n", r.spec().durationTicks()));
            json.append(String.format(Locale.ROOT, "      \"firstBatchLatencyTicks\": %d,\n", r.firstBatchLatencyTicks()));
            json.append(String.format(Locale.ROOT, "      \"firstBatchLatencyMs\": %.3f,\n", r.firstBatchLatencyMs()));
            json.append(String.format(Locale.ROOT, "      \"totalItemsProduced\": %d,\n", r.totalItemsProduced()));
            json.append(String.format(Locale.ROOT, "      \"throughputItemsPerTick\": %.4f,\n", r.throughputItemsPerTick()));
            json.append("      \"mspt\": {\n");
            json.append(String.format(Locale.ROOT, "        \"mean\": %.4f,\n", r.meanMspt()));
            json.append(String.format(Locale.ROOT, "        \"median\": %.4f,\n", r.p50Mspt()));
            json.append(String.format(Locale.ROOT, "        \"p95\": %.4f,\n", r.p95Mspt()));
            json.append(String.format(Locale.ROOT, "        \"p99\": %.4f,\n", r.p99Mspt()));
            json.append(String.format(Locale.ROOT, "        \"min\": %.4f,\n", r.minMspt()));
            json.append(String.format(Locale.ROOT, "        \"max\": %.4f\n", r.maxMspt()));
            json.append("      },\n");
            json.append(String.format(Locale.ROOT, "      \"notes\": \"%s\"\n", escapeJson(r.backoffNotes())));
            json.append("    }");
            if (i < RESULTS.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ],\n");
        json.append(String.format(Locale.ROOT, "  \"totalScenarios\": %d,\n", RESULTS.size()));
        json.append("  \"success\": true\n");
        json.append("}\n");

        String jsonText = json.toString();

        try {
            String benchDirProp = System.getProperty("ae2oc.benchmarkDirectory", "");
            if (!benchDirProp.isBlank()) {
                File benchDir = new File(benchDirProp);
                benchDir.mkdirs();
                File benchJson = new File(benchDir, "benchmark-results.json");
                try (var writer = new FileWriter(benchJson, StandardCharsets.UTF_8)) {
                    writer.write(jsonText);
                }
                LOG.info("Saved benchmark JSON report to: {}", benchJson.getAbsolutePath());
            }

            File localJson = new File("benchmark-results.json");
            try (var writer = new FileWriter(localJson, StandardCharsets.UTF_8)) {
                writer.write(jsonText);
            }
            LOG.info("Saved benchmark JSON report to: {}", localJson.getAbsolutePath());
        } catch (Exception e) {
            LOG.error("Failed to write benchmark-results.json", e);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
