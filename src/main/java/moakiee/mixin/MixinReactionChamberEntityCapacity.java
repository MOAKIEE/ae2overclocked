package moakiee.mixin;

import moakiee.support.CapacityCardRuntime;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public class MixinReactionChamberEntityCapacity {

    private static final long AE2OC_DEFAULT_FLUID_CAPACITY = 16_000L;
    private static final long AE2OC_MAX_FLUID_CAPACITY = Integer.MAX_VALUE;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_afterCtor(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }

    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_afterLoadTag(CompoundTag data, CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }

    @Inject(method = "onChangeInventory", at = @At("TAIL"))
    private void ae2oc_onUpgradeChange(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }

    @Inject(method = "onChangeTank", at = @At("HEAD"))
    private void ae2oc_onTankChange(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }
}