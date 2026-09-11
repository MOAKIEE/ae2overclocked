package moakiee.support;

import moakiee.ae2oc.compat.ae2.ManagedGenericInventory;

/** Compatibility facade for inventory-owned lifecycle state. */
public final class OverstackingRegistry {
    private OverstackingRegistry() {}
    public static void register(Object inventory) {
        if (inventory instanceof ManagedGenericInventory managed) managed.ae2oc$setManaged(true);
    }
    public static void unregister(Object inventory) {
        // Capacity removal freezes insertion, but ownership must persist for lossless extraction.
    }
    public static boolean shouldAllowOverstacking(Object inventory) {
        return inventory instanceof ManagedGenericInventory managed && managed.ae2oc$isManaged();
    }
}
