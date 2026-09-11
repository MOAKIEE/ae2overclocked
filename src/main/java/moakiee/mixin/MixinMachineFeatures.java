package moakiee.mixin;

import appeng.blockentity.AEBaseBlockEntity;
import moakiee.ae2oc.compat.ae2.MachineFeatures;
import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import moakiee.ae2oc.compat.ae2.UpgradeProfileOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AEBaseBlockEntity.class, remap = false)
public abstract class MixinMachineFeatures implements UpgradeProfileOwner {
    @Unique private UpgradeProfileCache ae2oc_profiles;
    @Unique private boolean ae2oc_refreshing;
    @Override public UpgradeProfileCache ae2oc$upgradeCache() {
        if (ae2oc_profiles == null) ae2oc_profiles = new UpgradeProfileCache();
        return ae2oc_profiles;
    }
    @Inject(method = "saveChanges", at = @At("HEAD"))
    private void ae2oc_featuresChanged(CallbackInfo ci) {
        if (ae2oc_refreshing || !(this instanceof MachineFeatures features)) return;
        ae2oc_refreshing = true;
        try { features.ae2oc$refreshEnergy(); features.ae2oc$refreshCapacity(); }
        finally { ae2oc_refreshing = false; }
    }
}
