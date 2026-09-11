package moakiee.ae2oc.core.observability;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** Process-wide, low-overhead counters for diagnosing the shared machine processor. */
public final class ProcessingMetrics {
    private static final long STARTED_AT_MILLIS = System.currentTimeMillis();
    private static final LongAdder TICK_CALLS = new LongAdder();
    private static final LongAdder BACKOFF_SKIPS = new LongAdder();
    private static final LongAdder BLOCKED_ATTEMPTS = new LongAdder();
    private static final LongAdder BATCHES_RESERVED = new LongAdder();
    private static final LongAdder OPERATIONS_RESERVED = new LongAdder();
    private static final LongAdder PROCESS_TICKS = new LongAdder();
    private static final DoubleAdder ENERGY_PAID = new DoubleAdder();
    private static final LongAdder OUTPUT_UNITS_DRAINED = new LongAdder();
    private static final LongAdder BATCHES_COMPLETED = new LongAdder();

    private ProcessingMetrics() {}

    public static void tickCalled() { TICK_CALLS.increment(); }
    public static void backoffSkipped() { BACKOFF_SKIPS.increment(); }
    public static void blocked() { BLOCKED_ATTEMPTS.increment(); }
    public static void batchReserved(long operations) {
        BATCHES_RESERVED.increment();
        OPERATIONS_RESERVED.add(operations);
    }
    public static void processTick() { PROCESS_TICKS.increment(); }
    public static void energyPaid(double amount) { ENERGY_PAID.add(amount); }
    public static void outputDrained(long amount) { OUTPUT_UNITS_DRAINED.add(amount); }
    public static void batchCompleted() { BATCHES_COMPLETED.increment(); }

    public static Snapshot snapshot() {
        return new Snapshot(STARTED_AT_MILLIS, TICK_CALLS.sum(), BACKOFF_SKIPS.sum(), BLOCKED_ATTEMPTS.sum(),
                BATCHES_RESERVED.sum(), OPERATIONS_RESERVED.sum(), PROCESS_TICKS.sum(), ENERGY_PAID.sum(),
                OUTPUT_UNITS_DRAINED.sum(), BATCHES_COMPLETED.sum());
    }

    public record Snapshot(long startedAtMillis, long tickCalls, long backoffSkips, long blockedAttempts,
            long batchesReserved, long operationsReserved, long processTicks, double energyPaid,
            long outputUnitsDrained, long batchesCompleted) {}
}
