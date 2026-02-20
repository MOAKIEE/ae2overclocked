package moakiee.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.helpers.externalstorage.GenericStackInv;
import moakiee.ModItems;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@Mixin(value = GenericStackInv.class, remap = false)
public class MixinGenericStackInv {

    private static final long AE2OC_MAX_FLUID_CAPACITY = Integer.MAX_VALUE;

    @Shadow
    @Final
    @Nullable
    private Runnable listener;

    @Inject(method = "getCapacity", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getFluidCapacity(AEKeyType space, CallbackInfoReturnable<Long> cir) {
        if (space == null || !space.equals(AEKeyType.fluids())) {
            return;
        }

        Object host = resolveHostFromListener(this.listener);
        if (getInstalledCapacityCards(host, 0) > 0) {
            cir.setReturnValue(AE2OC_MAX_FLUID_CAPACITY);
        }
    }

    @Nullable
    private static Object resolveHostFromListener(@Nullable Runnable runnable) {
        if (runnable == null) {
            return null;
        }

        if (runnable instanceof IUpgradeableObject) {
            return runnable;
        }

        try {
            for (Field field : runnable.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(runnable);
                if (value == null) {
                    continue;
                }
                if (value instanceof IUpgradeableObject) {
                    return value;
                }
                if (tryGetInstalledFromGetUpgrades(value) != null) {
                    return value;
                }

                Object nestedHost = tryGetField(value, "this$0");
                if (nestedHost instanceof IUpgradeableObject || tryGetInstalledFromGetUpgrades(nestedHost) != null) {
                    return nestedHost;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static int getInstalledCapacityCards(Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.CAPACITY_CARD.get());
        }

        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        int fromBlockEntity = getInstalledCapacityCards(byGetBlockEntity, depth + 1);
        if (fromBlockEntity > 0) {
            return fromBlockEntity;
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        int fromHost = getInstalledCapacityCards(byGetHost, depth + 1);
        if (fromHost > 0) {
            return fromHost;
        }

        Object byHostField = tryGetField(target, "host");
        return getInstalledCapacityCards(byHostField, depth + 1);
    }

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        try {
            Method getUpgrades = target.getClass().getMethod("getUpgrades");
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) {
                return null;
            }
            Method getInstalled = upgrades.getClass().getMethod("getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
            Object result = getInstalled.invoke(upgrades, ModItems.CAPACITY_CARD.get());
            if (result instanceof Integer i) {
                return i;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryGetField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
