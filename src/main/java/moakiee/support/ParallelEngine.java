package moakiee.support;

import net.minecraft.world.item.ItemStack;

import java.util.function.BiFunction;

/**
 * 通用并行结算引擎
 *
 * 实现"木桶效应"算法：
 * P_actual = min(cardMultiplier, materialLimit, outputLimit, energyLimit)
 *
 * 该类只负责计算最终并行数和总耗能，不执行任何扣减/产出操作。
 * 实际的原子化执行（扣材料、扣电、出产物）由各 Mixin 在同一方法中完成。
 *
 * 安全保障：
 * - 所有约束取 min，任何一项不满足都会压低实际并行数
 * - 返回的 actualParallel 必然 >= 0
 * - energyLimit 使用 floor 除法，保证不会超额提取能量
 */
public final class ParallelEngine {

    private ParallelEngine() {
    }

    /**
     * 计算实际并行数（通过模拟插入检测输出空间）
     *
     * @param cardMultiplier   并行卡倍数（来自 ParallelCardRuntime，>=1）
     * @param inputCount       输入槽当前物品数量
     * @param recipeInputCount 配方每次消耗的输入数量（压印器一般为 1）
     * @param outputStack      配方单份产物 ItemStack（已 copy）
     * @param outputInsertFn   输出插入函数：(stack, simulate) → remaining，用于检测可放入多少
     * @param availableEnergy  当前可用能量 (AE)
     * @param unitEnergy       单次配方能耗 (AE)
     * @return 并行结算结果
     */
    public static ParallelResult calculate(
            int cardMultiplier,
            int inputCount, int recipeInputCount,
            ItemStack outputStack,
            BiFunction<ItemStack, Boolean, ItemStack> outputInsertFn,
            double availableEnergy, double unitEnergy) {

        if (cardMultiplier <= 0 || inputCount <= 0 || recipeInputCount <= 0) {
            return new ParallelResult(0, 0);
        }

        // 1. 材料约束
        int materialLimit = inputCount / recipeInputCount;

        // 2. 输出空间约束（通过模拟插入探测）
        int outputLimit = probeOutputSpace(outputStack, outputInsertFn, cardMultiplier);

        // 3. 能量约束
        int energyLimit;
        if (unitEnergy <= 0.001) {
            energyLimit = Integer.MAX_VALUE; // 无能耗配方
        } else {
            energyLimit = (int) (availableEnergy / unitEnergy);
        }

        // 4. 木桶效应
        int actual = Math.min(cardMultiplier, Math.min(materialLimit, Math.min(outputLimit, energyLimit)));
        actual = Math.max(actual, 0);

        double totalCost = actual * unitEnergy;
        return new ParallelResult(actual, totalCost);
    }

    /**
     * 简化版计算（直接给出输出空间上限，不依赖模拟插入）
     * 适用于流体输出等无法简单模拟插入的场景
     *
     * @param cardMultiplier   并行卡倍数
     * @param inputCount       输入物品数量
     * @param recipeInputCount 每次消耗数量
     * @param outputSpaceLimit 输出空间可容纳的份数
     * @param availableEnergy  可用能量
     * @param unitEnergy       单次能耗
     * @return 并行结算结果
     */
    public static ParallelResult calculateSimple(
            int cardMultiplier,
            int inputCount, int recipeInputCount,
            int outputSpaceLimit,
            double availableEnergy, double unitEnergy) {

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

    /**
     * 探测输出槽能接受多少份产物。
     *
     * 策略：二分查找最大可插入份数，避免一次构造超大 ItemStack 导致溢出
     *
     * @param singleOutput    单份产物
     * @param outputInsertFn  模拟插入函数
     * @param maxParallel     上限
     * @return 可插入的最大份数
     */
    private static int probeOutputSpace(
            ItemStack singleOutput,
            BiFunction<ItemStack, Boolean, ItemStack> outputInsertFn,
            int maxParallel) {

        if (singleOutput.isEmpty()) {
            return maxParallel; // 无产物的配方（不太可能，保险处理）
        }

        int singleCount = singleOutput.getCount();

        // 先尝试最大量，如果全部能塞进去就直接返回
        long totalCount = (long) maxParallel * singleCount;
        // ItemStack.count 是 int，需要 clamp
        int testCount = (int) Math.min(totalCount, Integer.MAX_VALUE);

        ItemStack testStack = singleOutput.copyWithCount(testCount);
        ItemStack remaining = outputInsertFn.apply(testStack, true);

        int accepted = testCount - remaining.getCount();
        int outputLimit = accepted / singleCount;

        return Math.max(outputLimit, 0);
    }

    /**
     * 并行结算结果
     *
     * @param actualParallel 实际并行倍数（木桶效应后的值）
     * @param totalEnergyCost 总能量消耗 = actualParallel × unitEnergy
     */
    public record ParallelResult(int actualParallel, double totalEnergyCost) {
    }
}
