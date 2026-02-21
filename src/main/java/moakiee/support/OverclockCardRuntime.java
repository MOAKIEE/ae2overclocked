package moakiee.support;

import appeng.api.upgrades.IUpgradeableObject;
import moakiee.ModItems;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 超频卡运行时支持类
 * 用于检测机器是否安装了超频卡，并提供1-tick瞬间完工功能
 * 
 * 功能说明：
 * - 安装超频卡后，机器配方在1 tick内完成（不包含动画时间）
 * - 需要一次性支付全部配方能量消耗
 * - 与能源卡配合使用效果最佳（能源卡扩展能量缓存）
 * 
 * 安全机制：
 * - 必须有足够能量才能瞬间完成
 * - 必须有足够输出空间才能完成
 * - 能量不足时回退到原版速度
 */
public final class OverclockCardRuntime {

    private OverclockCardRuntime() {
    }

    /**
     * 检测指定宿主是否安装了超频卡
     *
     * @param host 机器 BlockEntity 或其宿主对象
     * @return 安装的超频卡数量（通常为 0 或 1）
     */
    public static int getInstalledOverclockCards(@Nullable Object host) {
        return getInstalledOverclockCards(host, 0);
    }

    /**
     * 检测机器是否应使用超频模式（1-tick 完工）
     *
     * @param host 机器 BlockEntity
     * @return 如果安装了超频卡则返回 true
     */
    public static boolean hasOverclockCard(@Nullable Object host) {
        return getInstalledOverclockCards(host) > 0;
    }

    /**
     * 获取压印器的配方总能量消耗
     * 
     * 原版压印器规则：
     * - MAX_PROCESSING_STEPS = 200
     * - 每 tick 能耗 = 10 * speedFactor
     * - 每 tick 进度 = speedFactor
     * - 总 tick 数 ≈ 200 / speedFactor (向上取整)
     * - 总能耗 ≈ 10 * 200 = 2000 AE (与速度卡无关)
     * 
     * @return 压印器配方总能量消耗（单位：AE）
     */
    public static double getInscriberRecipeEnergy() {
        // 固定为 2000 AE
        // 这是标准压印器配方的能量消耗
        return 2000.0;
    }

    /**
     * 获取电路切片器的配方总能量消耗
     * 
     * ExtendedAE 电路切片器规则：
     * - MAX_PROGRESS = 200
     * - 能耗计算与压印器类似
     * 
     * @return 电路切片器配方总能量消耗（单位：AE）
     */
    public static double getCircuitCutterRecipeEnergy() {
        // 固定为 2000 AE
        return 2000.0;
    }

    /**
     * 递归检测超频卡安装数量
     */
    private static int getInstalledOverclockCards(@Nullable Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        // 直接实现 IUpgradeableObject 接口
        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.OVERCLOCK_CARD.get());
        }

        // 尝试通过 getUpgrades() 方法获取
        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        // 尝试通过 getBlockEntity() 方法获取宿主
        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        int fromBlockEntity = getInstalledOverclockCards(byGetBlockEntity, depth + 1);
        if (fromBlockEntity > 0) {
            return fromBlockEntity;
        }

        // 尝试通过 getHost() 方法获取宿主
        Object byGetHost = tryInvokeNoArg(target, "getHost");
        int fromHost = getInstalledOverclockCards(byGetHost, depth + 1);
        if (fromHost > 0) {
            return fromHost;
        }

        // 尝试通过 host 字段获取宿主
        Object byHostField = tryGetField(target, "host");
        return getInstalledOverclockCards(byHostField, depth + 1);
    }

    /**
     * 通过反射调用 getUpgrades().getInstalledUpgrades(OVERCLOCK_CARD)
     */
    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        try {
            Method getUpgrades = target.getClass().getMethod("getUpgrades");
            Object upgradesInv = getUpgrades.invoke(target);
            if (upgradesInv != null) {
                Method getInstalled = upgradesInv.getClass().getMethod("getInstalledUpgrades", 
                        net.minecraft.world.level.ItemLike.class);
                Object result = getInstalled.invoke(upgradesInv, ModItems.OVERCLOCK_CARD.get());
                if (result instanceof Integer count) {
                    return count;
                }
            }
        } catch (Exception ignored) {
            // 方法不存在或调用失败，继续尝试其他方式
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
        } catch (Exception ignored) {
            // 方法不存在或调用失败
        }
        return null;
    }

    /**
     * 通过反射获取字段值
     */
    private static Object tryGetField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ignored) {
            // 字段不存在或访问失败
        }
        return null;
    }
}
