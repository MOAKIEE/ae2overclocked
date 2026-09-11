package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter;
import moakiee.ae2oc.compat.extendedae.CutterRecipes;
import org.spongepowered.asm.mixin.Pseudo;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;
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

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter", remap = false)
public abstract class MixinCircuitCutterOverclock {


    @Unique private MachineProcessorAdapter ae2oc_adapter;

    @Unique private MachineProcessorAdapter ae2oc_adapter() {
        if (ae2oc_adapter == null) {
            var self = (TileCircuitCutter) (Object) this;
            ae2oc_adapter = new MachineProcessorAdapter(self, self, self.getOutput(), new CutterRecipes(self), 1, 0);
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
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.save(ae2oc_items(), tag, "ae2ocLongSlots");
        if (ae2oc_adapter != null) ae2oc_adapter.save(tag);
    }

    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(CompoundTag tag, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.load(ae2oc_items(), tag, "ae2ocLongSlots");
        if (tag.contains("ae2ocProcessing")) ae2oc_adapter().load(tag);
    }

    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(Level level, BlockPos pos, List<ItemStack> drops, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.addHiddenDrops(ae2oc_items(), drops);
        if (ae2oc_adapter != null) ae2oc_adapter.addDrops(drops);
    }
    @Unique private appeng.api.inventories.InternalInventory ae2oc_items() {
        var self = (TileCircuitCutter) (Object) this;
        return new appeng.util.inv.CombinedInternalInventory(self.getInput(), self.getOutput());
    }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_attachItems(CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.attach(ae2oc_items(), (TileCircuitCutter) (Object) this);
    }
}