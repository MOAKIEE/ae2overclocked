package moakiee.support;

import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;

public final class CapacityCardRuntime {
    private CapacityCardRuntime() {}
    public static int getInstalledCapacityCards(Object host) { return UpgradeProfileCache.of(host).capacity() ? 1 : 0; }
}
