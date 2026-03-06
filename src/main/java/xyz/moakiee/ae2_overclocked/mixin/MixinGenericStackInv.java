package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.support.OverstackingRegistry;

import java.util.Objects;

/**
 * Injects into GenericStackInv to bypass stacking limits for registered overstacking inventories.
 * <p>
 * In AE2 1.21.1, GenericStackInv.setStack() clamps amounts via getMaxAmount(), and
 * insert() also respects that limit. For items, getMaxAmount() returns
 * Math.min(itemKey.getMaxStackSize(), capacity) — i.e. 64 max even if capacity is higher.
 * Without this mixin, items/fluids vanish when exceeding the default stack size.
 * <p>
 * Additionally, extract() internally calls setStack() to update the slot, so the clamp
 * in setStack() can corrupt overstacked slots during extraction as well.
 * <p>
 * Note: In 1.21.1 AE2, the old isAllowed(AEKey) was replaced by isAllowedIn(int, AEKey).
 */
@Mixin(targets = "appeng.helpers.externalstorage.GenericStackInv", remap = false)
public abstract class MixinGenericStackInv {

    @Shadow
    protected GenericStack[] stacks;

    @Shadow
    public abstract long getCapacity(AEKeyType space);

    @Shadow
    public abstract AEKey getKey(int slot);

    @Shadow
    public abstract long getAmount(int slot);

    @Shadow
    public abstract boolean canInsert();

    @Shadow
    public abstract boolean canExtract();

    @Shadow
    public abstract boolean isAllowedIn(int slot, AEKey what);

    @Shadow
    protected abstract void onChange();

    // ===== setStack — bypass the getMaxAmount clamp =====
    @Inject(method = "setStack", at = @At("HEAD"), cancellable = true)
    private void ae2oc_setStack(int slot, GenericStack stack, CallbackInfo ci) {
        boolean shouldBypass = false;
        if (stack != null && stack.amount() > 64) {
            shouldBypass = true;
        } else if (OverstackingRegistry.shouldAllowOverstacking(this)) {
            shouldBypass = true;
        }

        if (!shouldBypass) {
            return;
        }

        if (!Objects.equals(stacks[slot], stack)) {
            stacks[slot] = stack;
            onChange();
        }

        ci.cancel();
    }

    // ===== insert — use capacity instead of getMaxAmount =====
    @Inject(method = "insert(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
            at = @At("HEAD"), cancellable = true)
    private void ae2oc_insert(int slot, AEKey what, long amount, Actionable mode,
                              CallbackInfoReturnable<Long> cir) {
        if (!OverstackingRegistry.shouldAllowOverstacking(this)) {
            return;
        }

        Objects.requireNonNull(what, "what");
        if (amount < 0) {
            cir.setReturnValue(0L);
            return;
        }

        if (!canInsert() || !isAllowedIn(slot, what)) {
            cir.setReturnValue(0L);
            return;
        }

        AEKey currentWhat = getKey(slot);
        long currentAmount = getAmount(slot);

        if (currentWhat == null || currentWhat.equals(what)) {
            long capacity = getCapacity(what.getType());
            long newAmount = Math.min(currentAmount + amount, capacity);

            if (newAmount > currentAmount) {
                if (mode == Actionable.MODULATE) {
                    stacks[slot] = new GenericStack(what, newAmount);
                    onChange();
                }
                cir.setReturnValue(newAmount - currentAmount);
                return;
            }
        }

        cir.setReturnValue(0L);
    }

    // ===== extract — ensure correct extraction for overstacked amounts =====
    @Inject(method = "extract(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
            at = @At("HEAD"), cancellable = true)
    private void ae2oc_extract(int slot, AEKey what, long amount, Actionable mode,
                               CallbackInfoReturnable<Long> cir) {
        long currentAmount = getAmount(slot);
        boolean shouldBypass = false;
        if (currentAmount > 64) {
            shouldBypass = true;
        } else if (OverstackingRegistry.shouldAllowOverstacking(this)) {
            shouldBypass = true;
        }

        if (!shouldBypass) {
            return;
        }

        Objects.requireNonNull(what, "what");
        if (amount < 0) {
            cir.setReturnValue(0L);
            return;
        }

        AEKey currentWhat = getKey(slot);
        if (!canExtract() || currentWhat == null || !currentWhat.equals(what)) {
            cir.setReturnValue(0L);
            return;
        }

        long canExtract = Math.min(currentAmount, amount);

        if (canExtract > 0) {
            if (mode == Actionable.MODULATE) {
                long newAmount = currentAmount - canExtract;
                if (newAmount <= 0) {
                    stacks[slot] = null;
                } else {
                    stacks[slot] = new GenericStack(what, newAmount);
                }
                onChange();
            }
            cir.setReturnValue(canExtract);
            return;
        }

        cir.setReturnValue(0L);
    }
}
