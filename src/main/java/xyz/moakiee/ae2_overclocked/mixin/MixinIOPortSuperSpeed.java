/*
 * This file includes code adapted from MakeAE2Better by QiuYe.
 * Licensed under the MIT License.
 * Original Source: https://github.com/qiuye2024github/MaekAE2Better
 * Full license text: src/main/resources/LICENSE_MakeAE2Better.txt
 */
package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.StorageCells;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;
import xyz.moakiee.ae2_overclocked.ModItems;
import xyz.moakiee.ae2_overclocked.support.SuperSpeedNumberUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Method;

@Mixin(IOPortBlockEntity.class)
public abstract class MixinIOPortSuperSpeed implements IUpgradeableObject {

    @Mutable
    @Final
    @Shadow(remap = false)
    private AppEngInternalInventory inputCells;

    @Final
    @Shadow(remap = false)
    private static int NUMBER_OF_CELL_SLOTS;

    /**
     * @author ae2overclocked
     * @reason Add super speed card support to IO Port
     */
    @Overwrite(remap = false)
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        IOPortBlockEntity self = (IOPortBlockEntity) (Object) this;
        if (!self.getMainNode().isActive()) {
            return TickRateModulation.IDLE;
        }

        TickRateModulation ret = TickRateModulation.SLEEP;

        int speedUpgrades = this.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD);
        int superSpeedUpgrades = this.getUpgrades().getInstalledUpgrades(ModItems.SUPER_SPEED_CARD.get());
        long itemsToMove = ae2oc_getItemsToMove(speedUpgrades, superSpeedUpgrades);

        var grid = self.getMainNode().getGrid();
        if (grid == null) {
            return TickRateModulation.IDLE;
        }

        for (int x = 0; x < NUMBER_OF_CELL_SLOTS; x++) {
            var cell = this.inputCells.getStackInSlot(x);
            var cellInv = StorageCells.getCellInventory(cell, null);
            if (cellInv == null) {
                ae2oc_moveSlot(this, x);
                continue;
            }

            if (itemsToMove > 0L) {
                itemsToMove = ae2oc_transferContents(this, grid, cellInv, itemsToMove);
                ret = itemsToMove > 0L ? TickRateModulation.IDLE : TickRateModulation.URGENT;
            }

            if (itemsToMove > 0L && ae2oc_matchesFullnessMode(self, cellInv) && ae2oc_moveSlot(this, x)) {
                ret = TickRateModulation.URGENT;
            }
        }
        return ret;
    }

    @Unique
    private static long ae2oc_getItemsToMove(int speedUpgrades, int superSpeedUpgrades) {
        long baseItemsToMove = 256L;
        switch (speedUpgrades) {
            case 1 -> baseItemsToMove *= 2L;
            case 2 -> baseItemsToMove *= 4L;
            case 3 -> baseItemsToMove *= 8L;
            default -> {
            }
        }
        if (superSpeedUpgrades <= 0) {
            return baseItemsToMove;
        }
        return SuperSpeedNumberUtil.boostLongSaturating(baseItemsToMove);
    }

    @Unique
    private static boolean ae2oc_moveSlot(Object self, int slot) {
        try {
            Method method = ae2oc_findMethod(self.getClass(), "moveSlot", 1);
            if (method == null) {
                return false;
            }
            method.setAccessible(true);
            Object value = method.invoke(self, slot);
            return value instanceof Boolean ok && ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Unique
    private static long ae2oc_transferContents(Object self, Object grid, Object cellInv, long itemsToMove) {
        try {
            Method method = ae2oc_findMethod(self.getClass(), "transferContents", 3);
            if (method == null) {
                return itemsToMove;
            }
            method.setAccessible(true);
            Object value = method.invoke(self, grid, cellInv, itemsToMove);
            if (value instanceof Long moved) {
                return moved;
            }
            if (value instanceof Integer movedInt) {
                return movedInt.longValue();
            }
        } catch (Throwable ignored) {
        }
        return itemsToMove;
    }

    @Unique
    private static boolean ae2oc_matchesFullnessMode(Object self, Object cellInv) {
        try {
            Method method = ae2oc_findMethod(self.getClass(), "matchesFullnessMode", 1);
            if (method == null) {
                return true;
            }
            method.setAccessible(true);
            Object value = method.invoke(self, cellInv);
            return value instanceof Boolean ok && ok;
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Unique
    private static Method ae2oc_findMethod(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
