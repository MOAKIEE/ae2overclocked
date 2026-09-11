package moakiee.ae2oc.api;

public record UpgradeProfile(int parallelLimit, boolean capacity, boolean energy, boolean overclock,
                             int processTicks, long capacityLimit, double energyCapacity) {}
