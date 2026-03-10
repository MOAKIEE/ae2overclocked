package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import com.google.common.base.Preconditions;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = AppEngInternalInventory.class, remap = false)
public abstract class MixinAppEngInternalInventory {

    private static final String AE2OC_COUNT_KEY = "ae2ocCount";

    // Cache: for each class, the Optional<Field> for "this$0"
    @Unique
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> ae2oc_outerFieldCache = new ConcurrentHashMap<>();

    @Shadow
    @Final
    private NonNullList<ItemStack> stacks;

    @Inject(method = "getSlotLimit", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getSlotLimit(int slot, CallbackInfoReturnable<Integer> cir) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;
        if (CapacityCardRuntime.getInstalledCapacityCards(ae2oc_resolveHost(inventory)) > 0) {
            cir.setReturnValue(Ae2OcConfig.getCapacityCardSlotLimit());
        }
    }

    /**
     * Override insertItem to bypass vanilla's getMaxStackSize() cap (default 64) when a
     * Capacity Card is installed. The original InternalInventory.insertItem() default
     * implementation computes free space as:
     *   Math.min(getSlotLimit(slot), stack.getMaxStackSize())
     * which always caps at 64 regardless of our boosted getSlotLimit(). When a capacity
     * card is present, we use getSlotLimit(slot) directly as the per-slot capacity.
     *
     * This method directly overrides the interface default because Mixin @Inject cannot
     * target interface default methods that are not overridden by the target class.
     */
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        // When no capacity card is installed, delegate to the original interface default logic
        if (CapacityCardRuntime.getInstalledCapacityCards(ae2oc_resolveHost(inventory)) <= 0) {
            return ae2oc_originalInsertItem(inventory, slot, stack, simulate);
        }

        // Bounds check
        Preconditions.checkArgument(slot >= 0 && slot < inventory.size(), "slot out of range");

        if (stack.isEmpty() || !inventory.isItemValid(slot, stack)) {
            return stack;
        }

        ItemStack inSlot = this.stacks.get(slot);

        // Use getSlotLimit (boosted by capacity card) instead of Math.min(getSlotLimit, maxStackSize)
        int maxSpace = inventory.getSlotLimit(slot);
        int freeSpace = maxSpace - inSlot.getCount();
        if (freeSpace <= 0) {
            return stack;
        }

        // Ensure item compatibility for non-empty slots
        if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, stack)) {
            return stack;
        }

        int insertAmount = Math.min(stack.getCount(), freeSpace);
        if (!simulate) {
            ItemStack newItem = inSlot.isEmpty() ? stack.copy() : inSlot.copy();
            newItem.setCount(inSlot.getCount() + insertAmount);
            inventory.setItemDirect(slot, newItem);
        }

        if (freeSpace >= stack.getCount()) {
            return ItemStack.EMPTY;
        } else {
            ItemStack remainder = stack.copy();
            remainder.shrink(insertAmount);
            return remainder;
        }
    }

    /**
     * Resolve the host (typically a BlockEntity) for the given inventory.
     * <p>
     * For AE2's own machines, getHost() returns the InternalInventoryHost directly.
     * For AE2CS machines, the inventory is created as an anonymous inner class inside the
     * BlockEntity constructor (e.g. {@code new AppEngInternalInventory(3) { ... }}), so
     * getHost() returns null. In that case, we fall back to reading the synthetic
     * {@code this$0} field that Java generates for inner classes, which points to the
     * enclosing BlockEntity.
     */
    @Unique
    private static Object ae2oc_resolveHost(AppEngInternalInventory inventory) {
        Object host = inventory.getHost();
        if (host != null) return host;

        // Fallback: anonymous inner class → this$0 → enclosing BlockEntity (with cache)
        Class<?> clazz = inventory.getClass();
        Optional<Field> opt = ae2oc_outerFieldCache.computeIfAbsent(clazz, c -> {
            try {
                Field f = c.getDeclaredField("this$0");
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        });

        if (opt.isPresent()) {
            try {
                return opt.get().get(inventory);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Reproduce the original InternalInventory.insertItem() default logic for
     * the case when no capacity card is installed.
     */
    @Unique
    private ItemStack ae2oc_originalInsertItem(InternalInventory inventory, int slot, ItemStack stack, boolean simulate) {
        Preconditions.checkArgument(slot >= 0 && slot < inventory.size(), "slot out of range");
        if (stack.isEmpty() || !inventory.isItemValid(slot, stack)) {
            return stack;
        }

        ItemStack inSlot = inventory.getStackInSlot(slot);

        int maxSpace = Math.min(inventory.getSlotLimit(slot), stack.getMaxStackSize());
        int freeSpace = maxSpace - inSlot.getCount();
        if (freeSpace <= 0) {
            return stack;
        }

        if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, stack)) {
            return stack;
        }

        int insertAmount = Math.min(stack.getCount(), freeSpace);
        if (!simulate) {
            ItemStack newItem = inSlot.isEmpty() ? stack.copy() : inSlot.copy();
            newItem.setCount(inSlot.getCount() + insertAmount);
            inventory.setItemDirect(slot, newItem);
        }

        if (freeSpace >= stack.getCount()) {
            return ItemStack.EMPTY;
        } else {
            ItemStack remainder = stack.copy();
            remainder.shrink(insertAmount);
            return remainder;
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
