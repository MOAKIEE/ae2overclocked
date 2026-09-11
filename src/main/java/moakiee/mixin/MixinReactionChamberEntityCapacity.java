package moakiee.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import moakiee.ae2oc.compat.ae2.MachineFeatures;
import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import moakiee.support.OverstackingRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberEntityCapacity implements MachineFeatures {
    @Shadow public abstract GenericStackInv getTank();
    @Override public void ae2oc$refreshCapacity() {
        var tank = getTank();
        if (tank == null) return;
        OverstackingRegistry.register(tank);
        var profile = UpgradeProfileCache.of(this);
        long capacity = profile.capacity() ? profile.capacityLimit() : 16000;
        if (tank.getCapacity(AEKeyType.fluids()) != capacity) tank.setCapacity(AEKeyType.fluids(), capacity);
    }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_construct(CallbackInfo ci) { ae2oc$refreshCapacity(); }
    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) { ae2oc$refreshCapacity(); }
}
