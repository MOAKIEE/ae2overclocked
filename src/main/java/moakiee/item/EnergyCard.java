package moakiee.item;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.world.item.Item;

/**
 * 超级能源卡 - 扩充机器 AE 能量缓存上限，防止并行吞吐时断电
 */
public class EnergyCard extends UpgradeCardItem {

    public EnergyCard() {
        super(new Item.Properties().stacksTo(1));
    }
}
