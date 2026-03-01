package xyz.moakiee.ae2_overclocked.support;

import appeng.api.upgrades.IUpgradeableObject;
import xyz.moakiee.ae2_overclocked.ModItems;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class EnergyCardRuntime {

    private EnergyCardRuntime() {
    }

    public static int getInstalledEnergyCards(Object host) {
        return getInstalledEnergyCards(host, 0);
    }

    public static boolean hasEnergyCard(Object host) {
        return getInstalledEnergyCards(host) > 0;
    }

    private static int getInstalledEnergyCards(Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.SUPER_ENERGY_CARD.get());
        }

        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        int fromBlockEntity = getInstalledEnergyCards(byGetBlockEntity, depth + 1);
        if (fromBlockEntity > 0) {
            return fromBlockEntity;
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        int fromHost = getInstalledEnergyCards(byGetHost, depth + 1);
        if (fromHost > 0) {
            return fromHost;
        }

        Object byHostField = tryGetField(target, "host");
        return getInstalledEnergyCards(byHostField, depth + 1);
    }

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        try {
            Method getUpgrades = target.getClass().getMethod("getUpgrades");
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) {
                return null;
            }
            Method getInstalled = upgrades.getClass().getMethod("getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
            Object result = getInstalled.invoke(upgrades, ModItems.SUPER_ENERGY_CARD.get());
            if (result instanceof Integer installed) {
                return installed;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryGetField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
