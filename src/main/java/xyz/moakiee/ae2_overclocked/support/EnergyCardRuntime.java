package xyz.moakiee.ae2_overclocked.support;

import appeng.api.upgrades.IUpgradeableObject;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.ModItems;

import java.lang.reflect.Method;

public final class EnergyCardRuntime {

    private EnergyCardRuntime() {
    }

    public static int getInstalledEnergyCards(Object host) {
        if (host == null) return 0;
        if (Ae2OcConfig.isMachineDisabled(host)) {
            return 0;
        }
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

        Object byGetBlockEntity = ReflectionCache.invokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            int fromBlockEntity = getInstalledEnergyCards(byGetBlockEntity, depth + 1);
            if (fromBlockEntity > 0) return fromBlockEntity;
        }

        Object byGetHost = ReflectionCache.invokeNoArg(target, "getHost");
        if (byGetHost != null) {
            int fromHost = getInstalledEnergyCards(byGetHost, depth + 1);
            if (fromHost > 0) return fromHost;
        }

        Object byHostField = ReflectionCache.getFieldValue(target, "host");
        if (byHostField != null) {
            return getInstalledEnergyCards(byHostField, depth + 1);
        }
        return 0;
    }

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        Class<?> clazz = target.getClass();
        Method getUpgrades = ReflectionCache.getMethod(clazz, "getUpgrades");
        if (getUpgrades == null) return null;

        try {
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) return null;

            Method getInstalled = ReflectionCache.getMethod(upgrades.getClass(),
                    "getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
            if (getInstalled == null) return null;

            Object result = getInstalled.invoke(upgrades, ModItems.SUPER_ENERGY_CARD.get());
            if (result instanceof Integer installed) return installed;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
