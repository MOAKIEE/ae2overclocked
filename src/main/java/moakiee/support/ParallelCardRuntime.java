package moakiee.support;

import appeng.api.upgrades.IUpgradeableObject;
import moakiee.Ae2OcConfig;
import moakiee.ModItems;
import moakiee.item.ParallelCard;

/** Typed upgrade lookup; adapters resolve the owning machine before calling. */
public final class ParallelCardRuntime {
    private ParallelCardRuntime() {}
    public static int getParallelMultiplier(Object host) {
        if (!(host instanceof IUpgradeableObject machine) || Ae2OcConfig.isMachineDisabled(host)) return 1;
        var inventory = machine.getUpgrades();
        if (inventory.getInstalledUpgrades(ModItems.PARALLEL_CARD_MAX.get()) > 0) return Ae2OcConfig.getParallelCardMaxMultiplier();
        int multiplier = 1;
        for (var stack : inventory) {
            if (stack.getItem() instanceof ParallelCard card) multiplier = Math.max(multiplier, card.getMultiplier());
        }
        return multiplier;
    }
    public static boolean hasParallelCard(Object host) { return getParallelMultiplier(host) > 1; }
}
