package moakiee.item;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.world.item.Item;

/**
 * 超速卡 - 强制机器 1-tick 瞬间完工，极大增加单次耗电
 */
public class OverclockCard extends UpgradeCardItem {

    public OverclockCard() {
        super(new Item.Properties().stacksTo(64));
    }
}
