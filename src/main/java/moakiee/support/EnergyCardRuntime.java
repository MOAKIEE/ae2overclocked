package moakiee.support;

import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;

public final class EnergyCardRuntime {
    private EnergyCardRuntime() {}
    public static boolean hasEnergyCard(Object host) { return UpgradeProfileCache.of(host).energy(); }
    public static int getInstalledEnergyCards(Object host) { return hasEnergyCard(host) ? 1 : 0; }
}
