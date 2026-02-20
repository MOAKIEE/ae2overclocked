package moakiee.mixin;

import appeng.menu.slot.AppEngSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AppEngSlot.class, remap = false)
public class MixinAppEngSlot {

    @Inject(
            method = {
                    "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I",
                    "m_5866_(Lnet/minecraft/world/item/ItemStack;)I"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void ae2oc_getMaxStackSize(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        int slotLimit = ((AppEngSlot) (Object) this).getMaxStackSize();
        if (slotLimit > stack.getMaxStackSize()) {
            cir.setReturnValue(slotLimit);
        }
    }
}