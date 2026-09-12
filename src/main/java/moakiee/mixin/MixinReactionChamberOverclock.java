package moakiee.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.me.storage.CompositeStorage;
import net.minecraft.core.Direction;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;
import moakiee.ae2oc.compat.advancedae.ReactionAdapter;
import org.spongepowered.asm.mixin.Pseudo;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberOverclock {


    @Unique private MachineProcessorAdapter ae2oc_adapter;

    /** Upstream export target for one configured output side. */
    @Invoker("getTarget")
    protected abstract CompositeStorage ae2oc$target(Direction direction);

    @Unique private MachineProcessorAdapter ae2oc_adapter() {
        if (ae2oc_adapter == null) {
            var self = (ReactionChamberEntity) (Object) this;
            ae2oc_adapter = new ReactionAdapter(self);
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
        var result = ae2oc_adapter().tick();
        if (result != null) {
            if (moakiee.ae2oc.compat.advancedae.ReactionExport.push((ReactionChamberEntity) (Object) this, this::ae2oc$target))
                result = TickRateModulation.URGENT;
            cir.setReturnValue(result);
        }
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
    }

    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(Level level, BlockPos pos, List<ItemStack> drops, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.addHiddenDrops(ae2oc_items(), drops);
        // Tank fluids are intentionally discarded on destruction.
        if (ae2oc_adapter != null) ae2oc_adapter.addDrops(drops);
    }
    @Unique private appeng.api.inventories.InternalInventory ae2oc_items() {
        var self = (ReactionChamberEntity) (Object) this;
        return new appeng.util.inv.CombinedInternalInventory(self.getInput(), self.getOutput());
    }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_attachItems(CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.attach(ae2oc_items(), (ReactionChamberEntity) (Object) this);
    }
}
