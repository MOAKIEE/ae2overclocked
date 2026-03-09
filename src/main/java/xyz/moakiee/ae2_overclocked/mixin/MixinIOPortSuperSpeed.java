/*
 * This file includes code adapted from MakeAE2Better by QiuYe.
 * Licensed under the MIT License.
 * Original Source: https://github.com/qiuye2024github/MaekAE2Better
 * Full license text: src/main/resources/LICENSE_MakeAE2Better.txt
 */
package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;
import xyz.moakiee.ae2_overclocked.ModItems;
import xyz.moakiee.ae2_overclocked.support.SuperSpeedNumberUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IOPortBlockEntity.class)
public abstract class MixinIOPortSuperSpeed implements IUpgradeableObject {

    @Final
    @Shadow(remap = false)
    private AppEngInternalInventory inputCells;

    @Final
    @Shadow(remap = false)
    private static int NUMBER_OF_CELL_SLOTS;

    @Shadow(remap = false)
    private boolean moveSlot(int x) { throw new AbstractMethodError(); }

    @Shadow(remap = false)
    private long transferContents(IGrid grid, StorageCell cellInv, long itemsToMove) { throw new AbstractMethodError(); }

    @Shadow(remap = false)
    public abstract boolean matchesFullnessMode(StorageCell inv);

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2oc_tickingRequest(IGridNode node, int ticksSinceLastCall, CallbackInfoReturnable<TickRateModulation> cir) {
        IOPortBlockEntity self = (IOPortBlockEntity) (Object) this;
        if (!self.getMainNode().isActive()) {
            cir.setReturnValue(TickRateModulation.IDLE);
            return;
        }

        int superSpeedUpgrades = this.getUpgrades().getInstalledUpgrades(ModItems.SUPER_SPEED_CARD.get());
        if (superSpeedUpgrades <= 0) {
            // No super speed card installed, let the original method handle it
            return;
        }

        // Super speed card is installed, override the entire logic
        TickRateModulation ret = TickRateModulation.SLEEP;

        int speedUpgrades = this.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD);
        long itemsToMove = ae2oc_getItemsToMove(speedUpgrades, superSpeedUpgrades);

        var grid = self.getMainNode().getGrid();
        if (grid == null) {
            cir.setReturnValue(TickRateModulation.IDLE);
            return;
        }

        for (int x = 0; x < NUMBER_OF_CELL_SLOTS; x++) {
            var cell = this.inputCells.getStackInSlot(x);
            // Keep the original StorageCells.getCellInventory call so that other mods
            // (e.g. neoecoae) can @WrapOperation on it
            var cellInv = StorageCells.getCellInventory(cell, null);
            if (cellInv == null) {
                moveSlot(x);
                continue;
            }

            if (itemsToMove > 0L) {
                itemsToMove = transferContents(grid, cellInv, itemsToMove);
                ret = itemsToMove > 0L ? TickRateModulation.IDLE : TickRateModulation.URGENT;
            }

            if (itemsToMove > 0L && matchesFullnessMode(cellInv) && moveSlot(x)) {
                ret = TickRateModulation.URGENT;
            }
        }
        cir.setReturnValue(ret);
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
        return SuperSpeedNumberUtil.boostLongSaturating(baseItemsToMove);
    }
}
