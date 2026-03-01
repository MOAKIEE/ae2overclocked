package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiCrystalAssembler", remap = false)
public class MixinGuiCrystalAssemblerTooltip {

    @Unique
    private static final int AE2OC_DEFAULT_TANK_MB = 16_000;

    @ModifyConstant(method = "renderTooltip", constant = @Constant(intValue = 16000), require = 0)
    private int ae2oc_replaceTankTooltipCapacity(int original) {
        try {
            Object menu = ae2oc_invokeNoArg(this, "getMenu");
            if (menu == null) {
                return original;
            }

            Object host = ae2oc_invokeNoArg(menu, "getHost");
            if (host == null) {
                return original;
            }

            Object tankObj = ae2oc_invokeNoArg(host, "getTank");
            if (tankObj instanceof GenericStackInv tankInv) {
                long capacity = tankInv.getCapacity(AEKeyType.fluids());
                if (capacity > 0) {
                    return (int) Math.min(capacity, Integer.MAX_VALUE);
                }
            }
        } catch (Exception ignored) {
        }
        return AE2OC_DEFAULT_TANK_MB;
    }

    @Unique
    private static Object ae2oc_invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
