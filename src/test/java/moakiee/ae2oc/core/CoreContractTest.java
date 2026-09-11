package moakiee.ae2oc.core;

import java.math.BigInteger;
import java.util.OptionalLong;
import java.util.Random;
import java.util.List;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.core.execution.BatchProcessor;
import moakiee.ae2oc.core.execution.RetryBackoff;
import moakiee.ae2oc.core.observability.ProcessingMetrics;
import moakiee.ae2oc.core.planning.MachineBudget;
import moakiee.ae2oc.migration.MigrationInspection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import moakiee.ae2oc.core.execution.ProcessingState;
import moakiee.ae2oc.core.planning.BatchPlanner;
import moakiee.ae2oc.core.quantity.SaturatedMath;

/** Dependency-free deterministic property checks, executed by Gradle check. */
public final class CoreContractTest {
    public static void main(String[] args) {
        migrationInspectionIsReadOnlyAndRecursive();
        retryBackoffIsBoundedAndResettable();
        logicalSlots();
        processing();
        SlotTransactionTest.run();
        ProcessorFaultTest.run();
        long[] edges = {0, 1, 2, Integer.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long a : edges) for (long b : edges) arithmetic(a, b);
        Random random = new Random(0xAE20C);
        for (int i = 0; i < 10000; i++) {
            arithmetic(random.nextLong() & Long.MAX_VALUE, random.nextLong() & Long.MAX_VALUE);
            long materials = random.nextInt(10000);
            long space = random.nextInt(10000);
            long budget = random.nextInt(4097);
            var plan = BatchPlanner.plan(OptionalLong.empty(), materials, space, budget, 1000, 3);
            check(plan.operations() == Math.min(333, Math.min(materials, Math.min(space, budget))));
            check(plan.energyCost() <= 1000);
        }
        check(BatchPlanner.plan(OptionalLong.of(2), 100, 100, 64, 100, 0).operations() == 2);
        check(BatchPlanner.plan(OptionalLong.empty(), Long.MAX_VALUE, Long.MAX_VALUE, 4096, 0, 0).operations() == 4096);
        rejects(() -> SaturatedMath.addNonNegative(-1, 0));
        rejects(() -> SaturatedMath.multiplyNonNegative(0, -1));
        rejects(() -> SaturatedMath.floorDivToLong(Double.NaN, 1));
        rejects(() -> BatchPlanner.plan(OptionalLong.empty(), 1, 1, 1, 1, Double.NaN));
        System.out.println("Core contracts passed: boundaries and 10000 deterministic quantity/planner cases");
    }

    private static void migrationInspectionIsReadOnlyAndRecursive() {
        var root = new CompoundTag();
        root.put("ae2ocLongSlots", new ListTag());
        var processing = new CompoundTag();
        processing.putInt("dataVersion", 1);
        var nested = new CompoundTag();
        nested.putInt("ae2ocCount", 70);
        nested.putInt("ae2ocDataVersion", 99);
        processing.put("nested", nested);
        root.put("ae2ocProcessing", processing);
        var before = root.copy();
        var report = MigrationInspection.inspect(root);
        check(root.equals(before));
        check(report.logicalInventories() == 1);
        check(report.processingBatches() == 1);
        check(report.legacyCountFields() == 1);
        check(report.currentDataVersions() == 1);
        check(report.unknownDataVersions() == 1);
        check(report.hasAe2OcData());
    }

    private static void retryBackoffIsBoundedAndResettable() {
        check(MachineBudget.share(1, 4) == 1);
        check(MachineBudget.share(64, 4) == 16);
        check(MachineBudget.share(Long.MAX_VALUE, 64) == Long.MAX_VALUE / 64);
        rejects(() -> MachineBudget.share(0, 1));
        rejects(() -> MachineBudget.share(1, 0));
        var retries = new RetryBackoff(5, 100);
        check(retries.ready(0));
        retries.blocked(10);
        check(!retries.ready(14) && retries.ready(15));
        retries.blocked(15);
        check(!retries.ready(24) && retries.ready(25));
        for (int i = 0; i < 10; i++) retries.blocked(1_000 + i * 100L);
        check(!retries.ready(1_999) && retries.ready(2_000));
        retries.reset();
        check(retries.ready(0));

        var before = ProcessingMetrics.snapshot();
        ProcessingMetrics.tickCalled();
        ProcessingMetrics.backoffSkipped();
        ProcessingMetrics.blocked();
        ProcessingMetrics.batchReserved(7);
        var after = ProcessingMetrics.snapshot();
        check(after.tickCalls() == before.tickCalls() + 1);
        check(after.backoffSkips() == before.backoffSkips() + 1);
        check(after.blockedAttempts() == before.blockedAttempts() + 1);
        check(after.batchesReserved() == before.batchesReserved() + 1);
        check(after.operationsReserved() == before.operationsReserved() + 7);
    }

