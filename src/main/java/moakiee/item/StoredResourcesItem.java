package moakiee.item;

import java.util.List;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.me.helpers.IGridConnectedBlockEntity;
import moakiee.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A bounded, persistent drop for resources that cannot safely become ordinary item entities. */
public final class StoredResourcesItem extends Item {
    public StoredResourcesItem() { super(new Item.Properties().stacksTo(1).fireResistant()); }
    public static ItemStack pack(AEKey key, long amount) {
        var stack = new ItemStack(ModItems.STORED_RESOURCES.get());
        var tag = stack.getOrCreateTag();
        tag.putInt("dataVersion", 1);
        tag.put("resource", key.toTagGeneric());
        tag.putLong("amount", amount);
        return stack;
    }
    private static AEKey key(ItemStack stack) {
        return stack.hasTag() ? AEKey.fromTagGeneric(stack.getTag().getCompound("resource")) : null;
    }
    private static long amount(ItemStack stack) { return stack.hasTag() ? Math.max(0, stack.getTag().getLong("amount")) : 0; }
    private static void remove(ItemStack stack, long accepted) {
        long remaining = amount(stack) - accepted;
        if (remaining <= 0) stack.shrink(1);
        else stack.getOrCreateTag().putLong("amount", remaining);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!level.isClientSide && key(stack) instanceof AEItemKey item) {
            int offered = (int) Math.min(amount(stack), item.getMaxStackSize());
            if (offered > 0) {
                var delivery = item.toStack(offered);
                player.getInventory().add(delivery);
                remove(stack, offered - delivery.getCount());
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var stack = context.getItemInHand();
        if (!level.isClientSide && context.getPlayer() != null && key(stack) != null
                && level.getBlockEntity(context.getClickedPos()) instanceof IGridConnectedBlockEntity machine) {
            var grid = machine.getMainNode().getGrid();
            if (grid != null) {
                long offered = Math.min(amount(stack), 1_048_576L);
                long accepted = grid.getStorageService().getInventory().insert(key(stack), offered, Actionable.MODULATE,
                        IActionSource.ofPlayer(context.getPlayer()));
                if (accepted > 0) remove(stack, accepted);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        var key = key(stack);
        if (key != null) lines.add(key.getDisplayName().copy().append(" × " + amount(stack)));
        lines.add(Component.translatable("tooltip.ae2_overclocked.stored_resources"));
    }
}
