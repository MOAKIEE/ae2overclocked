package moakiee.mixin;

import appeng.blockentity.powersink.AEBasePoweredBlockEntity;
import appeng.me.energy.StoredEnergyAmount;
import moakiee.ae2oc.compat.ae2.MachineFeatures;
import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AEBasePoweredBlockEntity.class, remap = false)
public abstract class MixinAEBasePoweredBlockEntity implements MachineFeatures {
    @Shadow @Final private StoredEnergyAmount stored;
    @Unique private double ae2oc_baseMaximum = -1;
    @Override public void ae2oc$refreshEnergy() {
        if (stored == null) return;
        if (ae2oc_baseMaximum < 0) ae2oc_baseMaximum = stored.getMaximum();
        var profile = UpgradeProfileCache.of(this);
        double target = profile.energy() ? profile.energyCapacity() : ae2oc_baseMaximum;
        if (stored.getMaximum() != target) {
            stored.setMaximum(target);
            if (stored.getAmount() > target) stored.setStored(target);
        }
    }
    @Inject(method = "getInternalMaxPower", at = @At("HEAD"))
    private void ae2oc_capacity(CallbackInfoReturnable<Double> cir) { ae2oc$refreshEnergy(); }
    @Inject(method = "injectAEPower", at = @At("HEAD"))
    private void ae2oc_charge(double amount, appeng.api.config.Actionable mode, CallbackInfoReturnable<Double> cir) { ae2oc$refreshEnergy(); }
}
