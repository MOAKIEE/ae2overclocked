package moakiee.ae2oc.core.planning;

import java.util.OptionalLong;
import moakiee.ae2oc.core.quantity.SaturatedMath;

/** Pure constraint planning; an absent card limit denotes logical Max parallelism. */
public final class BatchPlanner {
    private BatchPlanner() {}

    public static Plan plan(OptionalLong cardLimit, long materialLimit, long outputLimit,
                            long operationBudget, double availableEnergy, double unitEnergy) {
        SaturatedMath.requireNonNegative(materialLimit);
        SaturatedMath.requireNonNegative(outputLimit);
        SaturatedMath.requireNonNegative(operationBudget);
        cardLimit.ifPresent(SaturatedMath::requireNonNegative);
        if (!Double.isFinite(availableEnergy) || availableEnergy < 0
                || !Double.isFinite(unitEnergy) || unitEnergy < 0) {
            throw new IllegalArgumentException("Invalid recipe energy");
        }
        long energyLimit = unitEnergy == 0 ? Long.MAX_VALUE
                : SaturatedMath.floorDivToLong(availableEnergy, unitEnergy);
        long count = Math.min(cardLimit.orElse(Long.MAX_VALUE), Math.min(materialLimit,
                Math.min(outputLimit, Math.min(operationBudget, energyLimit))));
        // Floating-point division can round upward at an exact integer boundary.
        while (count > 0 && count * unitEnergy > availableEnergy) {
            long adjusted = (long) Math.nextDown((double) count);
            count = Math.min(count - 1, adjusted);
        }
        return new Plan(count, count * unitEnergy);
    }

    public record Plan(long operations, double energyCost) {}
}
