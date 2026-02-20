package moakiee.mixin;

import appeng.menu.AEBaseMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AEBaseMenu.class, remap = false)
public class MixinAEBaseMenu {

    @Inject(method = "x", at = @At("HEAD"), cancellable = true)
    private void ae2oc_mergeWithSlotAwareLimit(Slot clickSlot, ItemStack tis, Slot d, CallbackInfoReturnable<Boolean> cir) {
        final ItemStack t = d.getItem().copy();

        if (ItemStack.isSameItemSameTags(t, tis)) {
            int maxSize = d.getMaxStackSize(tis);

            int placeable = maxSize - t.getCount();
            if (placeable > 0) {
                if (tis.getCount() < placeable) {
                    placeable = tis.getCount();
                }

                t.setCount(t.getCount() + placeable);
                tis.setCount(tis.getCount() - placeable);

                d.set(t);

                if (tis.getCount() <= 0) {
                    clickSlot.set(ItemStack.EMPTY);
                    d.setChanged();

                    ((AEBaseMenu) (Object) this).broadcastChanges();
                    ((AEBaseMenu) (Object) this).broadcastChanges();
                    cir.setReturnValue(true);
                    return;
                } else {
                    ((AEBaseMenu) (Object) this).broadcastChanges();
                }
            }
        }

        cir.setReturnValue(false);
    }
}