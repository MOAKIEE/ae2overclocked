package moakiee.support;

import moakiee.Ae2OcConfig;
import moakiee.ae2oc.core.quantity.SaturatedMath;

/** Applies only our card multiplier; upstream already owns its native speed factors. */
public final class SuperSpeedNumberUtil {
    private SuperSpeedNumberUtil() {}
    public static int convertLongToIntSaturating(long value) {
        return (int) Math.min(boostLongSaturating(value), Ae2OcConfig.getMaxRecipeOperationsPerMachineTick());
    }
    public static long boostLongSaturating(long value) {
        return Math.min(SaturatedMath.multiplyNonNegative(Math.max(0, value),
                Ae2OcConfig.getSuperSpeedCardMultiplier()), Ae2OcConfig.getMaxTransferAmountPerMachineTick());
    }
}
