package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.blockentity.powersink.AEBasePoweredBlockEntity;
import appeng.me.energy.StoredEnergyAmount;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.EnergyCardRuntime;

@Mixin(value = AEBasePoweredBlockEntity.class, remap = false)
public abstract class MixinAEBasePoweredBlockEntity {

    @Unique
    private double ae2ocOriginalMaxPower = -1;

    @Shadow
    @Final
    private StoredEnergyAmount stored;

    @Inject(method = "getInternalMaxPower", at = @At("RETURN"), cancellable = true, require = 0)
    private void ae2oc_getInternalMaxPower(CallbackInfoReturnable<Double> cir) {
        ae2oc_updateStoredMaximum();
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            cir.setReturnValue(Ae2OcConfig.getSuperEnergyBufferAE());
        }
    }

    @Inject(method = "injectAEPower", at = @At("HEAD"), require = 0)
    private void ae2oc_injectAEPower(double amount, Actionable mode, CallbackInfoReturnable<Double> cir) {
        // Ensure internal insert uses the expanded capacity when the card is installed.
        ae2oc_updateStoredMaximum();
    }

    @Inject(method = "getAECurrentPower", at = @At("HEAD"), require = 0)
    private void ae2oc_getAECurrentPower(CallbackInfoReturnable<Double> cir) {
        // Keep max synchronized even when queried by HUD/Jade before injection paths run.
        ae2oc_updateStoredMaximum();
    }

    @Inject(method = "getAEMaxPower", at = @At("HEAD"), require = 0)
    private void ae2oc_getAEMaxPower(CallbackInfoReturnable<Double> cir) {
        // Keep max synchronized even when queried by HUD/Jade before injection paths run.
        ae2oc_updateStoredMaximum();
    }

    @Inject(method = "getInternalCurrentPower", at = @At("RETURN"), cancellable = true, require = 0)
    private void ae2oc_getInternalCurrentPower(CallbackInfoReturnable<Double> cir) {
        double currentPower = cir.getReturnValue();
        double configuredMaxPower = Ae2OcConfig.getSuperEnergyBufferAE();

        double maxPower;
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            maxPower = configuredMaxPower;
        } else {
            maxPower = ae2ocOriginalMaxPower > 0 ? ae2ocOriginalMaxPower : this.stored.getMaximum();
        }

        if (currentPower > maxPower) {
            // Drop overflow energy immediately after card removal.
            this.stored.setStored(maxPower);
            cir.setReturnValue(maxPower);
        }
    }

    @Unique
    private void ae2oc_updateStoredMaximum() {
        double currentMax = this.stored.getMaximum();
        double configuredMaxPower = Ae2OcConfig.getSuperEnergyBufferAE();

        if (EnergyCardRuntime.hasEnergyCard(this)) {
            if (ae2ocOriginalMaxPower < 0 || currentMax < configuredMaxPower) {
                ae2ocOriginalMaxPower = currentMax;
            }

            if (currentMax < configuredMaxPower) {
                this.stored.setMaximum(configuredMaxPower);
            }
            return;
        }

        if (ae2ocOriginalMaxPower > 0 && currentMax > ae2ocOriginalMaxPower) {
            this.stored.setMaximum(ae2ocOriginalMaxPower);
        }
    }
}
