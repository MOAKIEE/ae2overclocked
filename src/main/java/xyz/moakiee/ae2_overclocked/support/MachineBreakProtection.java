package xyz.moakiee.ae2_overclocked.support;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MachineBreakProtection {

    private static final Set<String> PROTECTED_MACHINE_CLASS_NAMES = Set.of(
            "appeng.blockentity.misc.InscriberBlockEntity",
            "com.glodblock.github.extendedae.common.tileentities.TileExInscriber",
            "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter",
            "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity"
    );

    private static final ConcurrentHashMap<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private MachineBreakProtection() {
    }

    public static boolean isProtectedMachine(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        Class<?> clazz = blockEntity.getClass();
        while (clazz != null) {
            if (PROTECTED_MACHINE_CLASS_NAMES.contains(clazz.getName())) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }

        return false;
    }

    public static int getInternalItemTotalCount(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return 0;
        }

        try {
            Method getInternalInventory = cachedGetMethod(blockEntity.getClass(), "getInternalInventory");
            if (getInternalInventory == null) return 0;
            Object internalInventory = getInternalInventory.invoke(blockEntity);
            return countInventoryStacks(internalInventory);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int countInventoryStacks(Object inventory) {
        if (inventory == null) {
            return 0;
        }

        try {
            Method sizeMethod = cachedGetMethod(inventory.getClass(), "size");
            Method getStackInSlotMethod = cachedGetMethod(inventory.getClass(), "getStackInSlot", int.class);
            if (sizeMethod == null || getStackInSlotMethod == null) return 0;

            int size = (int) sizeMethod.invoke(inventory);
            int total = 0;
            for (int slot = 0; slot < size; slot++) {
                Object rawStack = getStackInSlotMethod.invoke(inventory, slot);
                if (rawStack instanceof ItemStack stack && !stack.isEmpty()) {
                    total += stack.getCount();
                }
            }
            return total;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Method cachedGetMethod(Class<?> clazz, String name, Class<?>... params) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append('#').append(name);
        for (Class<?> p : params) sb.append(',').append(p.getName());
        String key = sb.toString();

        Optional<Method> opt = METHOD_CACHE.computeIfAbsent(key, k -> {
            try {
                Method m = clazz.getMethod(name, params);
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        return opt.orElse(null);
    }
}
