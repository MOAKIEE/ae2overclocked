package moakiee.ae2oc.compat.ae2;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.blockentity.misc.InscriberBlockEntity;
import moakiee.Ae2OcConfig;

/** Bounded auto-export that does not reinsert rejected items into an over-capacity slot. */
public final class InscriberExport {
    private InscriberExport() {}

    public static boolean push(InscriberBlockEntity host) {
        if (host.getConfigManager().getSetting(Settings.AUTO_EXPORT) != YesNo.YES) return false;
        return SidedExport.push(host.getLevel(), host.getBlockPos(),
                ManagedItemStorages.slots(host.getInternalInventory()).get(3),
                host.getConfigManager().getSetting(Settings.INSCRIBER_SEPARATE_SIDES) == YesNo.YES,
                host.getTop(), Ae2OcConfig.getMaxTransferAmountPerMachineTick(), host::saveChanges);
    }
}
