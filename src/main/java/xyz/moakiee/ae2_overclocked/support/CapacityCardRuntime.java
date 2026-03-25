package xyz.moakiee.ae2_overclocked.support;

import appeng.api.stacks.AEKeyType;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.helpers.externalstorage.GenericStackInv;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.ModItems;

import java.lang.reflect.Method;

public final class CapacityCardRuntime {

    private CapacityCardRuntime() {
    }

    // ── Public API ─────────────────────────────────────────────────────

    public static int getInstalledCapacityCards(Object host) {
        if (host == null) {
            return 0;
        }
        if (Ae2OcConfig.isMachineDisabled(host)) {
            return 0;
        }
        return getInstalledCapacityCards(host, 0);
    }

    public static void applyFluidCapacity(Object host, long defaultCapacity, long upgradedCapacity) {
        if (host == null) {
            return;
        }

        Object tank = ReflectionCache.invokeNoArg(host, "getTank");
        if (!(tank instanceof GenericStackInv inv)) {
            return;
        }

        long targetCapacity = Ae2OcConfig.isMachineDisabled(host)
                ? defaultCapacity
                : (getInstalledCapacityCards(host) > 0 ? upgradedCapacity : defaultCapacity);
        inv.setCapacity(AEKeyType.fluids(), targetCapacity);
    }

    // ── Core recursive lookup (with depth guard) ───────────────────────

    private static int getInstalledCapacityCards(Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        // Fast path – direct interface check, no reflection needed
        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.CAPACITY_CARD.get());
        }

        // Try reflection-based getUpgrades() with cache
        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        Object byGetBlockEntity = ReflectionCache.invokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            int fromBlockEntity = getInstalledCapacityCards(byGetBlockEntity, depth + 1);
            if (fromBlockEntity > 0) {
                return fromBlockEntity;
            }
        }

        Object byGetHost = ReflectionCache.invokeNoArg(target, "getHost");
        if (byGetHost != null) {
            int fromHost = getInstalledCapacityCards(byGetHost, depth + 1);
            if (fromHost > 0) {
                return fromHost;
            }
        }

        Object byHostField = ReflectionCache.getFieldValue(target, "host");
        if (byHostField != null) {
            return getInstalledCapacityCards(byHostField, depth + 1);
        }

        return 0;
    }

    // ── Cached reflection helpers ──────────────────────────────────────

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        Class<?> clazz = target.getClass();

        Method getUpgrades = ReflectionCache.getMethod(clazz, "getUpgrades");
        if (getUpgrades == null) {
            return null;
        }

        try {
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) {
                return null;
            }

            Method getInstalled = ReflectionCache.getMethod(upgrades.getClass(),
                    "getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
            if (getInstalled == null) {
                return null;
            }

            Object result = getInstalled.invoke(upgrades, ModItems.CAPACITY_CARD.get());
            if (result instanceof Integer installed) {
                return installed;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
