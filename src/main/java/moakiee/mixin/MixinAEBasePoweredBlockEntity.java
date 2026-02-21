package moakiee.mixin;

import appeng.me.energy.StoredEnergyAmount;
import moakiee.support.EnergyCardRuntime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 超级能源卡功能注入：当机器装有能源卡时，将能量缓存上限提升到超大值。
 *
 * 目标机器（均继承自 AEBasePoweredBlockEntity）：
 * - AE2 压印器 (InscriberBlockEntity)
 * - ExtendedAE 扩展压印器 (TileExInscriber)
 * - ExtendedAE 电路切片器 (TileCircuitCutter)
 * - AdvancedAE 反应仓 (ReactionChamberEntity)
 *
 * 实现原理：
 * - 拦截 getInternalMaxPower() 方法
 * - 检测机器升级槽是否安装了能源卡
 * - 如果安装，返回 10 亿 AE（约 20 亿 FE，不会溢出 int）
 * - 同时更新 stored.maximum 以确保充电逻辑正常工作
 *
 * 安全说明：
 * - getAEMaxPower() 调用 getInternalMaxPower()，自动继承修改
 * - ForgeEnergyAdapter 使用 getAEMaxPower()，FE 上限也会自动扩展
 * - 转换比例默认 1 AE = 2 FE，10 亿 AE = 20 亿 FE < Integer.MAX_VALUE
 * - 拔卡时多余电量直接截断（void），防止刷电量 Bug
 */
@Mixin(targets = "appeng.blockentity.powersink.AEBasePoweredBlockEntity", remap = false)
public class MixinAEBasePoweredBlockEntity {

    /**
     * 扩展后的最大能量存储（单位：AE）
     * 设为 10 亿 AE，转换到 FE 后约 20 亿，不会溢出 int
     */
    private static final double AE2OC_MAX_POWER = 1_000_000_000.0;

    /**
     * 缓存原版的最大能量值，用于拔卡时恢复
     */
    @Unique
    private double ae2oc$originalMaxPower = -1;

    /**
     * Shadow 访问原版的能量存储对象
     */
    @Shadow
    @Final
    private StoredEnergyAmount stored;

    /**
     * 拦截 getInternalMaxPower() 方法
     * 当安装能源卡时返回超大值，否则返回原版值
     * 
     * 关键修复：同时更新 stored.maximum 以确保 stored.insert() 正常充电
     */
    @Inject(method = "getInternalMaxPower", at = @At("RETURN"), cancellable = true)
    private void ae2oc_getInternalMaxPower(CallbackInfoReturnable<Double> cir) {
        double currentMax = stored.getMaximum();
        
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            // 安装了能源卡
            // 保存原版上限（只在第一次保存）
            if (ae2oc$originalMaxPower < 0 || currentMax < AE2OC_MAX_POWER) {
                ae2oc$originalMaxPower = currentMax;
            }
            
            // 更新 stored 的实际 maximum，这样 stored.insert() 才能正常充电
            if (currentMax < AE2OC_MAX_POWER) {
                stored.setMaximum(AE2OC_MAX_POWER);
            }
            
            cir.setReturnValue(AE2OC_MAX_POWER);
        } else {
            // 没有能源卡
            // 如果之前有扩展过，恢复到原版上限
            if (currentMax >= AE2OC_MAX_POWER && ae2oc$originalMaxPower > 0) {
                stored.setMaximum(ae2oc$originalMaxPower);
                // stored.setMaximum 会自动截断超额电量
            }
        }
    }

    /**
     * 拦截 getInternalCurrentPower() 方法
     * 
     * 拔卡保护：当玩家拔掉能源卡后，能量上限恢复到原版值。
     * 如果当前存储的能量超过新上限，直接截断（void）多余能量。
     * 
     * 这是工业模组的通用做法（热力膨胀、沉浸工程等），安全且合理。
     */
    @Inject(method = "getInternalCurrentPower", at = @At("RETURN"), cancellable = true)
    private void ae2oc_getInternalCurrentPower(CallbackInfoReturnable<Double> cir) {
        double currentPower = cir.getReturnValue();
        
        // 计算实际的最大能量上限
        double maxPower;
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            maxPower = AE2OC_MAX_POWER;
        } else {
            // 没有能源卡时，使用原版上限
            maxPower = ae2oc$originalMaxPower > 0 ? ae2oc$originalMaxPower : stored.getMaximum();
        }
        
        // 如果当前能量超过上限，截断到上限并更新存储
        if (currentPower > maxPower) {
            // 直接截断多余能量（void）
            stored.setStored(maxPower);
            cir.setReturnValue(maxPower);
        }
    }
}
