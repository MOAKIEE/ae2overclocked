package xyz.moakiee.ae2_overclocked.mixin;

import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen", remap = false)
public class MixinReactionChamberScreen {

    @Unique
    private static final int AE2OC_DEFAULT_BUCKETS = 16;

    @Unique
    private static final int AE2OC_UPGRADED_BUCKETS = Integer.MAX_VALUE;

    @Unique
    private int ae2ocLastInputMax = Integer.MIN_VALUE;

    @Unique
    private int ae2ocLastOutputMax = Integer.MIN_VALUE;

    @Unique
    private static final sun.misc.Unsafe AE2OC_UNSAFE;

    @Unique
    private static long ae2ocMaxLevelOffset = -1L;

    static {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            AE2OC_UNSAFE = (sun.misc.Unsafe) field.get(null);
        } catch (Throwable t) {
            throw new RuntimeException("AE2OC failed to initialize Unsafe", t);
        }
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), require = 0)
    private void ae2oc_updateTankMaxLevel(CallbackInfo ci) {
        try {
            Object menu = ae2oc_invokeNoArg(this, "getMenu", "m_6262_");
            if (menu == null) {
                return;
            }

            Object host = ae2oc_invokeNoArg(menu, "getHost", "host");
            if (host == null) {
                return;
            }

            boolean upgraded = CapacityCardRuntime.getInstalledCapacityCards(host) > 0;
            int targetMax = upgraded ? AE2OC_UPGRADED_BUCKETS : AE2OC_DEFAULT_BUCKETS;

            Object inputSlot = ae2oc_getFieldValue(this, "inputSlot");
            Object outputSlot = ae2oc_getFieldValue(this, "outputSlot");

            if (targetMax != this.ae2ocLastInputMax) {
                ae2oc_setMaxLevelAndRefresh(inputSlot, targetMax);
                this.ae2ocLastInputMax = targetMax;
            }

            if (targetMax != this.ae2ocLastOutputMax) {
                ae2oc_setMaxLevelAndRefresh(outputSlot, targetMax);
                this.ae2ocLastOutputMax = targetMax;
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static Object ae2oc_invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }

        for (String methodName : methodNames) {
            Method method = ae2oc_findMethod(target.getClass(), methodName);
            if (method == null || method.getParameterCount() != 0) {
                continue;
            }

            try {
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    @Unique
    private static Method ae2oc_findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @Unique
    private static Object ae2oc_getFieldValue(Object target, String fieldName) {
        if (target == null) {
            return null;
        }

        Field field = ae2oc_findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }

        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static Field ae2oc_findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @Unique
    private static void ae2oc_setMaxLevelAndRefresh(Object fluidTankSlot, int maxLevel) {
        if (fluidTankSlot == null) {
            return;
        }

        try {
            if (ae2ocMaxLevelOffset < 0L) {
                Field maxLevelField = ae2oc_findField(fluidTankSlot.getClass(), "maxLevel");
                if (maxLevelField == null) {
                    return;
                }
                ae2ocMaxLevelOffset = AE2OC_UNSAFE.objectFieldOffset(maxLevelField);
            }

            AE2OC_UNSAFE.putInt(fluidTankSlot, ae2ocMaxLevelOffset, maxLevel);

            Field contentField = ae2oc_findField(fluidTankSlot.getClass(), "content");
            if (contentField == null) {
                return;
            }
            contentField.setAccessible(true);
            Object content = contentField.get(fluidTankSlot);
            if (!(content instanceof FluidStack stack)) {
                return;
            }

            Method setFluidStack = ae2oc_findMethod(fluidTankSlot.getClass(), "setFluidStack", FluidStack.class);
            if (setFluidStack == null) {
                return;
            }
            setFluidStack.setAccessible(true);
            setFluidStack.invoke(fluidTankSlot, stack);
        } catch (Throwable ignored) {
        }
    }
}

