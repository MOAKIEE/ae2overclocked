package moakiee.item;

import appeng.api.parts.IPartHost;
import appeng.api.parts.SelectedPart;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.items.materials.UpgradeCardItem;
import moakiee.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.Supplier;

/**
 * 并行卡 - 在单次工作周期内批量处理配方
 * 通过 multiplier 区分不同等级（×2 / ×8 / ×64 / ×1024 / Max）
 * MAX_PARALLEL 代表无上限（仅受材料和电量约束）
 * 
 * 互斥规则：所有等级的并行卡共享一个槽位，只能插入一张
 */
public class ParallelCard extends UpgradeCardItem {

    public static final int MAX_PARALLEL = Integer.MAX_VALUE;

    private final int multiplier;

    /**
     * 所有等级的并行卡 Supplier 列表，用于互斥检测
     */
    @SuppressWarnings("unchecked")
    private static final Supplier<Item>[] ALL_PARALLEL_CARDS = new Supplier[]{
            () -> ModItems.PARALLEL_CARD.get(),
            () -> ModItems.PARALLEL_CARD_8X.get(),
            () -> ModItems.PARALLEL_CARD_64X.get(),
            () -> ModItems.PARALLEL_CARD_1024X.get(),
            () -> ModItems.PARALLEL_CARD_MAX.get()
    };

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

    /**
     * 重写右键安装逻辑，添加并行卡互斥检测
     * 如果机器中已有任何等级的并行卡，拒绝安装新的并行卡
     */
    @Override
    public InteractionResult onItemUseFirst(net.minecraft.world.item.ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        InteractionHand hand = context.getHand();

        if (player != null && net.minecraft.world.InteractionHand.MAIN_HAND.equals(hand) || 
            (player != null && player.isShiftKeyDown())) {
            
            final BlockEntity te = context.getLevel().getBlockEntity(context.getClickedPos());
            IUpgradeInventory upgrades = null;

            if (te instanceof IPartHost) {
                final SelectedPart sp = ((IPartHost) te).selectPartWorld(context.getClickLocation());
                if (sp.part instanceof IUpgradeableObject) {
                    upgrades = ((IUpgradeableObject) sp.part).getUpgrades();
                }
            } else if (te instanceof IUpgradeableObject) {
                upgrades = ((IUpgradeableObject) te).getUpgrades();
            }

            // 检测是否已安装任何等级的并行卡
            if (upgrades != null && hasAnyParallelCard(upgrades)) {
                if (!context.getLevel().isClientSide()) {
                    player.displayClientMessage(
                            Component.translatable("message.ae2_overclocked.parallel_card_conflict"), 
                            true
                    );
                }
                return InteractionResult.FAIL;
            }
        }

        // 没有冲突，交给父类处理正常的安装逻辑
        return super.onItemUseFirst(stack, context);
    }

    /**
     * 检测升级库存中是否已安装任何等级的并行卡
     */
    private boolean hasAnyParallelCard(IUpgradeInventory upgrades) {
        for (Supplier<Item> cardSupplier : ALL_PARALLEL_CARDS) {
            try {
                Item card = cardSupplier.get();
                if (card != null && upgrades.getInstalledUpgrades(card) > 0) {
                    return true;
                }
            } catch (Exception ignored) {
                // Supplier 可能在 mod 初始化早期失败
            }
        }
        return false;
    }
}
