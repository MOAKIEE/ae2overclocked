package moakiee.mixin;

import appeng.api.config.Actionable;
import moakiee.Ae2OcConfig;
import moakiee.support.EnergyCardRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.machine.component.EnergyComponent", remap = false)
public class MixinAE2CSEnergyComponent {

    @Unique
    private double ae2oc$originalMaxPower = -1.0;

    @Inject(method = "injectAEPower", at = @At("HEAD"))
    private void ae2oc_beforeInjectAEPower(double amt, Actionable mode, CallbackInfoReturnable<Double> cir) {
        ae2oc_updateStoredMaximum();
    }

    @Inject(method = "getAEMaxPower", at = @At("RETURN"), cancellable = true)
    private void ae2oc_afterGetAEMaxPower(CallbackInfoReturnable<Double> cir) {
        ae2oc_updateStoredMaximum();
        if (EnergyCardRuntime.hasEnergyCard(ae2oc_getMachineHost())) {
            cir.setReturnValue(Ae2OcConfig.getSuperEnergyBufferAE());
        }
    }

    @Unique
    private void ae2oc_updateStoredMaximum() {
        Object host = ae2oc_getMachineHost();
        if (host == null) {
            return;
        }

        Object storedEnergy = ae2oc_getFieldRecursive(this, "storedEnergy");
        if (storedEnergy == null) {
            return;
        }

        Double currentMaxObj = ae2oc_invokeDouble(storedEnergy, "getMaximum");
        if (currentMaxObj == null) {
            return;
        }

        double currentMax = currentMaxObj;
        double configuredMax = Ae2OcConfig.getSuperEnergyBufferAE();
        boolean hasCard = EnergyCardRuntime.hasEnergyCard(host);

        if (hasCard) {
            if (this.ae2oc$originalMaxPower < 0 || currentMax < configuredMax) {
                this.ae2oc$originalMaxPower = currentMax;
            }
            if (currentMax < configuredMax) {
                ae2oc_invokeVoid(storedEnergy, "setMaximum", configuredMax);
            }
        } else {
            if (this.ae2oc$originalMaxPower > 0 && currentMax > this.ae2oc$originalMaxPower) {
                ae2oc_invokeVoid(storedEnergy, "setMaximum", this.ae2oc$originalMaxPower);

                Double amountObj = ae2oc_invokeDouble(storedEnergy, "getAmount");
                if (amountObj != null && amountObj > this.ae2oc$originalMaxPower) {
                    ae2oc_invokeVoid(storedEnergy, "setStored", this.ae2oc$originalMaxPower);
                }
            }
        }
    }

    @Unique
    private Object ae2oc_getMachineHost() {
        Object container = ae2oc_getFieldRecursive(this, "container");
        if (container == null) {
            return null;
        }

        Object host = ae2oc_tryInvokeNoArg(container, "host");
        if (host != null) {
            return host;
        }

        return ae2oc_tryInvokeNoArg(container, "getHost");
    }

    @Unique
    private static Object ae2oc_getFieldRecursive(Object target, String fieldName) {
        if (target == null) {
            return null;
        }

        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    @Unique
    private static Object ae2oc_tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static Double ae2oc_invokeDouble(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            if (result instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private static void ae2oc_invokeVoid(Object target, String methodName, double value) {
        try {
            Method method = target.getClass().getMethod(methodName, double.class);
            method.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }
}