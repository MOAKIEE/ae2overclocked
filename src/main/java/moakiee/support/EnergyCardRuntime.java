package moakiee.support;

import appeng.api.upgrades.IUpgradeableObject;
import moakiee.ModItems;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 超级能源卡运行时支持类
 * 用于检测机器是否安装了能源卡并提供能量缓存扩展功能
 */
public final class EnergyCardRuntime {

    private EnergyCardRuntime() {
    }

    /**
     * 检测指定宿主是否安装了能源卡
     *
     * @param host 机器 BlockEntity 或其宿主对象
     * @return 安装的能源卡数量（通常为 0 或 1）
     */
    public static int getInstalledEnergyCards(@Nullable Object host) {
        return getInstalledEnergyCards(host, 0);
    }

    /**
     * 检测机器是否应使用超大能量缓存
     *
     * @param host 机器 BlockEntity
     * @return 如果安装了能源卡则返回 true
     */
    public static boolean hasEnergyCard(@Nullable Object host) {
        return getInstalledEnergyCards(host) > 0;
    }

    /**
     * 获取扩展后的最大能量存储
     * 当安装能源卡时返回超大值，否则返回原版值
     *
     * @param host         机器 BlockEntity
     * @param defaultPower 原版默认的最大能量
     * @return 扩展后的最大能量（单位：AE）
     */
    public static double getExpandedMaxPower(@Nullable Object host, double defaultPower) {
        if (hasEnergyCard(host)) {
            // 返回 1_000_000_000 AE (10亿 AE)
            // 转换到 FE 后大约是 20 亿 FE（默认比率 1 AE = 2 FE）
            // 不会溢出 int (最大值约 21.4 亿)
            return 1_000_000_000.0;
        }
        return defaultPower;
    }

    /**
     * 递归检测能源卡安装数量
     */
    private static int getInstalledEnergyCards(@Nullable Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        // 直接实现 IUpgradeableObject 接口
        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.SUPER_ENERGY_CARD.get());
        }

        // 尝试通过 getUpgrades() 方法获取
        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        // 尝试通过 getBlockEntity() 方法获取宿主
        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        int fromBlockEntity = getInstalledEnergyCards(byGetBlockEntity, depth + 1);
        if (fromBlockEntity > 0) {
            return fromBlockEntity;
        }

        // 尝试通过 getHost() 方法获取宿主
        Object byGetHost = tryInvokeNoArg(target, "getHost");
        int fromHost = getInstalledEnergyCards(byGetHost, depth + 1);
        if (fromHost > 0) {
            return fromHost;
        }

        // 尝试通过 host 字段获取宿主
        Object byHostField = tryGetField(target, "host");
        return getInstalledEnergyCards(byHostField, depth + 1);
    }

    /**
     * 通过反射调用 getUpgrades().getInstalledUpgrades(SUPER_ENERGY_CARD)
     */
    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        try {
            Method getUpgrades = target.getClass().getMethod("getUpgrades");
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) {
                return null;
            }
            Method getInstalled = upgrades.getClass().getMethod(
                    "getInstalledUpgrades",
                    net.minecraft.world.level.ItemLike.class
            );
            Object result = getInstalled.invoke(upgrades, ModItems.SUPER_ENERGY_CARD.get());
            if (result instanceof Integer i) {
                return i;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 通过反射调用无参方法
     */
    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 通过反射获取字段值
     */
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
