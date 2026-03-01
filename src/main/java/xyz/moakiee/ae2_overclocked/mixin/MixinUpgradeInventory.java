package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.item.ParallelCard;

@Mixin(value = AppEngInternalInventory.class, remap = false)
public abstract class MixinUpgradeInventory {

    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true)
    private void ae2oc_checkParallelCardMutex(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!(this instanceof IUpgradeInventory) || stack.isEmpty()) {
            return;
        }

        if (!(stack.getItem() instanceof ParallelCard)) {
            return;
        }

        AppEngInternalInventory self = (AppEngInternalInventory) (Object) this;
        if (ae2oc_hasAnyParallelCard(self)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean ae2oc_hasAnyParallelCard(AppEngInternalInventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack existing = inv.getStackInSlot(i);
            if (!existing.isEmpty() && existing.getItem() instanceof ParallelCard) {
                return true;
            }
        }
        return false;
    }
}
