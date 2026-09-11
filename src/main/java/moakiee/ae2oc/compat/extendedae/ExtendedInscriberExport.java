package moakiee.ae2oc.compat.extendedae;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.inventories.InternalInventory;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import moakiee.Ae2OcConfig;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.SidedExport;
import moakiee.ae2oc.core.planning.MachineBudget;

/**
 * Overflow-safe lane export for the four-thread ExtendedAE inscriber.
 *
 * <p>Every lane owns its own output slot (index 3 of its combined inventory) but the machine has a single
 * auto-export setting and orientation. The per-lane allowance is a quarter of the machine-wide transfer
 * budget so all four lanes together stay within the configured per-tick limit.
 */
public final class ExtendedInscriberExport {
    public static final int LANES = 4;

    private ExtendedInscriberExport() {}

    public static boolean push(TileExInscriber host, InternalInventory lane) {
        if (host.getConfigManager().getSetting(Settings.AUTO_EXPORT) != YesNo.YES) return false;
        long budget = MachineBudget.share(Ae2OcConfig.getMaxTransferAmountPerMachineTick(), LANES);
        return SidedExport.push(host.getLevel(), host.getBlockPos(),
                ManagedItemStorages.slots(lane).get(3),
                host.isSeparateSides(), host.getTop(), budget, host::saveChanges);
    }
}