    private static void logicalSlots() {
        check(moakiee.ae2oc.migration.LegacyQuantity.resolve(1, OptionalLong.of(Integer.MAX_VALUE), OptionalLong.of(8)) == Integer.MAX_VALUE);
        check(moakiee.ae2oc.migration.LegacyQuantity.resolve(1, OptionalLong.empty(), OptionalLong.of(1024)) == 1024);
        rejects(() -> moakiee.ae2oc.migration.LegacyQuantity.resolve(1, OptionalLong.of(-1), OptionalLong.empty()));
        var slot = new moakiee.ae2oc.core.quantity.LogicalSlot<String>(Long.MAX_VALUE);
        check(slot.insert("iron", Long.MAX_VALUE, false) == Long.MAX_VALUE);
        slot.setCapacity(64);
        check(slot.overCapacity() && slot.amount() == Long.MAX_VALUE);
        check(slot.insert("iron", 1, false) == 0);
        check(slot.extract(Long.MAX_VALUE - 63, false) == Long.MAX_VALUE - 63);
        check(!slot.overCapacity() && slot.amount() == 63);
        check(slot.insert("gold", 1, false) == 0);
        check(slot.insert("iron", 2, true) == 1 && slot.amount() == 63);
        check(slot.insert("iron", 2, false) == 1);
        slot.restore("gold", 1000);
        check(slot.overCapacity() && slot.amount() == 1000);
    }

    private static void processing() {
        var emptyOutput = new BatchProcessor<String>();
        emptyOutput.restore(new ProcessingState<>("test:empty-output", List.of(),
                List.of(new ResourceAmount<>("empty", 0), new ResourceAmount<>("real", 4)), 0, 0, 0));
        check(emptyOutput.drain(4, 1, resource -> {
            check(resource.key().equals("real") && resource.amount() == 4);
            return 4;
        }));
        check(emptyOutput.isIdle());
        emptyOutput.restore(new ProcessingState<>("test:only-empty", List.of(),
                List.of(new ResourceAmount<>("empty", 0)), 0, 0, 0));
        check(emptyOutput.drain(0, 0, resource -> { throw new AssertionError("Empty output called a port"); }));
        check(emptyOutput.isIdle());
        BatchProcessor<String> processor = new BatchProcessor<>();
        processor.begin(new ProcessingState<>("test:recipe", List.of(new ResourceAmount<>("input", 8)),
                List.of(new ResourceAmount<>("output", 16)), 80, 0, 5));
        var stalled = processor.snapshot();
        check(!processor.advance(request -> 0));
        check(processor.snapshot() == stalled);
        double[] charged = {0};
        for (int tick = 0; tick < 12; tick++) {
            processor.advance(request -> { double paid = Math.min(request, 10); charged[0] += paid; return paid; });
            var restored = new BatchProcessor<String>();
            restored.restore(processor.snapshot());
            processor = restored;
        }
        check(charged[0] == 80 && processor.snapshot().finished());
        check(!processor.drain(64, 1, resource -> 0));
        check(processor.ownedResources().get(0).amount() == 16);
        check(processor.drain(7, 1, ResourceAmount::amount));
        check(processor.snapshot().outputs().get(0).amount() == 9);
        var restored = new BatchProcessor<String>();
        restored.restore(processor.snapshot());
        restored.drain(64, 1, ResourceAmount::amount);
        check(restored.snapshot() == null);
        // A failure in the second destination must not replay the first accepted output.
        restored.begin(new ProcessingState<>("test:two", List.of(),
                List.of(new ResourceAmount<>("a", 1), new ResourceAmount<>("b", 1)), 0, 0, 0));
        try {
            restored.drain(64, 2, resource -> {
                if (resource.key().equals("b")) throw new IllegalStateException("destination unavailable");
                return 1;
            });
            throw new AssertionError("Expected injected failure");
        } catch (IllegalStateException expected) {
            check(restored.snapshot().outputs().equals(List.of(new ResourceAmount<>("b", 1))));
        }
    }

    private static void arithmetic(long a, long b) {
        BigInteger x = BigInteger.valueOf(a), y = BigInteger.valueOf(b);
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        check(SaturatedMath.addNonNegative(a, b) == x.add(y).min(max).longValueExact());
        check(SaturatedMath.multiplyNonNegative(a, b) == x.multiply(y).min(max).longValueExact());
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid quantity accepted");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Resource contract violated");
    }
}
