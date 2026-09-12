package moakiee.mixin;

import io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity;
import io.github.lounode.ae2cs.common.init.AECSRecipeTypes;
import io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;
import moakiee.ae2oc.compat.ae2cs.ItemRecipes;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity", remap = false)
public abstract class MixinAE2CSEntropyProcessing {
    @Unique private MachineProcessorAdapter ae2oc_adapter;
    @Unique private MachineProcessorAdapter ae2oc_adapter() {
        if (ae2oc_adapter == null) {
            var self = (EntropyVariationReactionChamberBlockEntity) (Object) this;
            ae2oc_adapter = new moakiee.ae2oc.compat.ae2cs.EntropyAdapter(self);

        }
        return ae2oc_adapter;
    }
    // Preserve upstream component ticking, intercept only the machine-specific recipe body.
    @Inject(method = "serverTick", at = @At(value = "INVOKE", target = "Lio/github/lounode/ae2cs/common/block/entity/AENetworkedSelfPoweredBlockEntity;serverTick()V", shift = At.Shift.AFTER), cancellable = true)
    private void ae2oc_tick(CallbackInfo ci) {
        var self = (EntropyVariationReactionChamberBlockEntity) (Object) this;
        var adapter = ae2oc_adapter();
        boolean powered = self.getAECurrentPower() > 0;
        if (adapter.tick() != null) {
            moakiee.ae2oc.compat.ae2cs.AE2CSStateSync.sync(self, powered, adapter);
            ci.cancel();
        }
    }
    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("TAIL"), require = 1)
    private void ae2oc_save(CompoundTag tag, CallbackInfo ci) {
        if (ae2oc_adapter != null) ae2oc_adapter.save(tag);
    }
    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(CompoundTag tag, CallbackInfo ci) {
        if (ae2oc_adapter != null || tag.contains("ae2ocProcessing")) ae2oc_adapter().load(tag);
    }
    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                            java.util.List<net.minecraft.world.item.ItemStack> drops, CallbackInfo ci) {
        if (ae2oc_adapter != null) ae2oc_adapter.addDrops(drops);
    }
}