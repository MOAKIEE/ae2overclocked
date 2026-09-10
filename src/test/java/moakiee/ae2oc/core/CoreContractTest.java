package moakiee.ae2oc.core;

import java.math.BigInteger;
import java.util.OptionalLong;
import java.util.Random;
import moakiee.ae2oc.core.planning.BatchPlanner;
import moakiee.ae2oc.core.quantity.SaturatedMath;

/** Dependency-free deterministic property checks, executed by Gradle check. */
public final class CoreContractTest {
    public static void main(String[] args) {
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
