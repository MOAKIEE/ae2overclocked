package xyz.moakiee.ae2_overclocked.support;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.Set;

public final class MachineBreakProtection {

    private static final Set<String> PROTECTED_MACHINE_CLASS_NAMES = Set.of(
            "appeng.blockentity.misc.InscriberBlockEntity",
            "com.glodblock.github.extendedae.common.tileentities.TileExInscriber",
            "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter",
            "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity"
    );

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
            Method getInternalInventory = blockEntity.getClass().getMethod("getInternalInventory");
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
            Method sizeMethod = inventory.getClass().getMethod("size");
            Method getStackInSlotMethod = inventory.getClass().getMethod("getStackInSlot", int.class);

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
}
