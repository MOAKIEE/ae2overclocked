package moakiee.mixin;

import appeng.me.energy.StoredEnergyAmount;
import io.github.lounode.ae2cs.common.machine.component.BaseMachineComponent;
import io.github.lounode.ae2cs.common.machine.MachineComponentContainer;
import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.machine.component.EnergyComponent", remap = false)
public abstract class MixinAE2CSEnergyComponent extends BaseMachineComponent {
    @Shadow @Final private StoredEnergyAmount storedEnergy;
    @Unique private double ae2oc_baseMaximum = -1;
    @Unique private void ae2oc_refresh() {
        if (container == null || storedEnergy == null) return;
        if (ae2oc_baseMaximum < 0) ae2oc_baseMaximum = storedEnergy.getMaximum();
        var profile = UpgradeProfileCache.of(container.host());
        double target = profile.energy() ? profile.energyCapacity() : ae2oc_baseMaximum;
        if (storedEnergy.getMaximum() != target) {
            storedEnergy.setMaximum(target);
            if (storedEnergy.getAmount() > target) storedEnergy.setStored(target);
        }
    }
    @Inject(method = "onConstruct", at = @At("TAIL"))
    private void ae2oc_construct(MachineComponentContainer container, CallbackInfo ci) { ae2oc_refresh(); }
    @Inject(method = "getAEMaxPower", at = @At("HEAD"))
    private void ae2oc_capacity(CallbackInfoReturnable<Double> cir) { ae2oc_refresh(); }
    @Inject(method = "injectAEPower", at = @At("HEAD"))
    private void ae2oc_charge(double amount, appeng.api.config.Actionable mode, CallbackInfoReturnable<Double> cir) { ae2oc_refresh(); }
}
