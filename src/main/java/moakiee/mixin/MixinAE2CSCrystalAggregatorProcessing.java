package moakiee.mixin;

import io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity;
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
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity", remap = false)
public abstract class MixinAE2CSCrystalAggregatorProcessing {
    @Unique private MachineProcessorAdapter ae2oc_adapter;
    @Unique private MachineProcessorAdapter ae2oc_adapter() {
        if (ae2oc_adapter == null) {
            var self = (CrystalAggregatorBlockEntity) (Object) this;
            ae2oc_adapter = new MachineProcessorAdapter(self, self, self.getOutputInv(), () -> {
                if (self.getLevel() == null) return null;
                var inv = self.getInputInv();
                var input = ThreeItemStackRecipeInput.of(inv.getStackInSlot(0), inv.getStackInSlot(1), inv.getStackInSlot(2));
                var found = self.getLevel().getRecipeManager().getRecipeFor(AECSRecipeTypes.CRYSTAL_AGGREGATOR.get(), input, self.getLevel());
                if (found.isEmpty()) return null;
                var recipe = found.get();
                return ItemRecipes.snapshot(recipe.getId().toString(), inv, self, recipe.findMatch(input), recipe.required(),
                        recipe.assemble(input, self.getLevel().registryAccess()), recipe.energyCost());
            }, 1, 0);
        }
        return ae2oc_adapter;
    }
    // Preserve upstream component ticking, intercept only the machine-specific recipe body.
    @Inject(method = "serverTick", at = @At(value = "INVOKE", target = "Lio/github/lounode/ae2cs/common/block/entity/AENetworkedSelfPoweredBlockEntity;serverTick()V", shift = At.Shift.AFTER), cancellable = true)
    private void ae2oc_tick(CallbackInfo ci) {
        if (ae2oc_adapter().tick() != null) ci.cancel();
    }
    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("TAIL"), require = 1)
    private void ae2oc_save(CompoundTag tag, CallbackInfo ci) {
        if (ae2oc_adapter != null) ae2oc_adapter.save(tag);
    }
    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("ae2ocProcessing")) ae2oc_adapter().load(tag);
    }
    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                            java.util.List<net.minecraft.world.item.ItemStack> drops, CallbackInfo ci) {
        if (ae2oc_adapter != null) ae2oc_adapter.addDrops(drops);
    }
}