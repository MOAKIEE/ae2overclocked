package moakiee.mixin;

import appeng.api.upgrades.IUpgradeInventory;
import moakiee.item.ParallelCard;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 拦截 UpgradeInventory 的 isItemValid 方法
 * 实现并行卡互斥：任何等级的并行卡只能安装一张
 */
@Mixin(targets = "appeng.api.upgrades.UpgradeInventory", remap = false)
public abstract class MixinUpgradeInventory implements IUpgradeInventory {

    /**
     * 在 isItemValid 返回之前检查并行卡互斥
     * 如果正在插入的是并行卡，且已有任何等级的并行卡，则返回 false
     */
    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true)
    private void ae2oc_checkParallelCardMutex(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty()) {
            return;
        }
        
        // 检查正在插入的是否是并行卡
        if (!(stack.getItem() instanceof ParallelCard)) {
            return;
        }
        
        // 检查库存中是否已有任何并行卡
        if (hasAnyParallelCard()) {
            cir.setReturnValue(false);
        }
    }
    
    /**
     * 检测升级库存中是否已安装任何等级的并行卡
     */
    private boolean hasAnyParallelCard() {
        try {
            // 遍历自己的所有槽位
            for (int i = 0; i < this.size(); i++) {
                ItemStack existing = this.getStackInSlot(i);
                if (!existing.isEmpty() && existing.getItem() instanceof ParallelCard) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
