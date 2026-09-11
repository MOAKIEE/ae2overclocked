package moakiee.ae2oc.compat.ae2;

import appeng.api.upgrades.IUpgradeableObject;
import moakiee.Ae2OcConfig;
import moakiee.ModItems;
import moakiee.item.ParallelCard;
import moakiee.ae2oc.api.UpgradeProfile;

public final class UpgradeProfileCache {
    private long inventoryRevision = Long.MIN_VALUE;
    private long configRevision = Long.MIN_VALUE;
    private UpgradeProfile profile;
    public UpgradeProfile read(IUpgradeableObject host) {
        var inventory = host.getUpgrades();
        if (inventory == null) return new UpgradeProfile(1, false, false, false, 5, 64, 0);
        long revision = inventory instanceof UpgradeRevision versioned ? versioned.ae2oc$upgradeRevision() : Long.MIN_VALUE;
        if (profile != null && revision != Long.MIN_VALUE && inventoryRevision == revision && configRevision == Ae2OcConfig.revision()) return profile;
        boolean enabled = !Ae2OcConfig.isMachineDisabled(host);
        int parallel = 1;
        boolean capacity = false, energy = false, overclock = false;
        if (enabled) for (var stack : inventory) {
            var item = stack.getItem();
            if (item instanceof ParallelCard card) parallel = Math.max(parallel,
                    item == ModItems.PARALLEL_CARD_MAX.get() ? Ae2OcConfig.getParallelCardMaxMultiplier() : card.getMultiplier());
            capacity |= item == ModItems.CAPACITY_CARD.get();
            energy |= item == ModItems.SUPER_ENERGY_CARD.get();
            overclock |= item == ModItems.OVERCLOCK_CARD.get();
        }
        profile = new UpgradeProfile(parallel, capacity, energy, overclock, Ae2OcConfig.getOverclockCardProcessTicks(),
                Ae2OcConfig.getCapacityCardSlotLimit(), Ae2OcConfig.getSuperEnergyBufferAE());
        inventoryRevision = revision;
        configRevision = Ae2OcConfig.revision();
        return profile;
    }
    public static UpgradeProfile of(Object host) {
        if (!(host instanceof IUpgradeableObject machine)) return new UpgradeProfile(1, false, false, false, 5, 64, 0);
        return host instanceof UpgradeProfileOwner owner ? owner.ae2oc$upgradeCache().read(machine) : new UpgradeProfileCache().read(machine);
    }
}
