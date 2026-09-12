package moakiee.mixin;

import com.glodblock.github.extendedae.common.me.InscriberThread;
import moakiee.ae2oc.compat.extendedae.InscriberThreadState;
import moakiee.ae2oc.compat.extendedae.ExtendedInscriberVisualState;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileExInscriber", remap = false)
public abstract class MixinExInscriberPersistence implements ExtendedInscriberVisualState {
    @Shadow @Final private InscriberThread[] threads;
    @Unique private boolean ae2oc_visualPulse;
    @Unique private boolean ae2oc_visualSmash;
    @Unique private long ae2oc_visualClientStart;
    @Unique private ItemStack[] ae2oc_visualResults;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_initVisuals(CallbackInfo ci) {
        ae2oc_visualResults = new ItemStack[threads.length];
        java.util.Arrays.fill(ae2oc_visualResults, ItemStack.EMPTY);
    }

    @Override
    public boolean ae2oc$isVisualSmash() {
        return ae2oc_visualSmash;
    }

    @Override
    public ItemStack ae2oc$visualResult(InternalInventory inventory) {
        for (int i = 0; i < threads.length; i++) {
            if (threads[i].getInternalInventory() == inventory) return ae2oc_visualResults[i];
        }
        return ItemStack.EMPTY;
    }

    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_collectVisualCompletions(IGridNode node, int elapsed,
            CallbackInfoReturnable<TickRateModulation> cir) {
        boolean completed = false;
        for (int i = 0; i < threads.length; i++) {
            var output = ((InscriberThreadState) threads[i]).ae2oc$getAdapter().pollCompletedPrimaryOutput();
            if (output != null && output.key() instanceof AEItemKey item) {
                ae2oc_visualResults[i] = item.toStack(1);
                completed = true;
            }
        }
        if (completed) {
            ae2oc_visualPulse = true;
            ((com.glodblock.github.extendedae.common.tileentities.TileExInscriber) (Object) this).markForUpdate();
        }
    }

    @Inject(method = "writeToStream", at = @At("TAIL"))
    private void ae2oc_writeVisuals(FriendlyByteBuf data, CallbackInfo ci) {
        data.writeBoolean(ae2oc_visualPulse);
        for (var result : ae2oc_visualResults) data.writeItem(result);
        ae2oc_visualPulse = false;
        java.util.Arrays.fill(ae2oc_visualResults, ItemStack.EMPTY);
    }

    @Inject(method = "readFromStream", at = @At("TAIL"))
    private void ae2oc_readVisuals(FriendlyByteBuf data, CallbackInfoReturnable<Boolean> cir) {
        boolean pulse = data.readBoolean();
        for (int i = 0; i < ae2oc_visualResults.length; i++) ae2oc_visualResults[i] = data.readItem();
        if (pulse) {
            ae2oc_visualSmash = true;
            ae2oc_visualClientStart = System.currentTimeMillis();
        }
    }

    @Inject(method = "isSmash", at = @At("HEAD"), cancellable = true)
    private void ae2oc_isVisualSmash(CallbackInfoReturnable<Boolean> cir) {
        if (ae2oc_visualSmash) cir.setReturnValue(true);
    }

    @Inject(method = "getClientStart", at = @At("HEAD"), cancellable = true)
    private void ae2oc_visualClientStart(CallbackInfoReturnable<Long> cir) {
        if (ae2oc_visualSmash) cir.setReturnValue(ae2oc_visualClientStart);
    }

    @Inject(method = "setSmash", at = @At("HEAD"), cancellable = true)
    private void ae2oc_clearVisualSmash(boolean smash, CallbackInfo ci) {
        if (ae2oc_visualSmash && !smash) {
            ae2oc_visualSmash = false;
            ci.cancel();
        }
    }

    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("TAIL"), require = 1)
    private void ae2oc_save(CompoundTag tag, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.save(
                ((com.glodblock.github.extendedae.common.tileentities.TileExInscriber) (Object) this).getInternalInventory(), tag, "ae2ocLongSlots");
        for (int i = 0; i < threads.length; i++) {
            var child = new CompoundTag();
            ((InscriberThreadState) threads[i]).ae2oc$getAdapter().save(child);
            tag.put("ae2ocThread" + i, child);
        }
    }

    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(CompoundTag tag, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.load(
                ((com.glodblock.github.extendedae.common.tileentities.TileExInscriber) (Object) this).getInternalInventory(), tag, "ae2ocLongSlots");
        for (int i = 0; i < threads.length; i++) {
            ((InscriberThreadState) threads[i]).ae2oc$getAdapter().load(tag.getCompound("ae2ocThread" + i));
        }
        ae2oc_visualPulse = false;
        ae2oc_visualSmash = false;
        java.util.Arrays.fill(ae2oc_visualResults, ItemStack.EMPTY);
    }

    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(Level level, BlockPos pos, List<ItemStack> drops, CallbackInfo ci) {
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.addHiddenDrops(((com.glodblock.github.extendedae.common.tileentities.TileExInscriber) (Object) this).getInternalInventory(), drops);
        for (var thread : threads) ((InscriberThreadState) thread).ae2oc$getAdapter().addDrops(drops);
    }
}
