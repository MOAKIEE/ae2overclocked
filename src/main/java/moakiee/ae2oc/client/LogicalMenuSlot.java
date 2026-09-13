package moakiee.ae2oc.client;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.AEBaseMenu;
import appeng.menu.slot.AppEngSlot;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Follows AE2-Lightning-Tech's separate presentation/backing-slot interaction design. */
public final class LogicalMenuSlot extends AppEngSlot {
    private final AppEngSlot original;
    private final int backingIndex;
    private final LocalResourceSlot logical;
    private ItemStack synced;
    public LogicalMenuSlot(AppEngSlot original, int backingIndex) {
        super(original.getInventory(), backingIndex);
        this.original = original;
        this.backingIndex = backingIndex;
        logical = ManagedItemStorages.slots(original.getInventory()).get(backingIndex);
        super.setSlotEnabled(original.isSlotEnabled());
        setNotDraggable();
    }
    @Override public void setMenu(AEBaseMenu menu) { super.setMenu(menu); original.setMenu(menu); }
    @Override public void setSlotEnabled(boolean enabled) {
        super.setSlotEnabled(enabled);
        original.setSlotEnabled(enabled);
    }
    @Override public boolean mayPlace(ItemStack stack) {
        return isSlotEnabled() && !GenericStack.isWrapped(stack) && original.mayPlace(stack);
    }
    @Override public boolean mayPickup(Player player) { return isSlotEnabled() && original.mayPickup(player); }
    @Override public void onTake(Player player, ItemStack stack) { original.onTake(player, stack); }
    public ItemStack backingItem() { return getInventory().getStackInSlot(backingIndex); }
    public long amount() { var value = logical.read(); return value == null ? 0 : value.amount(); }
    @Override public ItemStack getItem() {
        if (!isSlotEnabled()) return ItemStack.EMPTY;
        if (isRemote() && synced != null) return synced;
        var value = logical.read();
        if (value == null) return ItemStack.EMPTY;
        var key = (AEItemKey) value.key();
        return value.amount() > key.getMaxStackSize() ? GenericStack.wrapInItemStack(key, value.amount()) : key.toStack((int) value.amount());
    }
    @Override public void initialize(ItemStack stack) { if (isRemote()) synced = stack.copy(); else super.initialize(stack); }
    @Override public void set(ItemStack stack) {
        if (isRemote()) synced = stack.copy();
        else if (!GenericStack.isWrapped(stack)) original.set(stack);
    }
    @Override public ItemStack remove(int amount) {
        if (!isSlotEnabled()) return ItemStack.EMPTY;
        var current = backingItem();
        return current.isEmpty() ? ItemStack.EMPTY : getInventory().extractItem(backingIndex, Math.min(Math.max(0, amount), current.getMaxStackSize()), false);
    }
    @Override public ItemStack safeInsert(ItemStack stack, int increment) {
        if (stack.isEmpty() || increment <= 0 || !mayPlace(stack)) return stack;
        int offered = Math.min(increment, stack.getCount());
        var remainder = getInventory().insertItem(backingIndex, stack.copyWithCount(offered), false);
        stack.shrink(offered - remainder.getCount());
        return stack;
    }
    /** Vanilla-style swap, but only when the complete backing amount fits in one legal cursor stack. */
    public ItemStack exchange(ItemStack cursor, Player player) {
        var current = backingItem();
        long currentAmount = amount();
        if (cursor.isEmpty() || current.isEmpty() || ItemStack.isSameItemSameTags(cursor, current)
                || !mayPlace(cursor) || !mayPickup(player) || currentAmount > current.getMaxStackSize()) return null;
        var extracted = remove((int) currentAmount);
        if (extracted.getCount() != currentAmount) {
            restoreExtracted(extracted);
            return null;
        }
        var offered = cursor.copy();
        safeInsert(offered, offered.getCount());
        if (offered.isEmpty()) return extracted;
        int inserted = cursor.getCount() - offered.getCount();
        if (inserted > 0) remove(inserted);
        restoreExtracted(extracted);
        return null;
    }
    public void restoreExtracted(ItemStack remainder) {
        if (remainder.isEmpty()) return;
        var before = logical.read();
        var key = AEItemKey.of(remainder);
        if (before != null && !before.key().equals(key)) throw new IllegalStateException("Rollback slot identity changed");
        logical.write(new moakiee.ae2oc.api.ResourceAmount<>(key,
                moakiee.ae2oc.core.quantity.SaturatedMath.addNonNegative(before == null ? 0 : before.amount(), remainder.getCount())));
    }
}
