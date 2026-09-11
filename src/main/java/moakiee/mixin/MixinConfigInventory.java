package moakiee.mixin;

import appeng.api.stacks.AEKey;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.util.ConfigInventory;
import moakiee.support.OverstackingRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ConfigInventory.class, remap = false)
public abstract class MixinConfigInventory {
    @Inject(method = "getMaxAmount", at = @At("HEAD"), cancellable = true)
    private void ae2oc_capacity(AEKey key, CallbackInfoReturnable<Long> cir) {
        if (OverstackingRegistry.shouldAllowOverstacking(this))
            cir.setReturnValue(((GenericStackInv) (Object) this).getCapacity(key.getType()));
    }
}
