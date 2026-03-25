package xyz.moakiee.ae2_overclocked.mixin;

import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen", remap = false)
public class MixinReactionChamberScreen {

    @Unique
    private static final int AE2OC_DEFAULT_BUCKETS = 16;



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
            Object menu = ae2oc_invokeByNames(this, "getMenu", "m_6262_");
            if (menu == null) {
                return;
            }

            Object host = ae2oc_invokeByNames(menu, "getHost", "host");
            if (host == null) {
                return;
            }

            boolean upgraded = CapacityCardRuntime.getInstalledCapacityCards(host) > 0;
            // Config value is in mB, convert to buckets for FluidTankSlot maxLevel
            int targetMax = upgraded ? (int) Math.min((long) Ae2OcConfig.getCapacityCardSlotLimit() / 1000L, Integer.MAX_VALUE) : AE2OC_DEFAULT_BUCKETS;

            Object inputSlot = ReflectionCache.getFieldValue(this, "inputSlot");
            Object outputSlot = ReflectionCache.getFieldValue(this, "outputSlot");

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
    private static Object ae2oc_invokeByNames(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String name : methodNames) {
            Object result = ReflectionCache.invokeNoArg(target, name);
            if (result != null) {
                return result;
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
                Field maxLevelField = ReflectionCache.getFieldHierarchy(fluidTankSlot.getClass(), "maxLevel");
                if (maxLevelField == null) {
                    return;
                }
                ae2ocMaxLevelOffset = AE2OC_UNSAFE.objectFieldOffset(maxLevelField);
            }

            AE2OC_UNSAFE.putInt(fluidTankSlot, ae2ocMaxLevelOffset, maxLevel);

            Field contentField = ReflectionCache.getFieldHierarchy(fluidTankSlot.getClass(), "content");
            if (contentField == null) {
                return;
            }
            Object content = contentField.get(fluidTankSlot);
            if (!(content instanceof FluidStack stack)) {
                return;
            }

            Method setFluidStack = ReflectionCache.getDeclaredMethodHierarchy(fluidTankSlot.getClass(), "setFluidStack", FluidStack.class);
            if (setFluidStack == null) {
                return;
            }
            setFluidStack.invoke(fluidTankSlot, stack);
        } catch (Throwable ignored) {
        }
    }
}
