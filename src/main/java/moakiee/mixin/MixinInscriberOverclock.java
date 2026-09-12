package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.ae2oc.compat.ae2.InscriberAdapter;
import moakiee.ae2oc.compat.ae2.InscriberVisualState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InscriberBlockEntity.class, remap = false)
public abstract class MixinInscriberOverclock implements InscriberVisualState {
    @Shadow public abstract InternalInventory getInternalInventory();
    @Shadow public abstract InscriberRecipe getTask();
    @Shadow private int processingTime;
    @Unique private InscriberAdapter ae2oc_adapter;
    @Unique private boolean ae2oc_visualSmash;
    @Unique private ItemStack ae2oc_visualResult = ItemStack.EMPTY;

    @Override
    public ItemStack ae2oc$visualResult() {
        return ae2oc_visualResult;
    }

    @Unique private InscriberAdapter ae2oc_adapter() {
        if (ae2oc_adapter == null) {
            var self = (InscriberBlockEntity) (Object) this;
            ae2oc_adapter = new InscriberAdapter(self, self, getInternalInventory(), this::getTask);
        }
        return ae2oc_adapter;
    }

    @Inject(method = "getTickingRequest", at = @At("RETURN"), cancellable = true)
    private void ae2oc_keepOwnedBatchScheduled(IGridNode node,
            CallbackInfoReturnable<appeng.api.networking.ticking.TickingRequest> cir) {
        if (ae2oc_adapter == null || !ae2oc_adapter.hasPendingBatch()) return;
        var request = cir.getReturnValue();
        cir.setReturnValue(new appeng.api.networking.ticking.TickingRequest(
                request.minTickRate(), request.maxTickRate(), false, request.canBeAlerted(), request.initialTickRate()));
    }

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_tick(IGridNode node, int elapsed, CallbackInfoReturnable<TickRateModulation> cir) {
        var self = (InscriberBlockEntity) (Object) this;
        // A vanilla batch owns its settlement from smash start through finalStep 16. Let upstream
        // finish that transaction before an upgrade can hand subsequent work to the shared processor.
        if (self.isSmash()) return;
        var result = ae2oc_adapter().tick();
        if (result != null) {
            if (moakiee.ae2oc.compat.ae2.InscriberExport.push(self))
                result = TickRateModulation.URGENT;
            var state = ae2oc_adapter.processing();
            processingTime = state == null ? 0 : state.paidProgress(self.getMaxProcessingTime());
            var completed = ae2oc_adapter.pollCompletedPrimaryOutput();
            if (completed != null && completed.key() instanceof appeng.api.stacks.AEItemKey item) {
                ae2oc_visualResult = item.toStack(1);
                ae2oc_visualSmash = true;
                ((InscriberBlockEntity) (Object) this).markForUpdate();
            }
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "writeToStream", at = @At("TAIL"))
    private void ae2oc_writeVisualResult(FriendlyByteBuf data, CallbackInfo ci) {
        data.writeBoolean(ae2oc_visualSmash);
        data.writeItem(ae2oc_visualResult);
        ae2oc_visualSmash = false;
        ae2oc_visualResult = ItemStack.EMPTY;
    }

    @Inject(method = "readFromStream", at = @At("TAIL"))
    private void ae2oc_readVisualResult(FriendlyByteBuf data, CallbackInfoReturnable<Boolean> cir) {
        boolean visualSmash = data.readBoolean();
        ae2oc_visualResult = data.readItem();
        if (visualSmash) {
            var self = (InscriberBlockEntity) (Object) this;
            // Restart even if a fast preceding animation has not yet cleared itself client-side.
            self.setSmash(false);
            self.setSmash(true);
        }
    }

    @Inject(method = "pushOutResult", at = @At("HEAD"), cancellable = true)
    private void ae2oc_exportLogicalOutput(CallbackInfoReturnable<Boolean> cir) {
        if (moakiee.ae2oc.compat.ae2.ManagedItemStorages.isManaged(getInternalInventory()))
            cir.setReturnValue(moakiee.ae2oc.compat.ae2.InscriberExport.push((InscriberBlockEntity) (Object) this));
    }

    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("TAIL"), require = 1)
    private void ae2oc_save(CompoundTag tag, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.save(ae2oc_items(), tag, "ae2ocLongSlots");
        if (ae2oc_adapter != null) ae2oc_adapter.save(tag);
    }

    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(CompoundTag tag, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.load(ae2oc_items(), tag, "ae2ocLongSlots");
        if (ae2oc_adapter != null || tag.contains("ae2ocProcessing")) ae2oc_adapter().load(tag);
        ae2oc_visualSmash = false;
        ae2oc_visualResult = ItemStack.EMPTY;
    }

    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(Level level, BlockPos pos, List<ItemStack> drops, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.addHiddenDrops(ae2oc_items(), drops);
        if (ae2oc_adapter != null) ae2oc_adapter.addDrops(drops);
    }
    @Unique private appeng.api.inventories.InternalInventory ae2oc_items() {
        var self = (InscriberBlockEntity) (Object) this;
        return new appeng.util.inv.CombinedInternalInventory(self.getInternalInventory());
    }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_attachItems(CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.attach(ae2oc_items(), (InscriberBlockEntity) (Object) this);
    }
}
