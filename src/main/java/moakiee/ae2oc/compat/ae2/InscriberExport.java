package moakiee.ae2oc.compat.ae2;

import java.util.EnumSet;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import moakiee.Ae2OcConfig;
import moakiee.ae2oc.api.ResourceAmount;
import net.minecraft.core.Direction;

/** Bounded auto-export that does not reinsert rejected items into an over-capacity slot. */
public final class InscriberExport {
    private InscriberExport() {}

    public static boolean push(InscriberBlockEntity host) {
        if (host.getLevel() == null || host.getConfigManager().getSetting(Settings.AUTO_EXPORT) != YesNo.YES) return false;
        var slot = ManagedItemStorages.slots(host.getInternalInventory()).get(3);
        var before = slot.read();
        if (before == null || before.amount() == 0) return false;
        var key = (AEItemKey) before.key();
        int offered = (int) Math.min(Math.min(before.amount(), key.getMaxStackSize()),
                Ae2OcConfig.getMaxTransferAmountPerMachineTick());
        var sides = EnumSet.allOf(Direction.class);
        if (host.getConfigManager().getSetting(Settings.INSCRIBER_SEPARATE_SIDES) == YesNo.YES) {
            sides.remove(host.getTop());
            sides.remove(host.getTop().getOpposite());
        }
        for (var side : sides) {
            var target = InternalInventory.wrapExternal(host.getLevel(), host.getBlockPos().relative(side), side.getOpposite());
            if (target == null) continue;
            // As with processing ports, a throwing external transfer must have moved nothing.
            var remainder = target.addItems(key.toStack(offered));
            if (remainder.getCount() > offered || (!remainder.isEmpty() && !key.matches(remainder)))
                throw new IllegalStateException("Auto-export port returned an invalid remainder");
            int accepted = offered - remainder.getCount();
            if (accepted > 0) {
                slot.write(new ResourceAmount<>(key, before.amount() - accepted));
                host.saveChanges();
                return true;
            }
        }
        return false;
    }
}
