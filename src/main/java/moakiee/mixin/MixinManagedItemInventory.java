package moakiee.mixin;

import appeng.api.inventories.BaseInternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import appeng.api.stacks.AEItemKey;
import moakiee.ae2oc.compat.ae2.LongItemStorage;
import moakiee.ae2oc.compat.ae2.ManagedItemInventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only explicitly attached processing inventories use logical storage. */
@Mixin(value = AppEngInternalInventory.class, remap = false)
public abstract class MixinManagedItemInventory extends BaseInternalInventory implements ManagedItemInventory, moakiee.ae2oc.compat.ae2.UpgradeRevision {
    @Unique private long ae2oc_revision;
    @Override public long ae2oc$upgradeRevision() { return ae2oc_revision; }
    @Inject(method = "onContentsChanged", at = @At("HEAD"))
    private void ae2oc_upgradeChanged(int slot, CallbackInfo ci) {
        if (this instanceof appeng.api.upgrades.IUpgradeInventory) ae2oc_revision++;
    }
    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void ae2oc_upgradeLoaded(net.minecraft.nbt.CompoundTag tag, String name, CallbackInfo ci) {
        if (this instanceof appeng.api.upgrades.IUpgradeInventory) ae2oc_revision++;
    }
    @Unique private LongItemStorage ae2oc_storage;
    @Shadow private IAEItemFilter filter;
    @Shadow protected abstract void onContentsChanged(int slot);
    @Override public LongItemStorage ae2oc$storage() { return ae2oc_storage; }
    @Override public void ae2oc$attach(LongItemStorage storage) { ae2oc_storage = storage; }

    @Inject(method = "getStackInSlot", at = @At("HEAD"), cancellable = true)
    private void ae2oc_project(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (ae2oc_storage != null) cir.setReturnValue(ae2oc_storage.projection(slot));
    }
    @Inject(method = "setItemDirect", at = @At("HEAD"), cancellable = true)
    private void ae2oc_replace(int slot, ItemStack value, CallbackInfo ci) {
        if (ae2oc_storage == null) return;
        ae2oc_storage.replaceProjection(slot, value);
        onContentsChanged(slot);
        ci.cancel();
    }
    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void ae2oc_extract(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (ae2oc_storage == null) return;
        var logical = ae2oc_storage.slot(slot);
        if (amount <= 0 || logical.key() == null || filter != null && !filter.allowExtract(this, slot, amount)) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        var key = logical.key();
        int extracted = (int) logical.extract(Math.min(amount, key.getMaxStackSize()), simulate);
        if (!simulate && extracted > 0) onContentsChanged(slot);
        cir.setReturnValue(extracted == 0 ? ItemStack.EMPTY : key.toStack(extracted));
    }

    // AppEngInternalInventory inherits this method; the unchanged superclass handles all other inventories.
    @Override public ItemStack insertItem(int slot, ItemStack value, boolean simulate) {
        if (ae2oc_storage == null) return super.insertItem(slot, value, simulate);
        if (value.isEmpty() || !isItemValid(slot, value)) return value;
        long accepted = ae2oc_storage.slot(slot).insert(AEItemKey.of(value), value.getCount(), simulate);
        if (!simulate && accepted > 0) onContentsChanged(slot);
        return value.copyWithCount(value.getCount() - (int) accepted);
    }

    @Inject(method = "writeToNBT", at = @At("HEAD"), cancellable = true)
    private void ae2oc_save(net.minecraft.nbt.CompoundTag tag, String name, CallbackInfo ci) {
        if (ae2oc_storage == null) return;
        var items = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < ae2oc_storage.size(); i++) {
            var logical = ae2oc_storage.slot(i);
            if (logical.key() == null) continue;
            var entry = logical.key().toStack(1).save(new net.minecraft.nbt.CompoundTag());
            entry.putInt("Slot", i);
            entry.putInt("ae2ocDataVersion", 1);
            entry.putLong("ae2ocAmount", logical.amount());
            items.add(entry);
        }
        tag.put(name, items);
        ci.cancel();
    }
    @Inject(method = "readFromNBT", at = @At("HEAD"), cancellable = true)
    private void ae2oc_load(net.minecraft.nbt.CompoundTag tag, String name, CallbackInfo ci) {
        if (ae2oc_storage == null) return;
        var items = tag.getList(name, net.minecraft.nbt.Tag.TAG_COMPOUND);
        var restored = new java.util.HashMap<Integer, moakiee.ae2oc.api.ResourceAmount<appeng.api.stacks.AEKey>>();
        for (int i = 0; i < items.size(); i++) {
            var entry = items.getCompound(i);
            int slot = entry.getInt("Slot");
            if (slot < 0 || slot >= ae2oc_storage.size()) throw new IllegalArgumentException("Invalid saved slot index");
            if (restored.containsKey(slot)) throw new IllegalArgumentException("Duplicate saved slot index");
            var value = moakiee.ae2oc.compat.ae2.ManagedItemStorages.readLegacyItem(entry);
            restored.put(slot, value);
        }
        for (int i = 0; i < ae2oc_storage.size(); i++) {
            var value = restored.get(i);
            ae2oc_storage.slot(i).restore(value == null ? null : (AEItemKey) value.key(), value == null ? 0 : value.amount());
        }
        ci.cancel();
    }
}
