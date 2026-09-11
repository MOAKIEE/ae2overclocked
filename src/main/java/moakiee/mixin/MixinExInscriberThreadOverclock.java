package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.recipes.handlers.InscriberRecipe;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import moakiee.ae2oc.compat.ae2.InscriberAdapter;
import moakiee.ae2oc.compat.extendedae.InscriberThreadState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.me.InscriberThread", remap = false)
public abstract class MixinExInscriberThreadOverclock implements InscriberThreadState {
    @Shadow @Final private TileExInscriber host;
    @Shadow public abstract InternalInventory getInternalInventory();
    @Shadow public abstract InscriberRecipe getTask();
    @Unique private InscriberAdapter ae2oc_adapter;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_attach(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.attach(getInternalInventory(), host);
    }

    @Override public InscriberAdapter ae2oc$getAdapter() {
        if (ae2oc_adapter == null) ae2oc_adapter = new InscriberAdapter(host, host, getInternalInventory(), this::getTask, 4);
        return ae2oc_adapter;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ae2oc_tick(CallbackInfoReturnable<TickRateModulation> cir) {
        var result = ae2oc$getAdapter().tick();
        if (result != null) {
            // The custom path replaces the upstream body, so it must also run the lane's auto-export.
            if (moakiee.ae2oc.compat.extendedae.ExtendedInscriberExport.push(host, getInternalInventory()))
                result = TickRateModulation.URGENT;
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "pushOutResult", at = @At("HEAD"), cancellable = true)
    private void ae2oc_exportLogicalOutput(CallbackInfoReturnable<Boolean> cir) {
        if (moakiee.ae2oc.compat.ae2.ManagedItemStorages.isManaged(getInternalInventory()))
            cir.setReturnValue(moakiee.ae2oc.compat.extendedae.ExtendedInscriberExport.push(host, getInternalInventory()));
    }

    @Inject(method = "isSleep", at = @At("HEAD"), cancellable = true)
    private void ae2oc_keepOwnedBatchScheduled(CallbackInfoReturnable<Boolean> cir) {
        if (ae2oc_adapter != null && ae2oc_adapter.hasPendingBatch()) cir.setReturnValue(false);
    }
}
