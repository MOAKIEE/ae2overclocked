package xyz.moakiee.ae2_overclocked.support;

import appeng.api.stacks.AEKeyType;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.helpers.externalstorage.GenericStackInv;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.ModItems;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class CapacityCardRuntime {

    private CapacityCardRuntime() {
    }

    // ── Reflection caches ──────────────────────────────────────────────
    // Key = target Class,  Value = Optional.empty() means "not found / not applicable"
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CACHE_GET_UPGRADES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CACHE_GET_INSTALLED_UPGRADES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Method>>  CACHE_METHOD_NO_ARG = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Field>>   CACHE_FIELD = new ConcurrentHashMap<>();

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

        Object tank = tryInvokeNoArg(host, "getTank");
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

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            int fromBlockEntity = getInstalledCapacityCards(byGetBlockEntity, depth + 1);
            if (fromBlockEntity > 0) {
                return fromBlockEntity;
            }
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        if (byGetHost != null) {
            int fromHost = getInstalledCapacityCards(byGetHost, depth + 1);
            if (fromHost > 0) {
                return fromHost;
            }
        }

        Object byHostField = tryGetField(target, "host");
        if (byHostField != null) {
            return getInstalledCapacityCards(byHostField, depth + 1);
        }

        return 0;
    }

    // ── Cached reflection helpers ──────────────────────────────────────

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        Class<?> clazz = target.getClass();

        // Lookup (or cache) the getUpgrades() method for this class
        Optional<Method> optGetUpgrades = CACHE_GET_UPGRADES.computeIfAbsent(clazz, c -> {
            try {
                Method m = c.getMethod("getUpgrades");
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });

        if (optGetUpgrades.isEmpty()) {
            return null; // This class has no getUpgrades() – cached, no exception thrown
        }

        try {
            Object upgrades = optGetUpgrades.get().invoke(target);
            if (upgrades == null) {
                return null;
            }

            // Lookup (or cache) the getInstalledUpgrades(ItemLike) method
            Optional<Method> optGetInstalled = CACHE_GET_INSTALLED_UPGRADES.computeIfAbsent(upgrades.getClass(), c -> {
                try {
                    Method m = c.getMethod("getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (NoSuchMethodException e) {
                    return Optional.empty();
                }
            });

            if (optGetInstalled.isEmpty()) {
                return null;
            }

            Object result = optGetInstalled.get().invoke(upgrades, ModItems.CAPACITY_CARD.get());
            if (result instanceof Integer installed) {
                return installed;
            }
        } catch (Throwable ignored) {
            // Invocation failure (not lookup failure) – rare, don't cache
        }
        return null;
    }

    /**
     * Invoke a public no-arg method by name, with Class-level caching.
     * Returns null both when the method doesn't exist and when invocation fails.
     */
    private static Object tryInvokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        String cacheKey = clazz.getName() + "#" + methodName;

        Optional<Method> opt = CACHE_METHOD_NO_ARG.computeIfAbsent(cacheKey, k -> {
            try {
                Method m = clazz.getMethod(methodName);
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });

        if (opt.isEmpty()) {
            return null;
        }

        try {
            return opt.get().invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Read a declared field by name, with Class-level caching.
     */
    private static Object tryGetField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        String cacheKey = clazz.getName() + "#" + fieldName;

        Optional<Field> opt = CACHE_FIELD.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        });

        if (opt.isEmpty()) {
            return null;
        }

        try {
            return opt.get().get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
