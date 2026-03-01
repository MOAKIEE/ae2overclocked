package xyz.moakiee.ae2_overclocked.support;

import net.minecraft.world.item.ItemStack;

import java.util.function.BiFunction;

public final class ParallelEngine {

    private ParallelEngine() {
    }

    public static ParallelResult calculate(
            int cardMultiplier,
            int inputCount, int recipeInputCount,
            ItemStack outputStack,
            BiFunction<ItemStack, Boolean, ItemStack> outputInsertFn,
            double availableEnergy, double unitEnergy
    ) {
        if (cardMultiplier <= 0 || inputCount <= 0 || recipeInputCount <= 0) {
            return new ParallelResult(0, 0);
        }

        int materialLimit = inputCount / recipeInputCount;
        int outputLimit = probeOutputSpace(outputStack, outputInsertFn, cardMultiplier);

        int energyLimit;
        if (unitEnergy <= 0.001) {
            energyLimit = Integer.MAX_VALUE;
        } else {
            energyLimit = (int) (availableEnergy / unitEnergy);
        }

        int actual = Math.min(cardMultiplier, Math.min(materialLimit, Math.min(outputLimit, energyLimit)));
        actual = Math.max(actual, 0);
        double totalCost = actual * unitEnergy;
        return new ParallelResult(actual, totalCost);
    }

    public static ParallelResult calculateSimple(
            int cardMultiplier,
            int inputCount, int recipeInputCount,
            int outputSpaceLimit,
            double availableEnergy, double unitEnergy
    ) {
        if (cardMultiplier <= 0 || inputCount <= 0 || recipeInputCount <= 0) {
            return new ParallelResult(0, 0);
        }

        int materialLimit = inputCount / recipeInputCount;

        int energyLimit;
        if (unitEnergy <= 0.001) {
            energyLimit = Integer.MAX_VALUE;
        } else {
            energyLimit = (int) (availableEnergy / unitEnergy);
        }

        int actual = Math.min(cardMultiplier, Math.min(materialLimit, Math.min(outputSpaceLimit, energyLimit)));
        actual = Math.max(actual, 0);
        double totalCost = actual * unitEnergy;
        return new ParallelResult(actual, totalCost);
    }

    private static int probeOutputSpace(
            ItemStack singleOutput,
            BiFunction<ItemStack, Boolean, ItemStack> outputInsertFn,
            int maxParallel
    ) {
        if (singleOutput.isEmpty()) {
            return maxParallel;
        }

        int singleCount = singleOutput.getCount();
        long totalCount = (long) maxParallel * singleCount;
        int testCount = (int) Math.min(totalCount, Integer.MAX_VALUE);

        ItemStack testStack = singleOutput.copyWithCount(testCount);
        ItemStack remaining = outputInsertFn.apply(testStack, true);
        int accepted = testCount - remaining.getCount();
        int outputLimit = accepted / singleCount;
        return Math.max(outputLimit, 0);
    }

    public record ParallelResult(int actualParallel, double totalEnergyCost) {
    }
}
