package xyz.moakiee.ae2_overclocked.support;

import appeng.api.upgrades.IUpgradeableObject;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.ModItems;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class OverclockCardRuntime {

    private OverclockCardRuntime() {
    }

    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CACHE_GET_UPGRADES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CACHE_GET_INSTALLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Method>>  CACHE_METHOD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Field>>   CACHE_FIELD  = new ConcurrentHashMap<>();

    public static int getInstalledOverclockCards(Object host) {
        if (host == null) return 0;
        if (Ae2OcConfig.isMachineDisabled(host)) {
            return 0;
        }
        return getInstalledOverclockCards(host, 0);
    }

    public static boolean hasOverclockCard(Object host) {
        return getInstalledOverclockCards(host) > 0;
    }

    private static int getInstalledOverclockCards(Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.OVERCLOCK_CARD.get());
        }

        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            int fromBlockEntity = getInstalledOverclockCards(byGetBlockEntity, depth + 1);
            if (fromBlockEntity > 0) return fromBlockEntity;
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        if (byGetHost != null) {
            int fromHost = getInstalledOverclockCards(byGetHost, depth + 1);
            if (fromHost > 0) return fromHost;
        }

        Object byHostField = tryGetField(target, "host");
        if (byHostField != null) {
            return getInstalledOverclockCards(byHostField, depth + 1);
        }
        return 0;
    }

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        Class<?> clazz = target.getClass();
        Optional<Method> optGetUpgrades = CACHE_GET_UPGRADES.computeIfAbsent(clazz, c -> {
            try {
                Method m = c.getMethod("getUpgrades");
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        if (optGetUpgrades.isEmpty()) return null;

        try {
            Object upgrades = optGetUpgrades.get().invoke(target);
            if (upgrades == null) return null;

            Optional<Method> optGetInstalled = CACHE_GET_INSTALLED.computeIfAbsent(upgrades.getClass(), c -> {
                try {
                    Method m = c.getMethod("getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (NoSuchMethodException e) {
                    return Optional.empty();
                }
            });
            if (optGetInstalled.isEmpty()) return null;

            Object result = optGetInstalled.get().invoke(upgrades, ModItems.OVERCLOCK_CARD.get());
            if (result instanceof Integer installed) return installed;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        String key = clazz.getName() + "#" + methodName;
        Optional<Method> opt = CACHE_METHOD.computeIfAbsent(key, k -> {
            try {
                Method m = clazz.getMethod(methodName);
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        if (opt.isEmpty()) return null;
        try { return opt.get().invoke(target); } catch (Throwable ignored) { return null; }
    }

    private static Object tryGetField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        String key = clazz.getName() + "#" + fieldName;
        Optional<Field> opt = CACHE_FIELD.computeIfAbsent(key, k -> {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        });
        if (opt.isEmpty()) return null;
        try { return opt.get().get(target); } catch (Throwable ignored) { return null; }
    }
}
