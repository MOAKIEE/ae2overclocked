package xyz.moakiee.ae2_overclocked.support;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class OverstackingRegistry {

    private static final Set<Object> OVERSTACKING_INVENTORIES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private OverstackingRegistry() {
    }

    public static void register(Object inventory) {
        if (inventory != null) {
            OVERSTACKING_INVENTORIES.add(inventory);
        }
    }

    public static void unregister(Object inventory) {
        if (inventory != null) {
            OVERSTACKING_INVENTORIES.remove(inventory);
        }
    }

    public static boolean shouldAllowOverstacking(Object inventory) {
        return inventory != null && OVERSTACKING_INVENTORIES.contains(inventory);
    }
}
