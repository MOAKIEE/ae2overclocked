package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.me.energy.StoredEnergyAmount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.EnergyCardRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Adds Super Energy Card support to AE2CS machines.
 * <p>
 * AE2CS machines do NOT inherit from AE2's AEBasePoweredBlockEntity; instead they
 * use a component-based architecture where EnergyComponent holds a StoredEnergyAmount.
 * The existing MixinAEBasePoweredBlockEntity has no effect on these machines.
 * <p>
 * Since EnergyComponent's key methods (getAEMaxPower, injectAEPower, etc.) are declared
 * {@code final}, we cannot inject into them directly. Instead, we target
 * AENetworkedSelfPoweredBlockEntity, which delegates to EnergyComponent with non-final
 * wrapper methods like getAEMaxPower(), injectAEPower(), extractAEPower().
 */
@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.AENetworkedSelfPoweredBlockEntity", remap = false)
public abstract class MixinAECSEnergyComponent {

    @Unique
    private double ae2oc_originalMaxPower = -1;

    /**
     * Intercept getAEMaxPower() to return the expanded buffer when an energy card is installed.
     */
    @Inject(method = "getAEMaxPower", at = @At("RETURN"), cancellable = true, require = 0)
    private void ae2oc_getAEMaxPower(CallbackInfoReturnable<Double> cir) {
        ae2oc_syncEnergyMax();
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            cir.setReturnValue(Ae2OcConfig.getSuperEnergyBufferAE());
        }
    }

    /**
     * Ensure expanded capacity is in place before power injection.
     */
    @Inject(method = "injectAEPower", at = @At("HEAD"), require = 0)
    private void ae2oc_beforeInjectPower(double amt, Actionable mode, CallbackInfoReturnable<Double> cir) {
        ae2oc_syncEnergyMax();
    }

    /**
     * Ensure expanded capacity is in place before power extraction.
     */
    @Inject(method = "extractAEPower(DLappeng/api/config/Actionable;)D", at = @At("HEAD"), require = 0)
    private void ae2oc_beforeExtractPower(double amt, Actionable mode, CallbackInfoReturnable<Double> cir) {
        ae2oc_syncEnergyMax();
    }

    /**
     * Synchronise the StoredEnergyAmount maximum with the energy card state.
     * When a card is installed, expand the internal maximum; when removed, restore.
     */
    @Unique
    private void ae2oc_syncEnergyMax() {
        try {
            Object energyComponent = ae2oc_getEnergyComponent();
            if (energyComponent == null) return;

            Field storedField = ae2oc_findField(energyComponent.getClass(), "storedEnergy");
            storedField.setAccessible(true);
            Object stored = storedField.get(energyComponent);
            if (!(stored instanceof StoredEnergyAmount sea)) return;

            double currentMax = sea.getMaximum();
            double configuredMax = Ae2OcConfig.getSuperEnergyBufferAE();

            if (EnergyCardRuntime.hasEnergyCard(this)) {
                if (ae2oc_originalMaxPower < 0 || currentMax < configuredMax) {
                    if (ae2oc_originalMaxPower < 0) {
                        ae2oc_originalMaxPower = currentMax;
                    }
                }
                if (currentMax < configuredMax) {
                    sea.setMaximum(configuredMax);
                }
            } else {
                // Card removed: restore
                if (ae2oc_originalMaxPower > 0 && currentMax > ae2oc_originalMaxPower) {
                    sea.setMaximum(ae2oc_originalMaxPower);
                    // Drop overflow
                    if (sea.getAmount() > ae2oc_originalMaxPower) {
                        sea.setStored(ae2oc_originalMaxPower);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Retrieve the EnergyComponent from the machine component container via reflection.
     * Path: this.getMachineComponents().getService(EnergyComponent.class)
     */
    @Unique
    private Object ae2oc_getEnergyComponent() {
        try {
            Method getMC = this.getClass().getMethod("getMachineComponents");
            Object container = getMC.invoke(this);
            if (container == null) return null;

            // EnergyComponent.class may not be on our classpath as a concrete class ref,
            // so load it by name.
            Class<?> ecClass = Class.forName("io.github.lounode.ae2cs.common.machine.component.EnergyComponent");
            Method getService = container.getClass().getMethod("getService", Class.class);
            return getService.invoke(container, ecClass);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static Field ae2oc_findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            Class<?> parent = clazz.getSuperclass();
            if (parent != null) return ae2oc_findField(parent, name);
            throw e;
        }
    }
}
