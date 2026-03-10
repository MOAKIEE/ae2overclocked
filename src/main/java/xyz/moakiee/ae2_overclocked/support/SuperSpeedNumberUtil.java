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

    // Cached EPPConfig.busSpeed field lookup (done once, never repeated)
    private static volatile boolean ae2oc_eppCacheTried = false;
    private static volatile java.lang.reflect.Field ae2oc_eppBusSpeedField = null;

    private static int ae2oc_getExtendedAeBusSpeed() {
        if (!ae2oc_eppCacheTried) {
            synchronized (SuperSpeedNumberUtil.class) {
                if (!ae2oc_eppCacheTried) {
                    try {
                        Class<?> eppConfigClass = Class.forName("com.glodblock.github.extendedae.config.EPPConfig");
                        java.lang.reflect.Field f = eppConfigClass.getDeclaredField("busSpeed");
                        f.setAccessible(true);
                        ae2oc_eppBusSpeedField = f;
                    } catch (Throwable ignored) {
                        // ExtendedAE not present – field stays null
                    }
                    ae2oc_eppCacheTried = true;
                }
            }
        }

        if (ae2oc_eppBusSpeedField == null) {
            return 1;
        }

        try {
            int value = ae2oc_eppBusSpeedField.getInt(null);
            return Math.max(value, 1);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static int ae2oc_getSuperSpeedMultiplier() {
        return Math.max(Ae2OcConfig.getSuperSpeedCardMultiplier(), 1);
    }
}
