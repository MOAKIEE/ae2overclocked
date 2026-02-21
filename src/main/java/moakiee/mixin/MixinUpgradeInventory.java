package moakiee.mixin;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.util.inv.AppEngInternalInventory;
import moakiee.item.ParallelCard;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 拦截 AppEngInternalInventory 的 isItemValid 方法
 * 实现并行卡互斥：任何等级的并行卡只能安装一张
 */
@Mixin(AppEngInternalInventory.class)
public abstract class MixinUpgradeInventory {

    /**
     * 在 isItemValid 返回之前检查并行卡互斥
     * 如果正在插入的是并行卡，且已有任何等级的并行卡，则返回 false
     * 仅对 UpgradeInventory 实例生效
     */
    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2oc_checkParallelCardMutex(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        // 仅对 UpgradeInventory 生效
        if (!(this instanceof IUpgradeInventory)) {
            return;
        }
        
        if (stack.isEmpty()) {
            return;
        }
        
        // 检查正在插入的是否是并行卡
        if (!(stack.getItem() instanceof ParallelCard)) {
            return;
        }
        
        // 检查库存中是否已有任何并行卡
        AppEngInternalInventory self = (AppEngInternalInventory) (Object) this;
        if (ae2oc_hasAnyParallelCard(self)) {
            cir.setReturnValue(false);
        }
    }
    
    /**
     * 检测升级库存中是否已安装任何等级的并行卡
     */
    private static boolean ae2oc_hasAnyParallelCard(AppEngInternalInventory inv) {
        try {
            // 遍历所有槽位
            for (int i = 0; i < inv.size(); i++) {
                ItemStack existing = inv.getStackInSlot(i);
                if (!existing.isEmpty() && existing.getItem() instanceof ParallelCard) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
