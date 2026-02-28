package xyz.moakiee.ae2_overclocked.item;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class ParallelCard extends UpgradeCardItem {

    public static final int MAX_PARALLEL = Integer.MAX_VALUE;
    private final int multiplier;

    public ParallelCard(int multiplier) {
        super(new Item.Properties().stacksTo(64));
        this.multiplier = multiplier;
    }

    public int getMultiplier() {
        return multiplier;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.ae2_overclocked.parallel_card").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
