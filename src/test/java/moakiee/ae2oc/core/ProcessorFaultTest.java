package moakiee.ae2oc.core;

import java.util.List;
import java.util.HashMap;
import java.util.Random;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.core.execution.BatchProcessor;
import moakiee.ae2oc.core.execution.ProcessingState;

/** Exact-port failures, restart boundaries, and externally observed conservation. */
final class ProcessorFaultTest {
    static void run() {
        energyFailures();
        outputFailures();
        randomizedRecovery();
        System.out.println("Processor fault contracts passed: invalid ports and 1000 deterministic recovery cases");
    }

    private static void energyFailures() {
        var processor = new BatchProcessor<String>();
        processor.begin(new ProcessingState<>("test:energy", List.of(new ResourceAmount<>("input", 2)),
                List.of(new ResourceAmount<>("output", 3)), 10, 0, 2));
        check(processor.advance(request -> 4));
        var paid = processor.snapshot();
        check(paid.energyPaid() == 4 && paid.ticksRemaining() == 2);
        var failure = new IllegalStateException("energy unavailable");
        try {
            processor.advance(request -> { throw failure; });
            throw new AssertionError("Expected energy failure");
        } catch (IllegalStateException actual) { check(actual == failure); }
        check(processor.snapshot() == paid);
        for (double invalid : new double[] {-1, 7, Double.NaN, Double.POSITIVE_INFINITY}) {
            expectIllegalState(() -> processor.advance(request -> invalid));
            check(processor.snapshot() == paid);
        }
        processor.restore(paid);
        check(processor.advance(request -> { check(request == 6); return request; }));
        check(processor.snapshot().ticksRemaining() == 1);
        check(processor.ownedResources().equals(paid.inputs()));
        check(!processor.drain(100, 10, resource -> { throw new AssertionError("Premature output"); }));
        check(processor.advance(request -> { throw new AssertionError("Charged paid batch again"); }));
        check(processor.ownedResources().equals(paid.outputs()));
        check(processor.drain(100, 10, ResourceAmount::amount) && processor.isIdle());
    }

    private static void outputFailures() {
        var processor = new BatchProcessor<String>();
        processor.begin(new ProcessingState<>("test:output", List.of(),
                List.of(new ResourceAmount<>("a", 5), new ResourceAmount<>("b", 7)), 0, 0, 0));
        var initial = processor.snapshot();
        for (long invalid : new long[] {-1, 6, Long.MAX_VALUE}) {
            expectIllegalState(() -> processor.drain(5, 1, resource -> invalid));
            check(processor.snapshot() == initial);
        }
        check(!processor.drain(0, 10, resource -> { throw new AssertionError("Zero amount budget"); }));
        check(!processor.drain(10, 0, resource -> { throw new AssertionError("Zero call budget"); }));
        long[] delivered = {0};
        int[] calls = {0};
        var failure = new IllegalStateException("output unavailable");
        try {
            processor.drain(10, 3, resource -> {
                if (++calls[0] == 2) throw failure;
                delivered[0] += 2;
                return 2;
            });
            throw new AssertionError("Expected output failure");
        } catch (IllegalStateException actual) { check(actual == failure); }
        check(processor.snapshot().outputs().get(0).amount() == 3);
        var restarted = new BatchProcessor<String>();
        restarted.restore(processor.snapshot());
        calls[0] = 0;
        check(restarted.drain(100, 1, resource -> { calls[0]++; delivered[0] += resource.amount(); return resource.amount(); }));
        check(calls[0] == 1 && delivered[0] == 5);
        check(restarted.snapshot().outputs().equals(List.of(new ResourceAmount<>("b", 7))));
        check(restarted.drain(100, 1, resource -> { delivered[0] += resource.amount(); return resource.amount(); }));
        check(restarted.isIdle() && delivered[0] == 12);
    }

    private static void randomizedRecovery() {
        var random = new Random(0xFA017);
        for (int scenario = 0; scenario < 1000; scenario++) {
            var expected = List.of(new ResourceAmount<>("a", 1L + random.nextInt(1000)),
                    new ResourceAmount<>("b", 1L + random.nextInt(1000)),
                    new ResourceAmount<>("a", 1L + random.nextInt(1000)));
            var processor = new BatchProcessor<String>();
            processor.begin(new ProcessingState<>("test:recovery", List.of(), expected, 0, 0, 0));
            var delivered = new HashMap<String, Long>();
            var failure = new IllegalStateException("temporary output failure");
            int iterations = 0;
            while (!processor.isIdle()) {
                check(++iterations < 10000);
                long budget = 1 + random.nextInt(200);
                int keyBudget = 1 + random.nextInt(4);
                long[] transferred = {0};
                int[] calls = {0};
                try {
                    processor.drain(budget, keyBudget, resource -> {
                        calls[0]++;
                        if (random.nextInt(5) == 0) throw failure;
                        long accepted = random.nextInt((int) resource.amount() + 1);
                        delivered.merge(resource.key(), accepted, Long::sum);
                        transferred[0] += accepted;
                        return accepted;
                    });
                } catch (IllegalStateException actual) { check(actual == failure); }
                check(calls[0] <= keyBudget && transferred[0] <= budget);
                for (String key : List.of("a", "b")) {
                    long total = expected.stream().filter(r -> r.key().equals(key)).mapToLong(ResourceAmount::amount).sum();
                    long pending = processor.ownedResources().stream().filter(r -> r.key().equals(key)).mapToLong(ResourceAmount::amount).sum();
                    check(delivered.getOrDefault(key, 0L) + pending == total);
                }
                // Every successful prefix and failed call is a possible restart boundary.
                var restarted = new BatchProcessor<String>();
                restarted.restore(processor.snapshot());
                processor = restarted;
            }
        }
    }

    private static void expectIllegalState(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected exact-port contract rejection");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Processor fault contract failed");
    }
}
