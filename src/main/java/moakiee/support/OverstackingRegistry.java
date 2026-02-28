package moakiee.support;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 注册表：追踪哪些 ConfigInventory 实例应该允许超量堆叠。
 * 使用 WeakHashMap 避免内存泄漏，当 ConfigInventory 被 GC 时自动移除。
 */
public class OverstackingRegistry {
    
    private static final Set<Object> OVERSTACKING_INVENTORIES = 
        Collections.newSetFromMap(new WeakHashMap<>());
    
    /**
     * 注册一个 inventory 实例，允许其超量堆叠
     */
    public static void register(Object inventory) {
        if (inventory != null) {
            OVERSTACKING_INVENTORIES.add(inventory);
        }
    }
    
    /**
     * 取消注册一个 inventory 实例
     */
    public static void unregister(Object inventory) {
        if (inventory != null) {
            OVERSTACKING_INVENTORIES.remove(inventory);
        }
    }
    
    /**
     * 检查一个 inventory 实例是否应该允许超量堆叠
     */
    public static boolean shouldAllowOverstacking(Object inventory) {
        return inventory != null && OVERSTACKING_INVENTORIES.contains(inventory);
    }
    
}
