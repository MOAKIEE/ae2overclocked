package moakiee.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import moakiee.ae2oc.compat.ae2.MachineFeatures;
import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import moakiee.support.OverstackingRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity", remap = false)
public abstract class MixinAE2CSEntropyVariationReactionChamberCapacity implements MachineFeatures {
    @Shadow public abstract GenericStackInv getInputInv();
    @Shadow public abstract GenericStackInv getOutputInv();
    @Unique private long[] ae2oc_base;
    @Override public void ae2oc$refreshCapacity() {
        if (ae2oc_base == null) return;
        var profile = UpgradeProfileCache.of(this);
        GenericStackInv[] inventories = {getInputInv(), getOutputInv()};
        for (int i = 0; i < inventories.length; i++) {
            var inv = inventories[i];
            OverstackingRegistry.register(inv);
            long items = profile.capacity() ? profile.capacityLimit() : ae2oc_base[2 * i];
            long fluids = profile.capacity() ? profile.capacityLimit() : ae2oc_base[2 * i + 1];
            if (inv.getCapacity(AEKeyType.items()) != items) inv.setCapacity(AEKeyType.items(), items);
            if (inv.getCapacity(AEKeyType.fluids()) != fluids) inv.setCapacity(AEKeyType.fluids(), fluids);
        }
    }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_construct(CallbackInfo ci) {
        ae2oc_base = new long[]{getInputInv().getCapacity(AEKeyType.items()), getInputInv().getCapacity(AEKeyType.fluids()),
                getOutputInv().getCapacity(AEKeyType.items()), getOutputInv().getCapacity(AEKeyType.fluids())};
        ae2oc$refreshCapacity();
    }
    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) { ae2oc$refreshCapacity(); }
}
