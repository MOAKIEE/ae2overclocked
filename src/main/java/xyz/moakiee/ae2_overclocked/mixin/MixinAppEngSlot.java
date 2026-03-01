package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AppEngSlot.class, remap = false)
public abstract class MixinAppEngSlot {

    @Shadow
    @Final
    private InternalInventory inventory;

    @Shadow
    @Final
    private int invSlot;

    @Inject(
            method = "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_getMaxStackSize(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        // Keep non-stackable and wrapped stacks untouched.
        if (stack.getMaxStackSize() <= 1) {
            return;
        }

        int slotLimit = this.inventory.getSlotLimit(this.invSlot);
        if (slotLimit > stack.getMaxStackSize()) {
            cir.setReturnValue(slotLimit);
        }
    }
}
