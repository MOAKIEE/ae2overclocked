package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.support.OverclockCardRuntime;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 超频卡功能注入 - ExtendedAE 扩展压印器线程
 * 
 * ExtendedAE 的压印器使用 InscriberThread 作为工作单元，
 * 每个 TileExInscriber 包含 4 个 InscriberThread。
 * 
 * 功能：安装超频卡后，每个线程的配方在1 tick内瞬间完成
 * 
 * 注意：完全使用反射访问字段和方法以避免 refMap 问题
 */
@Mixin(targets = "com.glodblock.github.extendedae.common.me.InscriberThread", remap = false)
public abstract class MixinExInscriberThreadOverclock {

    /**
     * 配方总能量消耗（每线程）
     */
    @Unique
    private static final double AE2OC_THREAD_RECIPE_ENERGY = 2000.0;

    /**
     * 在 tick() 方法头部注入超频逻辑
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ae2oc_overclockThreadTick(CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;
        
        try {
            // 获取 host 字段
            Field hostField = ae2oc_getField(self.getClass(), "host");
            hostField.setAccessible(true);
            Object host = hostField.get(self);
            
            // 检测宿主是否安装超频卡
            if (!OverclockCardRuntime.hasOverclockCard(host)) {
                return; // 没有超频卡，执行原版逻辑
            }

            // 获取 smash 字段
            Field smashField = ae2oc_getField(self.getClass(), "smash");
            smashField.setAccessible(true);
            boolean smash = smashField.getBoolean(self);
            
            // 获取 finalStep 字段
            Field finalStepField = ae2oc_getField(self.getClass(), "finalStep");
            finalStepField.setAccessible(true);
            int finalStep = finalStepField.getInt(self);

            // 如果正在播放动画，使用加速动画逻辑
            if (smash) {
                finalStep += 4; // 加速4倍
                finalStepField.setInt(self, finalStep);
                
                if (finalStep >= 8 && finalStep < 16) {
                    // 执行产物输出
                    ae2oc_finishCraft(self, host);
                    finalStepField.setInt(self, 16);
                }
                if (finalStep >= 16) {
                    finalStepField.setInt(self, 0);
                    // setSmash(false)
                    Method setSmash = self.getClass().getMethod("setSmash", boolean.class);
                    setSmash.invoke(self, false);
                    ae2oc_markHostForUpdate(host);
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }

            // 获取当前配方
            Method getTask = self.getClass().getMethod("getTask");
            InscriberRecipe recipe = (InscriberRecipe) getTask.invoke(self);
            if (recipe == null) {
                return; // 没有配方，执行原版逻辑
            }

            // 获取 sideItemHandler 字段
            Field sideItemHandlerField = ae2oc_getField(self.getClass(), "sideItemHandler");
            sideItemHandlerField.setAccessible(true);
            Object sideItemHandler = sideItemHandlerField.get(self);

            // 检查输出槽是否有空间（模拟插入）
            ItemStack outputCopy = recipe.getResultItem().copy();
            Method insertItem = sideItemHandler.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
            ItemStack remaining = (ItemStack) insertItem.invoke(sideItemHandler, 1, outputCopy, true);
            if (!remaining.isEmpty()) {
                return; // 输出槽满，执行原版逻辑
            }

            // 检查并消耗能量
            if (!ae2oc_tryConsumePower(host, AE2OC_THREAD_RECIPE_ENERGY)) {
                return; // 能量不足，执行原版逻辑
            }

            // === 所有条件满足，瞬间完成配方 ===
            
            // 触发完成动画
            Method setSmash = self.getClass().getMethod("setSmash", boolean.class);
            setSmash.invoke(self, true);
            finalStepField.setInt(self, 0);
            ae2oc_markHostForUpdate(host);

            cir.setReturnValue(TickRateModulation.URGENT);
            
        } catch (Exception e) {
            // 反射失败，执行原版逻辑
        }
    }

    /**
     * 递归获取字段（包括父类）
     */
    @Unique
    private Field ae2oc_getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return ae2oc_getField(superClass, fieldName);
            }
            throw e;
        }
    }

    /**
     * 完成配方制作（消耗材料和产出物品）
     */
    @Unique
    private void ae2oc_finishCraft(Object self, Object host) {
        try {
            Method getTask = self.getClass().getMethod("getTask");
            InscriberRecipe recipe = (InscriberRecipe) getTask.invoke(self);
            if (recipe == null) {
                return;
            }

            // 获取 inventory 字段
            Field sideItemHandlerField = ae2oc_getField(self.getClass(), "sideItemHandler");
            sideItemHandlerField.setAccessible(true);
            Object sideItemHandler = sideItemHandlerField.get(self);
            
            Field topItemHandlerField = ae2oc_getField(self.getClass(), "topItemHandler");
            topItemHandlerField.setAccessible(true);
            Object topItemHandler = topItemHandlerField.get(self);
            
            Field bottomItemHandlerField = ae2oc_getField(self.getClass(), "bottomItemHandler");
            bottomItemHandlerField.setAccessible(true);
            Object bottomItemHandler = bottomItemHandlerField.get(self);

            ItemStack outputCopy = recipe.getResultItem().copy();

            // 插入产物
            Method insertItem = sideItemHandler.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
            ItemStack remaining = (ItemStack) insertItem.invoke(sideItemHandler, 1, outputCopy, false);
            
            if (remaining.isEmpty()) {
                // 成功插入，重置进度
                Method setProcessingTime = self.getClass().getDeclaredMethod("setProcessingTime", int.class);
                setProcessingTime.setAccessible(true);
                setProcessingTime.invoke(self, 0);

                // 消耗模板（如果是 PRESS 类型）
                Method extractItem = topItemHandler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
                if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                    extractItem.invoke(topItemHandler, 0, 1, false);
                    extractItem.invoke(bottomItemHandler, 0, 1, false);
                }

                // 消耗输入材料
                Method sideExtract = sideItemHandler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
                sideExtract.invoke(sideItemHandler, 0, 1, false);
            }

            ae2oc_saveHostChanges(host);
            
        } catch (Exception e) {
            // 反射失败
        }
    }

    /**
     * 尝试消耗配方所需的全部能量
     */
    @Unique
    private boolean ae2oc_tryConsumePower(Object host, double powerNeeded) {
        try {
            // 通过反射调用 host.extractAEPower()
            Method extractMethod = host.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            
            // 先模拟
            double extracted = (double) extractMethod.invoke(host, powerNeeded, 
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            
            if (extracted >= powerNeeded - 0.01) {
                // 内部缓存足够，正式消耗
                extractMethod.invoke(host, powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }

            // 尝试从网络提取
            Method getMainNode = host.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(host);
            
            if (mainNode != null) {
                Method getGrid = mainNode.getClass().getMethod("getGrid");
                Object grid = getGrid.invoke(mainNode);
                
                if (grid != null) {
                    Method getEnergyService = grid.getClass().getMethod("getEnergyService");
                    IEnergyService energyService = (IEnergyService) getEnergyService.invoke(grid);
                    
                    double networkExtracted = energyService.extractAEPower(powerNeeded, Actionable.SIMULATE, 
                            PowerMultiplier.CONFIG);
                    
                    if (networkExtracted >= powerNeeded - 0.01) {
                        energyService.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // 反射失败，返回 false
        }
        
        return false;
    }

    /**
     * 通过反射调用 host.markForUpdate()
     */
    @Unique
    private void ae2oc_markHostForUpdate(Object host) {
        try {
            Method method = host.getClass().getMethod("markForUpdate");
            method.invoke(host);
        } catch (Exception ignored) {
        }
    }

    /**
     * 通过反射调用 host.saveChanges()
     */
    @Unique
    private void ae2oc_saveHostChanges(Object host) {
        try {
            Method method = host.getClass().getMethod("saveChanges");
            method.invoke(host);
        } catch (Exception ignored) {
        }
    }
}
