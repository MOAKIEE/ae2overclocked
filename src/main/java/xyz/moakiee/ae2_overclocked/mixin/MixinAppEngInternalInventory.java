package xyz.moakiee.ae2_overclocked.mixin;

import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;

@Mixin(value = AppEngInternalInventory.class, remap = false)
public abstract class MixinAppEngInternalInventory {

    private static final String AE2OC_COUNT_KEY = "ae2ocCount";

    @Shadow
    @Final
    private NonNullList<ItemStack> stacks;

    @Inject(method = "getSlotLimit", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getSlotLimit(int slot, CallbackInfoReturnable<Integer> cir) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;
        if (CapacityCardRuntime.getInstalledCapacityCards(inventory.getHost()) > 0) {
            cir.setReturnValue(Ae2OcConfig.getCapacityCardSlotLimit());
        }
    }

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void ae2oc_extractItem(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        if (slot < 0 || slot >= inventory.size()) {
            return;
        }

        ItemStack stack = this.stacks.get(slot);
        if (stack.isEmpty() || stack.getCount() <= stack.getMaxStackSize()) {
            return;
        }

        int toExtract = Math.min(stack.getCount(), amount);
        if (toExtract <= 0) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (stack.getCount() <= toExtract) {
            if (!simulate) {
                inventory.setItemDirect(slot, ItemStack.EMPTY);
            }
            cir.setReturnValue(simulate ? stack.copy() : stack);
            return;
        }

        ItemStack extracted = stack.copy();
        if (!simulate) {
            stack.shrink(toExtract);
            inventory.setItemDirect(slot, stack);
        }
        extracted.setCount(toExtract);
        cir.setReturnValue(extracted);
    }

    @Inject(method = "writeToNBT", at = @At("HEAD"), cancellable = true)
    private void ae2oc_writeToNBT(CompoundTag data, String name, HolderLookup.Provider registries, CallbackInfo ci) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;
        if (inventory.isEmpty()) {
            data.remove(name);
            ci.cancel();
            return;
        }

        boolean hasOversizedStack = false;
        for (int i = 0; i < this.stacks.size(); i++) {
            if (this.stacks.get(i).getCount() > 64) {
                hasOversizedStack = true;
                break;
            }
        }

        if (!hasOversizedStack) {
            return;
        }

        ListTag items = new ListTag();
        for (int slot = 0; slot < this.stacks.size(); slot++) {
            ItemStack stack = this.stacks.get(slot);
            if (stack.isEmpty()) {
                continue;
            }

            CompoundTag itemTag = new CompoundTag();
            itemTag.putInt("Slot", slot);

            int realCount = stack.getCount();
            if (realCount > 64) {
                // Write a safe payload and keep the real count in a custom key.
                ItemStack oneCopy = stack.copy();
                oneCopy.setCount(1);
                itemTag = (CompoundTag) oneCopy.save(registries, itemTag);
                itemTag.putInt(AE2OC_COUNT_KEY, realCount);
            } else {
                itemTag = (CompoundTag) stack.save(registries, itemTag);
            }

            items.add(itemTag);
        }

        data.put(name, items);
        ci.cancel();
    }

    @Inject(method = "readFromNBT", at = @At("HEAD"), cancellable = true)
    private void ae2oc_readFromNBT(CompoundTag data, String name, HolderLookup.Provider registries, CallbackInfo ci) {
        if (!data.contains(name, Tag.TAG_LIST)) {
            return;
        }

        ListTag tagList = data.getList(name, Tag.TAG_COMPOUND);
        boolean hasOversizedStack = false;
        for (Tag rawTag : tagList) {
            CompoundTag itemTag = (CompoundTag) rawTag;
            if (itemTag.contains(AE2OC_COUNT_KEY, Tag.TAG_INT)) {
                hasOversizedStack = true;
                break;
            }
        }

        if (!hasOversizedStack) {
            return;
        }

        for (int i = 0; i < this.stacks.size(); i++) {
            this.stacks.set(i, ItemStack.EMPTY);
        }

        for (Tag rawTag : tagList) {
            CompoundTag itemTag = (CompoundTag) rawTag;
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= this.stacks.size()) {
                continue;
            }

            ItemStack stack;
            if (itemTag.contains(AE2OC_COUNT_KEY, Tag.TAG_INT)) {
                CompoundTag safeTag = itemTag.copy();
                safeTag.remove(AE2OC_COUNT_KEY);
                safeTag.putInt("count", 1);
                stack = ItemStack.parseOptional(registries, safeTag);
                int restoredCount = Math.max(itemTag.getInt(AE2OC_COUNT_KEY), 1);
                stack.setCount(restoredCount);
            } else {
                stack = ItemStack.parseOptional(registries, itemTag);
            }

            this.stacks.set(slot, stack);
        }

        ci.cancel();
    }
}
