package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.support.OverstackingRegistry;

import java.lang.reflect.Method;

/**
 * Injects into ConfigInventory (a subclass of GenericStackInv) to bypass
 * the getMaxAmount limit for registered overstacking inventories.
 * <p>
 * In AE2 1.21.1, ConfigInventory.getMaxAmount() checks its own {@code allowOverstacking}
 * field. If false, it delegates to super.getMaxAmount() which clamps items to
 * Math.min(itemKey.getMaxStackSize(), capacity) — effectively 64 for most items.
 * <p>
 * We only need to override getMaxAmount here. The setStack() override in ConfigInventory
 * calls super.setStack() which is already handled by MixinGenericStackInv.
 * <p>
 * Note: getCapacity() is defined in the parent class GenericStackInv, so we cannot
 * use @Shadow for it. We use reflection instead.
 */
@Mixin(targets = "appeng.util.ConfigInventory", remap = false)
public abstract class MixinConfigInventory {

    // ===== getMaxAmount — return capacity instead of clamped value =====
    @Inject(method = "getMaxAmount", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getMaxAmount(AEKey key, CallbackInfoReturnable<Long> cir) {
        if (OverstackingRegistry.shouldAllowOverstacking(this)) {
            long capacity = ae2oc_getCapacity(key.getType());
            if (capacity > 0) {
                cir.setReturnValue(capacity);
            }
        }
    }

    @Unique
    private long ae2oc_getCapacity(AEKeyType keyType) {
        try {
            Method m = this.getClass().getMethod("getCapacity", AEKeyType.class);
            Object result = m.invoke(this, keyType);
            if (result instanceof Long l) {
                return l;
            }
        } catch (Throwable ignored) {
        }
        return Long.MAX_VALUE;
    }
}
