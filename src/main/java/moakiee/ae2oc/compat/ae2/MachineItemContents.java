package moakiee.ae2oc.compat.ae2;

import java.util.IdentityHashMap;
import java.util.function.LongSupplier;
import appeng.api.stacks.AEItemKey;
import appeng.helpers.externalstorage.GenericStackInv;
import moakiee.ae2oc.core.quantity.SaturatedMath;

/** Host-owned inventory views, queried only when deciding whether dismantling needs Shift. */
public final class MachineItemContents {
    public interface Owner { MachineItemContents ae2oc$itemContents(); }
    private final IdentityHashMap<Object, LongSupplier> sources = new IdentityHashMap<>();

    public void register(Object identity, LongSupplier amount) { sources.putIfAbsent(identity, amount); }
    public boolean isManaged() { return !sources.isEmpty(); }
    public long total() {
        long total = 0;
        for (var source : sources.values()) total = SaturatedMath.addNonNegative(total, source.getAsLong());
        return total;
    }
    public static void register(Object host, LongItemStorage storage) {
        if (host instanceof Owner owner) owner.ae2oc$itemContents().register(storage, () -> {
            long total = 0;
            for (int i = 0; i < storage.size(); i++) total = SaturatedMath.addNonNegative(total, storage.slot(i).amount());
            return total;
        });
    }
    public static void register(Object host, GenericStackInv inventory) {
        if (host instanceof Owner owner) owner.ae2oc$itemContents().register(inventory, () -> {
            long total = 0;
            for (int i = 0; i < inventory.size(); i++) {
                if (inventory.getKey(i) instanceof AEItemKey)
                    total = SaturatedMath.addNonNegative(total, inventory.getAmount(i));
            }
            return total;
        });
    }
}
