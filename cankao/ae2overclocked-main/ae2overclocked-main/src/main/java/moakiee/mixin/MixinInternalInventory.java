package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import com.google.common.base.Preconditions;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = InternalInventory.class, remap = false)
public interface MixinInternalInventory {

    @Overwrite
    default ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        InternalInventory inventory = (InternalInventory) this;

        Preconditions.checkArgument(slot >= 0 && slot < inventory.size(), "slot out of range");

        if (stack.isEmpty() || !inventory.isItemValid(slot, stack)) {
            return stack;
        }

        var inSlot = inventory.getStackInSlot(slot);

        int maxSpace = inventory.getSlotLimit(slot);
        int freeSpace = maxSpace - inSlot.getCount();
        if (freeSpace <= 0) {
            return stack;
        }

        if (!inSlot.isEmpty() && !ItemStack.isSameItemSameTags(inSlot, stack)) {
            return stack;
        }

        var insertAmount = Math.min(stack.getCount(), freeSpace);
        if (!simulate) {
            var newItem = inSlot.isEmpty() ? stack.copy() : inSlot.copy();
            newItem.setCount(inSlot.getCount() + insertAmount);
            inventory.setItemDirect(slot, newItem);
        }

        if (freeSpace >= stack.getCount()) {
            return ItemStack.EMPTY;
        } else {
            var remainder = stack.copy();
            remainder.shrink(insertAmount);
            return remainder;
        }
    }
}
