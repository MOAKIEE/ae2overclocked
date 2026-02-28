package moakiee.mixin;

import moakiee.support.CapacityCardRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 修复反应仓流体条溢出和容量显示错误。
 * <p>
 * 原版 FluidTankSlot.maxLevel = 16 (桶)，当实际流体超过 16B 时——
 *  1. 流体条超出顶部边界（ratio > 1.0 无 clamp）
 *  2. Tooltip 显示 "XX/16 b"，与实际容量不符
 * <p>
 * 本 mixin 在每帧渲染前动态调整 FluidTankSlot.maxLevel：
 *  - 有容量卡：max(256, currentBuckets + 16)
 *  - 无容量卡：保持默认 16
 * 并重新触发 tooltip 更新。
 */
@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen", remap = false)
public class MixinReactionChamberScreen {

    private static final int AE2OC_DEFAULT_BUCKETS = 16;
    private static final int AE2OC_UPGRADED_MIN_BUCKETS = 256;

    @Unique
    private int ae2oc_lastInputMax = -1;
    @Unique
    private int ae2oc_lastOutputMax = -1;

    // Unsafe 实例，用于修改 final int 字段
    @Unique
    private static final sun.misc.Unsafe AE2OC_UNSAFE;
    @Unique
    private static long ae2oc_maxLevelOffset = -1;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            AE2OC_UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (Throwable e) {
            throw new RuntimeException("AE2OC: Failed to get Unsafe", e);
        }
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void ae2oc_syncFluidMaxLevel(CallbackInfo ci) {
        try {
            // 获取 Menu → Host
            Object menu = ae2oc_getMenu(this);
            if (menu == null) return;
            Object host = ae2oc_invokeNoArg(menu, "getHost");
            if (host == null) return;

            int cards = CapacityCardRuntime.getInstalledCapacityCards(host);

            // 读取两个 FluidTankSlot
            Object inputSlot = ae2oc_getFieldValue(this, "inputSlot");
            Object outputSlot = ae2oc_getFieldValue(this, "outputSlot");

            int inputBuckets = ae2oc_readContentBuckets(inputSlot);
            int outputBuckets = ae2oc_readContentBuckets(outputSlot);

            int inputMax = cards > 0
                    ? Math.max(AE2OC_UPGRADED_MIN_BUCKETS, inputBuckets + 16)
                    : AE2OC_DEFAULT_BUCKETS;
            int outputMax = cards > 0
                    ? Math.max(AE2OC_UPGRADED_MIN_BUCKETS, outputBuckets + 16)
                    : AE2OC_DEFAULT_BUCKETS;

            if (inputMax != ae2oc_lastInputMax) {
                ae2oc_setMaxLevelAndRefresh(inputSlot, inputMax);
                ae2oc_lastInputMax = inputMax;
            }
            if (outputMax != ae2oc_lastOutputMax) {
                ae2oc_setMaxLevelAndRefresh(outputSlot, outputMax);
                ae2oc_lastOutputMax = outputMax;
            }
        } catch (Throwable ignored) {
        }
    }

    // ——— 反射工具 ———

    private static Object ae2oc_getMenu(Object screen) {
        // AbstractContainerScreen.getMenu() → SRG: m_6262_
        for (String name : new String[]{"getMenu", "m_6262_"}) {
            Object result = ae2oc_invokeNoArg(screen, name);
            if (result != null) return result;
        }
        return null;
    }

    private static int ae2oc_readContentBuckets(Object fluidTankSlot) {
        if (fluidTankSlot == null) return 0;
        try {
            Field contentField = fluidTankSlot.getClass().getDeclaredField("content");
            contentField.setAccessible(true);
            Object fluidStack = contentField.get(fluidTankSlot);
            if (fluidStack == null) return 0;

            // FluidStack.getAmount()
            Method getAmount = fluidStack.getClass().getMethod("getAmount");
            Object amt = getAmount.invoke(fluidStack);
            if (amt instanceof Number n) {
                return (int) (n.longValue() / 1000);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void ae2oc_setMaxLevelAndRefresh(Object fluidTankSlot, int newMax) {
        if (fluidTankSlot == null) return;
        try {
            // 用 Unsafe 绕过 final 限制写入 maxLevel
            if (ae2oc_maxLevelOffset == -1) {
                Field f = fluidTankSlot.getClass().getDeclaredField("maxLevel");
                ae2oc_maxLevelOffset = AE2OC_UNSAFE.objectFieldOffset(f);
            }
            AE2OC_UNSAFE.putInt(fluidTankSlot, ae2oc_maxLevelOffset, newMax);

            // 重新触发 tooltip 更新：调用 setFluidStack(当前内容)
            Field contentField = fluidTankSlot.getClass().getDeclaredField("content");
            contentField.setAccessible(true);
            Object currentContent = contentField.get(fluidTankSlot);
            if (currentContent != null) {
                Method setFluidStack = fluidTankSlot.getClass().getMethod("setFluidStack",
                        net.minecraftforge.fluids.FluidStack.class);
                setFluidStack.invoke(fluidTankSlot, currentContent);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object ae2oc_invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object ae2oc_getFieldValue(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
