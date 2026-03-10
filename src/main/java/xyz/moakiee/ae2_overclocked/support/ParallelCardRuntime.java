package xyz.moakiee.ae2_overclocked.support;

import appeng.api.upgrades.IUpgradeableObject;
import net.minecraft.world.level.ItemLike;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.ModItems;
import xyz.moakiee.ae2_overclocked.item.ParallelCard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ParallelCardRuntime {

    @SuppressWarnings("unchecked")
    private static final Supplier<? extends ItemLike>[] PARALLEL_CARDS_DESC = new Supplier[]{
            ModItems.PARALLEL_CARD_MAX,
            ModItems.PARALLEL_CARD_1024X,
            ModItems.PARALLEL_CARD_64X,
            ModItems.PARALLEL_CARD_8X,
            ModItems.PARALLEL_CARD
    };

    private ParallelCardRuntime() {
    }

    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CACHE_GET_UPGRADES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CACHE_GET_INSTALLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Method>>  CACHE_METHOD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Field>>   CACHE_FIELD  = new ConcurrentHashMap<>();

    public static int getParallelMultiplier(Object host) {
        if (host == null) return 1;
        if (Ae2OcConfig.isMachineDisabled(host)) {
            return 1;
        }
        return getParallelMultiplier(host, 0);
    }

    public static boolean hasParallelCard(Object host) {
        return getParallelMultiplier(host) > 1;
    }

    private static int getParallelMultiplier(Object target, int depth) {
        if (target == null || depth > 4) {
            return 1;
        }

        if (target instanceof IUpgradeableObject upgradeable) {
            return scanUpgradeInventory(upgradeable);
        }

        Integer fromReflect = tryGetMultiplierFromGetUpgrades(target);
        if (fromReflect != null && fromReflect > 1) {
            return fromReflect;
        }

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            int fromBlockEntity = getParallelMultiplier(byGetBlockEntity, depth + 1);
            if (fromBlockEntity > 1) return fromBlockEntity;
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        if (byGetHost != null) {
            int fromHost = getParallelMultiplier(byGetHost, depth + 1);
            if (fromHost > 1) return fromHost;
        }

        Object byHostField = tryGetField(target, "host");
        if (byHostField != null) {
            return getParallelMultiplier(byHostField, depth + 1);
        }
        return 1;
    }

    private static int scanUpgradeInventory(IUpgradeableObject upgradeable) {
        var upgrades = upgradeable.getUpgrades();
        for (Supplier<? extends ItemLike> cardSupplier : PARALLEL_CARDS_DESC) {
            ItemLike card = cardSupplier.get();
            if (upgrades.getInstalledUpgrades(card) <= 0) {
                continue;
            }

            if (card == ModItems.PARALLEL_CARD_MAX.get()) {
                return Ae2OcConfig.getParallelCardMaxMultiplier();
            }

            if (card instanceof ParallelCard parallelCard) {
                return parallelCard.getMultiplier();
            }
        }
        return 1;
    }

    private static Integer tryGetMultiplierFromGetUpgrades(Object target) {
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

            for (Supplier<? extends ItemLike> cardSupplier : PARALLEL_CARDS_DESC) {
                ItemLike card = cardSupplier.get();
                Object result = optGetInstalled.get().invoke(upgrades, card);
                if (!(result instanceof Integer count) || count <= 0) {
                    continue;
                }

                if (card == ModItems.PARALLEL_CARD_MAX.get()) {
                    return Ae2OcConfig.getParallelCardMaxMultiplier();
                }

                if (card instanceof ParallelCard parallelCard) {
                    return parallelCard.getMultiplier();
                }
            }
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
