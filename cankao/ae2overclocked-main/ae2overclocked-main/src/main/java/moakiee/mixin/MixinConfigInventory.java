package moakiee.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import moakiee.support.OverstackingRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 注入 ConfigInventory，绕过 allowOverstacking 和 getMaxAmount 限制。
 */
@Mixin(targets = "appeng.util.ConfigInventory", remap = false)
public abstract class MixinConfigInventory {
    
    // ===== getMaxAmount 注入 =====
    @Inject(method = "getMaxAmount", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getMaxAmount(AEKey key, CallbackInfoReturnable<Long> cir) {
        if (OverstackingRegistry.shouldAllowOverstacking(this)) {
            long capacity = ae2oc_getCapacityViaReflection(key.getType());
            if (capacity > 0) {
                cir.setReturnValue(capacity);
            }
        }
    }
    
    // ===== setStack 注入 - ConfigInventory 覆盖了父类方法 =====
    @Inject(method = "setStack", at = @At("HEAD"), cancellable = true)
    private void ae2oc_setStack(int slot, GenericStack stack, CallbackInfo ci) {
        if (!OverstackingRegistry.shouldAllowOverstacking(this)) {
            return;
        }
        
        try {
            // 检查 filter
            if (stack != null) {
                Method isAllowed = this.getClass().getMethod("isAllowed", AEKey.class);
                if (!(Boolean) isAllowed.invoke(this, stack.what())) {
                    ci.cancel();
                    return;
                }
            }
            
            // 获取 stacks 数组（在父类 GenericStackInv 中定义）
            Field stacksField = ae2oc_getFieldRecursive(this.getClass(), "stacks");
            stacksField.setAccessible(true);
            GenericStack[] stacks = (GenericStack[]) stacksField.get(this);
            
            // 直接设置，完全绕过所有限制
            if (!Objects.equals(stacks[slot], stack)) {
                stacks[slot] = stack;
                
                // 调用 onChange()
                Method onChange = ae2oc_getMethodRecursive(this.getClass(), "onChange");
                onChange.setAccessible(true);
                onChange.invoke(this);
            }
            
            ci.cancel();
        } catch (Throwable e) {
            // 反射失败，回退到原始逻辑
        }
    }
    
    @Unique
    private long ae2oc_getCapacityViaReflection(AEKeyType keyType) {
        try {
            Method method = this.getClass().getMethod("getCapacity", AEKeyType.class);
            Object result = method.invoke(this, keyType);
            if (result instanceof Long l) {
                return l;
            }
        } catch (Throwable ignored) {
        }
        return Long.MAX_VALUE;
    }
    
    @Unique
    private static Field ae2oc_getFieldRecursive(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return ae2oc_getFieldRecursive(superClass, fieldName);
            }
            throw e;
        }
    }
    
    @Unique
    private static Method ae2oc_getMethodRecursive(Class<?> clazz, String methodName) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return ae2oc_getMethodRecursive(superClass, methodName);
            }
            throw e;
        }
    }
}
