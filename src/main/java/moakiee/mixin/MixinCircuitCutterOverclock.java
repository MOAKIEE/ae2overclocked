package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.ticking.TickRateModulation;
import moakiee.support.OverclockCardRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 超频卡功能注入 - ExtendedAE 电路切片器
 * 
 * 功能：安装超频卡后，电路切片器配方在1 tick内瞬间完成
 * 
 * 注意：完全使用反射访问字段和方法以避免 refMap 问题
 */
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter", remap = false)
public abstract class MixinCircuitCutterOverclock {

    /**
     * 电路切片器配方总能量消耗 (MAX_PROGRESS * 10 = 2000)
     */
    @Unique
    private static final double AE2OC_CUTTER_RECIPE_ENERGY = 2000.0;

    /**
     * 在 tickingRequest 方法头部注入超频逻辑
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_overclockCutterTick(IGridNode node, int ticksSinceLastCall, 
                                            CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;

        // 检测是否安装超频卡
        if (!OverclockCardRuntime.hasOverclockCard(self)) {
            return; // 没有超频卡，执行原版逻辑
        }

        try {
            // 获取 ctx 字段
            Field ctxField = ae2oc_getField(self.getClass(), "ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(self);

            // 获取 currentRecipe 字段（在父类 ContainerRecipeContext 中）
            Field recipeField = ae2oc_getField(ctx.getClass(), "currentRecipe");
            recipeField.setAccessible(true);
            Object currentRecipe = recipeField.get(ctx);

            // 如果没有配方，尝试查找
            if (currentRecipe == null) {
                // 检查 shouldTick
                Method shouldTick = ctx.getClass().getMethod("shouldTick");
                if ((boolean) shouldTick.invoke(ctx)) {
                    Method findRecipe = ae2oc_getMethod(ctx.getClass(), "findRecipe");
                    findRecipe.setAccessible(true);
                    findRecipe.invoke(ctx);
                    currentRecipe = recipeField.get(ctx);
                }
            }

            if (currentRecipe == null) {
                return; // 仍然没有配方，执行原版逻辑
            }

            // 获取 output 字段
            Field outputField = ae2oc_getField(self.getClass(), "output");
            outputField.setAccessible(true);
            Object output = outputField.get(self);

            // 获取配方输出（直接通过 output 字段访问）
            Field recipeOutputField = ae2oc_getField(currentRecipe.getClass(), "output");
            recipeOutputField.setAccessible(true);
            ItemStack recipeOutput = ((ItemStack) recipeOutputField.get(currentRecipe)).copy();

            // 检查输出槽是否有空间（模拟插入）
            Method insertItem = output.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
            ItemStack remaining = (ItemStack) insertItem.invoke(output, 0, recipeOutput, true);
            if (!remaining.isEmpty()) {
                return; // 输出槽满，执行原版逻辑
            }

            // 检查配方是否可执行（材料检查）
            Method testRecipe = ctx.getClass().getMethod("testRecipe", Recipe.class);
            boolean canRun = (boolean) testRecipe.invoke(ctx, currentRecipe);
            if (!canRun) {
                return; // 材料不足，执行原版逻辑
            }

            // 检查并消耗能量
            if (!ae2oc_tryConsumePower(self, node, AE2OC_CUTTER_RECIPE_ENERGY)) {
                return; // 能量不足，执行原版逻辑
            }

            // === 所有条件满足，瞬间完成配方 ===
            
            // 执行配方（消耗材料）
            Method runRecipe = ctx.getClass().getMethod("runRecipe", Recipe.class);
            runRecipe.invoke(ctx, currentRecipe);
            
            // 输出产物
            insertItem.invoke(output, 0, recipeOutput, false);
            
            // 重置状态
            Field progressField = ae2oc_getField(self.getClass(), "progress");
            progressField.setAccessible(true);
            progressField.setInt(self, 0);
            
            recipeField.set(ctx, null);
            
            // setWorking(false)
            Method setWorking = self.getClass().getMethod("setWorking", boolean.class);
            setWorking.invoke(self, false);
            
            ae2oc_markForUpdate(self);
            ae2oc_saveChanges(self);

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
     * 递归获取方法（包括父类）
     */
    @Unique
    private Method ae2oc_getMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return ae2oc_getMethod(superClass, methodName, paramTypes);
            }
            throw e;
        }
    }

    /**
     * 调用 markForUpdate()
     */
    @Unique
    private void ae2oc_markForUpdate(Object self) {
        try {
            Method method = self.getClass().getMethod("markForUpdate");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    /**
     * 调用 saveChanges()
     */
    @Unique
    private void ae2oc_saveChanges(Object self) {
        try {
            Method method = self.getClass().getMethod("saveChanges");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    /**
     * 尝试消耗配方所需的全部能量
     */
    @Unique
    private boolean ae2oc_tryConsumePower(Object self, IGridNode node, double powerNeeded) {
        try {
            // 通过反射调用 extractAEPower()
            Method extractMethod = self.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            
            // 先模拟
            double extracted = (double) extractMethod.invoke(self, powerNeeded, 
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            
            if (extracted >= powerNeeded - 0.01) {
                // 内部缓存足够，正式消耗
                extractMethod.invoke(self, powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }

            // 尝试从网络提取
            var grid = node.getGrid();
            if (grid != null) {
                IEnergyService energyService = grid.getEnergyService();
                double networkExtracted = energyService.extractAEPower(powerNeeded, Actionable.SIMULATE, 
                        PowerMultiplier.CONFIG);
                
                if (networkExtracted >= powerNeeded - 0.01) {
                    energyService.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                    return true;
                }
            }
        } catch (Exception e) {
            // 反射失败
        }
        
        return false;
    }
}
