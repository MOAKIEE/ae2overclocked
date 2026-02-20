package moakiee.mixin;

import appeng.api.stacks.GenericStack;
import appeng.core.localization.Tooltips;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 修复电路切割机流体 Tooltip 显示错误。
 * <p>
 * 原版 GuiCircuitCutter.renderTooltip 使用编译时常量 TileCircuitCutter.TANK_CAP = 16000，
 * 无法显示容量卡扩容后的实际容量。
 * <p>
 * 本 mixin 替换 renderTooltip 逻辑，从实际 GenericStackInv 读取流体容量。
 */
@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiCircuitCutter", remap = false)
public class MixinGuiCircuitCutter {

    @Inject(
            method = {
                    "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V",
                    "m_280072_(Lnet/minecraft/client/gui/GuiGraphics;II)V"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_renderTooltip(GuiGraphics guiGraphics, int x, int y, CallbackInfo ci) {
        try {
            // 获取 menu
            Object menu = ae2oc_getMenu(this);
            if (menu == null) return;

            // 获取 carried (鼠标上的物品)
            ItemStack carried = ae2oc_invokeReturning(menu, ItemStack.class, "getCarried", "m_142621_");
            if (carried == null || !carried.isEmpty()) return; // 有物品在鼠标上时不干预

            // 获取 hoveredSlot
            Slot hoveredSlot = ae2oc_getFieldReturning(this, Slot.class, "hoveredSlot", "f_97734_");
            if (hoveredSlot == null || !hoveredSlot.isActive() || !hoveredSlot.hasItem()) return;

            // 检查是否是 tank 槽 — isTank 是 ExtendedAE 自己的方法，不受 SRG 影响
            Boolean isTank = ae2oc_invokeWithArg(menu, "isTank", hoveredSlot, Slot.class);
            if (isTank == null || !isTank) return;

            // 使用 Integer.MAX_VALUE 作为容量上限
            long actualCap = Integer.MAX_VALUE;

            // 构建 tooltip
            ItemStack slotItem = hoveredSlot.getItem();
            @SuppressWarnings("unchecked")
            List<Component> baseTooltip = ae2oc_invokeReturning(this, List.class,
                    "getTooltipFromContainerItem", "m_280553_", slotItem, ItemStack.class);
            if (baseTooltip == null) return;

            List<Component> itemTooltip = new ArrayList<>(baseTooltip);
            var unwrapped = GenericStack.fromItemStack(slotItem);
            long amt = unwrapped != null ? unwrapped.amount() : 0;
            itemTooltip.add(Component.translatable("gui.expatternprovider.tank_amount", amt, actualCap)
                    .withStyle(Tooltips.NORMAL_TOOLTIP_TEXT));

            // 绘制 tooltip — drawTooltip 是 AE2 方法，不受 SRG 影响
            ae2oc_invokeDrawTooltip(this, guiGraphics, x, y, itemTooltip);

            ci.cancel();
        } catch (Throwable ignored) {
            // 反射失败时让原版方法继续
        }
    }

    // ——— 反射工具 ———

    private static Object ae2oc_getMenu(Object screen) {
        for (String name : new String[]{"getMenu", "m_6262_"}) {
            Object result = ae2oc_invokeNoArg(screen, name);
            if (result != null) return result;
        }
        return null;
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

    @SuppressWarnings("unchecked")
    private static <T> T ae2oc_invokeWithArg(Object target, String methodName, Object arg, Class<?> argType) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName, argType);
            return (T) m.invoke(target, arg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T ae2oc_invokeReturning(Object target, Class<T> returnType, String... namesAndArg) {
        if (target == null) return null;
        // namesAndArg: method names to try (no-arg)
        for (String name : namesAndArg) {
            try {
                Method m = target.getClass().getMethod(name);
                Object result = m.invoke(target);
                if (returnType.isInstance(result)) return (T) result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T ae2oc_invokeReturning(Object target, Class<T> returnType,
                                                String name1, String name2,
                                                Object arg, Class<?> argType) {
        if (target == null) return null;
        for (String name : new String[]{name1, name2}) {
            try {
                Method m = target.getClass().getMethod(name, argType);
                Object result = m.invoke(target, arg);
                if (returnType.isInstance(result)) return (T) result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T ae2oc_getFieldReturning(Object target, Class<T> type, String... fieldNames) {
        if (target == null) return null;
        for (String name : fieldNames) {
            try {
                Field f = ae2oc_findField(target.getClass(), name);
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (type.isInstance(val)) return (T) val;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Field ae2oc_findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void ae2oc_invokeDrawTooltip(Object screen, GuiGraphics g, int x, int y, List<Component> lines) {
        // drawTooltip 是 AE2 自有方法，不受 SRG 影响
        try {
            Method m = screen.getClass().getMethod("drawTooltip",
                    GuiGraphics.class, int.class, int.class, List.class);
            m.invoke(screen, g, x, y, lines);
        } catch (Throwable ignored) {
        }
    }
}
