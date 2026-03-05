package moakiee.item;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 超速卡 - 专用于提升 I/O Port 与导入导出总线吞吐
 */
public class SuperSpeedCard extends UpgradeCardItem {

    public SuperSpeedCard() {
        super(new Item.Properties().stacksTo(64));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.ae2_overclocked.super_speed_card").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
