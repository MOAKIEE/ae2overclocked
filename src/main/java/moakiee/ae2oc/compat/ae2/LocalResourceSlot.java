package moakiee.ae2oc.compat.ae2;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import moakiee.ae2oc.api.ResourceAmount;
import net.minecraft.world.item.ItemStack;

public interface LocalResourceSlot {
    ResourceAmount<AEKey> read();
    void write(ResourceAmount<AEKey> value);

    static LocalResourceSlot item(InternalInventory inv, int slot) {
        return new LocalResourceSlot() {
            public ResourceAmount<AEKey> read() {
                if (inv instanceof ManagedItemInventory managed && managed.ae2oc$storage() != null) {
                    var logical = managed.ae2oc$storage().slot(slot);
                    return logical.key() == null ? null : new ResourceAmount<>(logical.key(), logical.amount());
                }
                var stack = inv.getStackInSlot(slot);
                return stack.isEmpty() ? null : new ResourceAmount<>(AEItemKey.of(stack), stack.getCount());
            }
            public void write(ResourceAmount<AEKey> value) {
                if (inv instanceof ManagedItemInventory managed && managed.ae2oc$storage() != null) {
                    managed.ae2oc$storage().slot(slot).restore(value == null ? null : (AEItemKey) value.key(), value == null ? 0 : value.amount());
                    inv.setItemDirect(slot, managed.ae2oc$storage().projection(slot));
                    return;
                }
                inv.setItemDirect(slot, value == null || value.amount() == 0 ? ItemStack.EMPTY
                        : ((AEItemKey) value.key()).toStack(Math.toIntExact(value.amount())));
            }
        };
    }

    static LocalResourceSlot generic(GenericStackInv inv, int slot) {
        return new LocalResourceSlot() {
            public ResourceAmount<AEKey> read() {
                var stack = inv.getStack(slot);
                return stack == null ? null : new ResourceAmount<>(stack.what(), stack.amount());
            }
            public void write(ResourceAmount<AEKey> value) {
                inv.setStack(slot, value == null || value.amount() == 0 ? null : new GenericStack(value.key(), value.amount()));
            }
        };
    }
}
