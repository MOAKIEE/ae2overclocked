package moakiee.ae2oc.compat.extendedae;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter;
import moakiee.Ae2OcConfig;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.SidedExport;

/**
 * Overflow-safe auto-export for the ExtendedAE circuit cutter.
 *
 * <p>Upstream {@code pushOutResult} hands a whole stack from the output slot to a neighbour and reinserts
 * what was rejected. Once a managed slot holds more than a visible stack that reinsertion fails and the
 * rejected remainder is destroyed. The custom processing path also cancels the upstream tick entirely, so
 * this helper both preserves the excess and restores auto-export while a batch or parallel card is active.
 *
 * <p>Upstream ejects around adjacent circuit cutters, which is kept here so a machine never feeds another
 * cutter's input.
 */
public final class CutterExport {
    private CutterExport() {}

    public static boolean push(TileCircuitCutter host) {
        if (host.getConfigManager().getSetting(Settings.AUTO_EXPORT) != YesNo.YES) return false;
        return SidedExport.push(host.getLevel(), host.getBlockPos(),
                LocalResourceSlot.item(host.getOutput(), 0), false, null,
                Ae2OcConfig.getMaxTransferAmountPerMachineTick(), host::saveChanges,
                neighbour -> neighbour instanceof TileCircuitCutter);
    }
}
