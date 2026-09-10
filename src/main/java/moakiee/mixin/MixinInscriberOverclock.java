package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.ae2oc.compat.ae2.InscriberAdapter;
import net.minecraft.nbt.CompoundTag;
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
public abstract class MixinInscriberOverclock {
    @Shadow public abstract InternalInventory getInternalInventory();
    @Shadow public abstract InscriberRecipe getTask();
    @Unique private InscriberAdapter ae2oc_adapter;

    @Unique private InscriberAdapter ae2oc_adapter() {
        if (ae2oc_adapter == null) {
            var self = (InscriberBlockEntity) (Object) this;
            ae2oc_adapter = new InscriberAdapter(self, self, getInternalInventory(), this::getTask);
        }
        return ae2oc_adapter;
    }

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_tick(IGridNode node, int elapsed, CallbackInfoReturnable<TickRateModulation> cir) {
        var result = ae2oc_adapter().tick();
        if (result != null) cir.setReturnValue(result);
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
    private void ae2oc_drops(Level level, BlockPos pos, List<ItemStack> drops, CallbackInfo ci) {
        if (ae2oc_adapter != null) ae2oc_adapter.addDrops(drops);
    }
}
