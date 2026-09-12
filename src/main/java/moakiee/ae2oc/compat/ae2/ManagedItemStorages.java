package moakiee.ae2oc.compat.ae2;

import appeng.api.inventories.InternalInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.util.inv.AppEngInternalInventory;
import moakiee.Ae2OcConfig;
import moakiee.ModItems;
import moakiee.mixin.AccessorCombinedInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.List;

public final class ManagedItemStorages {
    private ManagedItemStorages() {}
    public static boolean isManaged(InternalInventory inventory) {
        if (inventory instanceof ManagedItemInventory managed) return managed.ae2oc$storage() != null;
        if (inventory instanceof AccessorCombinedInventory combined) {
            for (var child : combined.ae2oc$children()) if (isManaged(child)) return true;
        }
        return false;
    }
    public static void addHiddenDrops(InternalInventory inventory, java.util.List<net.minecraft.world.item.ItemStack> drops) {
        for (var port : slots(inventory)) {
            var value = port.read();
            if (value == null) continue;
            var item = (appeng.api.stacks.AEItemKey) value.key();
            long visible = Math.min(value.amount(), item.getMaxStackSize());
            if (value.amount() > visible) drops.add(moakiee.item.StoredResourcesItem.pack(item, value.amount() - visible));
        }
    }
    public static net.minecraft.world.item.ItemStack recipeStack(InternalInventory inventory, int slot) {
        var value = LocalResourceSlot.item(inventory, slot).read();
        return value == null ? net.minecraft.world.item.ItemStack.EMPTY
                : ((appeng.api.stacks.AEItemKey) value.key()).toStack((int) Math.min(Integer.MAX_VALUE, value.amount()));
    }
    public static long projectedConsumption(long logicalAmount, int projectedBefore, int projectedAfter) {
        if (logicalAmount < 0 || projectedBefore < 0 || projectedAfter < 0
                || projectedAfter > projectedBefore || projectedBefore > logicalAmount) {
            throw new IllegalArgumentException("Invalid recipe projection");
        }
        return (long) projectedBefore - projectedAfter;
    }
    public static List<LocalResourceSlot> slots(InternalInventory inventory) {
        List<LocalResourceSlot> result = new ArrayList<>();
        if (inventory instanceof AccessorCombinedInventory combined) {
            for (var child : combined.ae2oc$children()) result.addAll(slots(child));
        } else for (int i = 0; i < inventory.size(); i++) result.add(LocalResourceSlot.item(inventory, i));
        return result;
    }
    public static void attach(InternalInventory inventory, IUpgradeableObject host) {
        if (inventory instanceof AccessorCombinedInventory combined) {
            for (var child : combined.ae2oc$children()) attach(child, host);
        } else if (inventory instanceof AppEngInternalInventory app && inventory instanceof ManagedItemInventory managed
                && managed.ae2oc$storage() == null) {
            int[] base = new int[app.size()];
            var initial = new net.minecraft.world.item.ItemStack[app.size()];
            for (int i = 0; i < base.length; i++) { base[i] = app.getSlotLimit(i); initial[i] = app.getStackInSlot(i).copy(); }
            var storage = new LongItemStorage(app.size(), slot -> UpgradeProfileCache.of(host).capacity()
                    ? UpgradeProfileCache.of(host).capacityLimit() : base[slot]);
            for (int i = 0; i < base.length; i++) if (!initial[i].isEmpty())
                storage.slot(i).restore(appeng.api.stacks.AEItemKey.of(initial[i]), initial[i].getCount());
            managed.ae2oc$attach(storage);
            MachineItemContents.register(host, storage);
        }
    }
    public static void save(InternalInventory inventory, CompoundTag tag, String name) {
        var list = new ListTag();
        for (var slot : slots(inventory)) {
            var value = slot.read();
            var entry = new CompoundTag();
            if (value != null) { entry.put("key", value.key().toTagGeneric()); entry.putLong("amount", value.amount()); }
            list.add(entry);
        }
        tag.put(name, list);
    }
    public static void load(InternalInventory inventory, CompoundTag tag, String name) {
        if (!tag.contains(name, Tag.TAG_LIST)) {
            if (tag.contains("inv", Tag.TAG_COMPOUND)) {
                var legacy = tag.getCompound("inv");
                var ports = slots(inventory);
                var restored = new java.util.HashMap<Integer, moakiee.ae2oc.api.ResourceAmount<appeng.api.stacks.AEKey>>();
                for (int i = 0; i < ports.size(); i++) {
                    var item = legacy.getCompound("item" + i);
                    if (!item.isEmpty()) restored.put(i, readLegacyItem(item));
                }
                restored.forEach((slot, value) -> ports.get(slot).write(value));
            } else if (tag.contains("inv", Tag.TAG_LIST)) {
                var list = tag.getList("inv", Tag.TAG_COMPOUND);
                var ports = slots(inventory);
                var restored = new java.util.HashMap<Integer, moakiee.ae2oc.api.ResourceAmount<appeng.api.stacks.AEKey>>();
                for (int i = 0; i < list.size(); i++) {
                    var entry = list.getCompound(i);
                    int slot = entry.contains("Slot") ? entry.getInt("Slot") : i;
                    if (slot >= 0 && slot < ports.size()) {
                        restored.put(slot, readLegacyItem(entry));
                    }
                }
                restored.forEach((slot, value) -> ports.get(slot).write(value));
            }
            return;
        }
        var slots = slots(inventory);
        var list = tag.getList(name, Tag.TAG_COMPOUND);
        if (list.size() != slots.size()) throw new IllegalArgumentException("Logical inventory shape changed");
        var restored = new ArrayList<moakiee.ae2oc.api.ResourceAmount<appeng.api.stacks.AEKey>>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            var entry = list.getCompound(i);
            if (!entry.contains("key")) restored.add(null);
            else {
                var key = appeng.api.stacks.AEKey.fromTagGeneric(entry.getCompound("key"));
                if (!(key instanceof appeng.api.stacks.AEItemKey)) throw new IllegalArgumentException("Unknown saved item");
                restored.add(new moakiee.ae2oc.api.ResourceAmount<>(key, entry.getLong("amount")));
            }
        }
        for (int i = 0; i < slots.size(); i++) slots.get(i).write(restored.get(i));
    }

    public static moakiee.ae2oc.api.ResourceAmount<appeng.api.stacks.AEKey> readLegacyItem(CompoundTag item) {
        if (item.contains("ae2ocDataVersion") && item.getInt("ae2ocDataVersion") != 1)
            throw new IllegalArgumentException("Unsupported item storage version");
        long amount = item.contains("ae2ocAmount") ? item.getLong("ae2ocAmount")
                : moakiee.ae2oc.migration.LegacyQuantity.resolve(item.getByte("Count"),
                item.contains("ae2ocCount") ? java.util.OptionalLong.of(item.getInt("ae2ocCount")) : java.util.OptionalLong.empty(),
                item.getCompound("tag").contains("ae2ocNetCount")
                        ? java.util.OptionalLong.of(item.getCompound("tag").getInt("ae2ocNetCount")) : java.util.OptionalLong.empty());
        if (amount == 0) return null;
        var copy = item.copy();
        copy.putByte("Count", (byte) 1);
        copy.getCompound("tag").remove("ae2ocNetCount");
        var stack = net.minecraft.world.item.ItemStack.of(copy);
        if (stack.isEmpty()) throw new IllegalArgumentException("Unknown legacy item; cannot migrate without losing resources");
        return new moakiee.ae2oc.api.ResourceAmount<>(appeng.api.stacks.AEItemKey.of(stack), amount);
    }
}
