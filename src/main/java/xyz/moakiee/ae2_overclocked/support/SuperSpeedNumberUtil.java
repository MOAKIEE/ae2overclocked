/*
 * This file includes code adapted from MakeAE2Better by QiuYe.
 * Licensed under the MIT License.
 * Original Source: https://github.com/qiuye2024github/MaekAE2Better
 * Full license text: src/main/resources/LICENSE_MakeAE2Better.txt
 */
package xyz.moakiee.ae2_overclocked.support;

import xyz.moakiee.ae2_overclocked.Ae2OcConfig;

import java.lang.reflect.Field;

public final class SuperSpeedNumberUtil {

    private SuperSpeedNumberUtil() {
    }

    public static int convertLongToIntSaturating(long value) {
        long result = value * ae2oc_getSuperSpeedMultiplier() * ae2oc_getExtendedAeBusSpeed();
        if (result > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        } else if (result < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        } else {
            return (int) result;
        }
    }

    public static long boostLongSaturating(long value) {
        long multiplier = (long) ae2oc_getSuperSpeedMultiplier() * ae2oc_getExtendedAeBusSpeed();
        if (value <= 0L || multiplier <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static int ae2oc_getExtendedAeBusSpeed() {
        try {
            Class<?> eppConfigClass = Class.forName("com.glodblock.github.extendedae.config.EPPConfig");
            Field busSpeedField = eppConfigClass.getDeclaredField("busSpeed");
            busSpeedField.setAccessible(true);
            int value = busSpeedField.getInt(null);
            return Math.max(value, 1);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static int ae2oc_getSuperSpeedMultiplier() {
        return Math.max(Ae2OcConfig.getSuperSpeedCardMultiplier(), 1);
    }
}
