package moakiee.item;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.world.item.Item;

/**
 * 堆叠卡 - 提高机器插槽堆叠上限，为并行处理提供物理基础
 */
public class CapacityCard extends UpgradeCardItem {

    public CapacityCard() {
        super(new Item.Properties().stacksTo(1));
    }
}
