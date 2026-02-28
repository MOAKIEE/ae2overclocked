package moakiee.support;

import appeng.api.upgrades.IUpgradeableObject;
import moakiee.Ae2OcConfig;
import moakiee.ModItems;
import moakiee.item.ParallelCard;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 并行卡运行时支持类
 * 用于检测机器是否安装了并行卡，并返回对应的并行倍数
 *
 * 并行卡层级：×2, ×8, ×64, ×1024, Max(Integer.MAX_VALUE)
 * 每种限插 1 张，实际只有 1 张生效（取最大倍率的那张）
 */
public final class ParallelCardRuntime {

    private ParallelCardRuntime() {
    }

    /**
     * 所有等级的并行卡，按倍数从高到低排列。
     * 查找时优先返回高倍率。
     */
    @SuppressWarnings("unchecked")
    private static final java.util.function.Supplier<ItemLike>[] PARALLEL_CARDS_DESC = new java.util.function.Supplier[]{
            ModItems.PARALLEL_CARD_MAX,
            ModItems.PARALLEL_CARD_1024X,
            ModItems.PARALLEL_CARD_64X,
            ModItems.PARALLEL_CARD_8X,
            ModItems.PARALLEL_CARD
    };

    /**
     * 获取机器中安装的并行卡倍数。
     * 扫描所有等级的并行卡，返回找到的最高倍率。
     * 若未安装任何并行卡则返回 1（即单倍，不并行）。
     *
     * @param host 机器 BlockEntity 或其宿主对象
     * @return 并行倍数（>=1）
     */
    public static int getParallelMultiplier(@Nullable Object host) {
        return getParallelMultiplier(host, 0);
    }

    /**
     * 快捷检测：是否安装了任意等级的并行卡
     */
    public static boolean hasParallelCard(@Nullable Object host) {
        return getParallelMultiplier(host) > 1;
    }

    /**
     * 递归检测并行卡安装情况，返回最高倍率
     */
    private static int getParallelMultiplier(@Nullable Object target, int depth) {
        if (target == null || depth > 4) {
            return 1;
        }

        // 方式1: 通过 IUpgradeableObject 接口
        if (target instanceof IUpgradeableObject upgradeable) {
            return scanUpgradeInventory(upgradeable);
        }

        // 方式2: 通过反射 getUpgrades()
        Integer fromReflect = tryGetMultiplierFromGetUpgrades(target);
        if (fromReflect != null && fromReflect > 1) {
            return fromReflect;
        }

        // 方式3: 通过 getBlockEntity() 递归
        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        int fromBlockEntity = getParallelMultiplier(byGetBlockEntity, depth + 1);
        if (fromBlockEntity > 1) {
            return fromBlockEntity;
        }

        // 方式4: 通过 getHost() 递归
        Object byGetHost = tryInvokeNoArg(target, "getHost");
        int fromHost = getParallelMultiplier(byGetHost, depth + 1);
        if (fromHost > 1) {
            return fromHost;
        }

        // 方式5: 通过 host 字段递归
        Object byHostField = tryGetField(target, "host");
        return getParallelMultiplier(byHostField, depth + 1);
    }

    /**
     * 扫描 IUpgradeableObject 的升级库存，找到最高倍率的并行卡
     */
    private static int scanUpgradeInventory(IUpgradeableObject upgradeable) {
        var upgrades = upgradeable.getUpgrades();
        for (var cardSupplier : PARALLEL_CARDS_DESC) {
            ItemLike card = cardSupplier.get();
            if (upgrades.getInstalledUpgrades(card) > 0 && card instanceof ParallelCard pc) {
                if (card == ModItems.PARALLEL_CARD_MAX.get()) {
                    return Ae2OcConfig.getParallelCardMaxMultiplier();
                }
                return pc.getMultiplier();
            }
        }
        return 1;
    }

    /**
     * 通过反射获取 getUpgrades() 并扫描并行卡
     */
    private static Integer tryGetMultiplierFromGetUpgrades(Object target) {
        try {
            Method getUpgrades = target.getClass().getMethod("getUpgrades");
            Object upgradesInv = getUpgrades.invoke(target);
            if (upgradesInv == null) {
                return null;
            }

            Method getInstalled = upgradesInv.getClass().getMethod(
                    "getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);

            for (var cardSupplier : PARALLEL_CARDS_DESC) {
                ItemLike card = cardSupplier.get();
                Object result = getInstalled.invoke(upgradesInv, card);
                if (result instanceof Integer count && count > 0 && card instanceof ParallelCard pc) {
                    if (card == ModItems.PARALLEL_CARD_MAX.get()) {
                        return Ae2OcConfig.getParallelCardMaxMultiplier();
                    }
                    return pc.getMultiplier();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object tryGetField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}
