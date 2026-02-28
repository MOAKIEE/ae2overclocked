package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.me.energy.StoredEnergyAmount;
import moakiee.Ae2OcConfig;
import moakiee.support.EnergyCardRuntime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 超级能源卡功能注入：装有能源卡时，将能量缓存上限提升到超大值。
 * 拦截 getInternalMaxPower()，同时更新 stored.maximum。
 */
@Mixin(targets = "appeng.blockentity.powersink.AEBasePoweredBlockEntity", remap = false)
public class MixinAEBasePoweredBlockEntity {

    @Unique
    private double ae2oc$originalMaxPower = -1;

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
        ae2oc_updateStoredMaximum();
        
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            cir.setReturnValue(Ae2OcConfig.getSuperEnergyBufferAE());
        }
    }

    /**
     * 拦截 injectAEPower() 方法（FE 充电路径的关键入口）
     * 在充电前先更新 stored.maximum，确保 stored.insert() 能正常工作
     */
    @Inject(method = "injectAEPower", at = @At("HEAD"))
    private void ae2oc_injectAEPower(double amt, Actionable mode, CallbackInfoReturnable<Double> cir) {
        ae2oc_updateStoredMaximum();
    }

    /**
     * 统一的能量上限更新逻辑
     */
    @Unique
    private void ae2oc_updateStoredMaximum() {
        double currentMax = stored.getMaximum();
        double configuredMaxPower = Ae2OcConfig.getSuperEnergyBufferAE();
        
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            // 安装了能源卡
            // 保存原版上限（只在第一次保存）
            if (ae2oc$originalMaxPower < 0 || currentMax < configuredMaxPower) {
                ae2oc$originalMaxPower = currentMax;
            }
            
            // 更新 stored 的实际 maximum，这样 stored.insert() 才能正常充电
            if (currentMax < configuredMaxPower) {
                stored.setMaximum(configuredMaxPower);
            }
        } else {
            // 没有能源卡
            // 如果之前有扩展过，恢复到原版上限
            if (currentMax > ae2oc$originalMaxPower && ae2oc$originalMaxPower > 0) {
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
        double configuredMaxPower = Ae2OcConfig.getSuperEnergyBufferAE();
        
        // 计算实际的最大能量上限
        double maxPower;
        if (EnergyCardRuntime.hasEnergyCard(this)) {
            maxPower = configuredMaxPower;
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
