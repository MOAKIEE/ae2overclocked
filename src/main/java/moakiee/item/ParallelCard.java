package moakiee.item;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.world.item.Item;

/**
 * 并行卡 - 在单次工作周期内批量处理配方
 * 通过 multiplier 区分不同等级（×2 / ×8 / ×64 / ×1024 / Max）
 * MAX_PARALLEL 代表无上限（仅受材料和电量约束）
 */
public class ParallelCard extends UpgradeCardItem {

    public static final int MAX_PARALLEL = Integer.MAX_VALUE;

    private final int multiplier;

    public ParallelCard(int multiplier) {
        super(new Item.Properties().stacksTo(64));
        this.multiplier = multiplier;
    }

    /**
     * 返回该卡片的并行倍数。
     * Max 级别返回 Integer.MAX_VALUE，实际运算时由木桶效应算法裁剪。
     */
    public int getMultiplier() {
        return multiplier;
    }
}
