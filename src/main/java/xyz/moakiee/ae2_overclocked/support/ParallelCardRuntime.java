package xyz.moakiee.ae2_overclocked.support;

import appeng.api.upgrades.IUpgradeableObject;
import net.minecraft.world.level.ItemLike;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.ModItems;
import xyz.moakiee.ae2_overclocked.item.ParallelCard;

import java.lang.reflect.Method;
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

        Object byGetBlockEntity = ReflectionCache.invokeNoArg(target, "getBlockEntity");
        if (byGetBlockEntity != null) {
            int fromBlockEntity = getParallelMultiplier(byGetBlockEntity, depth + 1);
            if (fromBlockEntity > 1) return fromBlockEntity;
        }

        Object byGetHost = ReflectionCache.invokeNoArg(target, "getHost");
        if (byGetHost != null) {
            int fromHost = getParallelMultiplier(byGetHost, depth + 1);
            if (fromHost > 1) return fromHost;
        }

        Object byHostField = ReflectionCache.getFieldValue(target, "host");
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
        Method getUpgrades = ReflectionCache.getMethod(clazz, "getUpgrades");
        if (getUpgrades == null) return null;

        try {
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) return null;

            Method getInstalled = ReflectionCache.getMethod(upgrades.getClass(),
                    "getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
            if (getInstalled == null) return null;

            for (Supplier<? extends ItemLike> cardSupplier : PARALLEL_CARDS_DESC) {
                ItemLike card = cardSupplier.get();
                Object result = getInstalled.invoke(upgrades, card);
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
}
