package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import moakiee.support.OverstackingRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * 注入 GenericStackInv 的核心方法，绕过堆叠限制。
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
    public abstract boolean isAllowed(AEKey what);
    
    @Shadow
    protected abstract void onChange();
    
    // ===== setStack 注入 - 绕过 clamp 逻辑 =====
    @Inject(method = "setStack", at = @At("HEAD"), cancellable = true)
    private void ae2oc_setStack(int slot, GenericStack stack, CallbackInfo ci) {
        // 检查是否是超堆叠场景：数量 > 64 或已注册
        boolean shouldBypass = false;
        if (stack != null && stack.amount() > 64) {
            shouldBypass = true;
        } else if (OverstackingRegistry.shouldAllowOverstacking(this)) {
            shouldBypass = true;
        }
        
        if (!shouldBypass) {
            return;
        }
        
        // 直接设置，完全绕过 getMaxAmount clamp
        if (!Objects.equals(stacks[slot], stack)) {
            stacks[slot] = stack;
            onChange();
        }
        
        ci.cancel();
    }
    
    // ===== insert 注入 - 绕过 getMaxAmount 限制 =====
    @Inject(method = "insert(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J", at = @At("HEAD"), cancellable = true)
    private void ae2oc_insert(int slot, AEKey what, long amount, Actionable mode, CallbackInfoReturnable<Long> cir) {
        // 只在注册的 inventory 中生效（在放入时，判断当前数量可能还是 0）
        if (!OverstackingRegistry.shouldAllowOverstacking(this)) {
            return;
        }
        
        Objects.requireNonNull(what, "what");
        if (amount < 0) {
            cir.setReturnValue(0L);
            return;
        }
        
        if (!canInsert() || !isAllowed(what)) {
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
    
    // ===== extract 注入 - 确保正确提取 =====
    @Inject(method = "extract(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J", at = @At("HEAD"), cancellable = true)
    private void ae2oc_extract(int slot, AEKey what, long amount, Actionable mode, CallbackInfoReturnable<Long> cir) {
        // 检查是否是超堆叠场景：当前数量 > 64 或已注册
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
