package moakiee.ae2oc.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;
import appeng.api.stacks.AEItemKey;
import moakiee.ae2oc.core.quantity.LogicalSlot;
import net.minecraft.world.item.ItemStack;

/** Owns quantities; Minecraft sees only bounded stack projections. */
public final class LongItemStorage {
    private final List<LogicalSlot<AEItemKey>> slots;
    private final IntToLongFunction capacity;
    public LongItemStorage(int size, IntToLongFunction capacity) {
        this.capacity = capacity;
        slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) slots.add(new LogicalSlot<>(0));
    }
    public LogicalSlot<AEItemKey> slot(int index) {
        var slot = slots.get(index);
        slot.setCapacity(capacity.applyAsLong(index));
        return slot;
    }
    public int size() { return slots.size(); }
    public ItemStack projection(int index) {
        var slot = slot(index);
        return slot.key() == null ? ItemStack.EMPTY : slot.key().toStack((int) Math.min(slot.amount(), slot.key().getMaxStackSize()));
    }
    public void replaceProjection(int index, ItemStack replacement) {
        var slot = slot(index);
        var current = projection(index);
        long hidden = slot.amount() - current.getCount();
        if (hidden > 0 && !replacement.isEmpty() && !ItemStack.isSameItemSameTags(current, replacement))
            throw new IllegalStateException("Cannot replace a logical slot while it owns hidden resources");
        var key = replacement.isEmpty() ? slot.key() : AEItemKey.of(replacement);
        slot.restore(key, hidden + replacement.getCount());
    }
}
