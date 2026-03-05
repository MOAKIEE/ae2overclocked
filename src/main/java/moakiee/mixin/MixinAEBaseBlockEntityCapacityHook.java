package moakiee.mixin;

import appeng.blockentity.AEBaseBlockEntity;
import moakiee.support.CapacityCardRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AEBaseBlockEntity.class, remap = false)
public abstract class MixinAEBaseBlockEntityCapacityHook {

    private static final String AE2OC_REACTION_CHAMBER_CLASS =
        "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity";
    private static final long AE2OC_DEFAULT_FLUID_CAPACITY = 16_000L;
    private static final long AE2OC_MAX_FLUID_CAPACITY = Integer.MAX_VALUE;

    @Inject(method = "saveChanges", at = @At("TAIL"))
    private void ae2oc_afterSaveChanges(CallbackInfo ci) {
        Object self = this;
        if (!self.getClass().getName().equals(AE2OC_REACTION_CHAMBER_CLASS)) {
            return;
        }

        CapacityCardRuntime.applyFluidCapacity(self, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }
}