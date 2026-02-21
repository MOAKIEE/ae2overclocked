package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import moakiee.support.OverclockCardRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 超频卡功能注入 - AdvancedAE 反应仓
 * 
 * 功能：安装超频卡后，反应仓配方在1 tick内瞬间完成
 * 
 * 注意：完全使用反射访问字段和方法以避免 refMap 问题
 */
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberOverclock {

    /**
     * 在 tickingRequest 方法头部注入超频逻辑
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_overclockReactionChamberTick(IGridNode node, int ticksSinceLastCall, 
                                                     CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;

        // 检测是否安装超频卡
        if (!OverclockCardRuntime.hasOverclockCard(self)) {
            return; // 没有超频卡，执行原版逻辑
        }

        try {
            // 获取当前配方
            Method getTask = self.getClass().getDeclaredMethod("getTask");
            getTask.setAccessible(true);
            Object recipe = getTask.invoke(self);

            if (recipe == null) {
                return; // 没有配方，执行原版逻辑
            }

            // 获取配方能量消耗
            Method getEnergy = recipe.getClass().getMethod("getEnergy");
            int recipeEnergy = (int) getEnergy.invoke(recipe);

            // 获取 outputInv 字段
            Field outputInvField = ae2oc_getField(self.getClass(), "outputInv");
            outputInvField.setAccessible(true);
            Object outputInv = outputInvField.get(self);

            // 获取 inputInv 字段
            Field inputInvField = ae2oc_getField(self.getClass(), "inputInv");
            inputInvField.setAccessible(true);
            Object inputInv = inputInvField.get(self);

            // 获取 fluidInv 字段
            Field fluidInvField = ae2oc_getField(self.getClass(), "fluidInv");
            fluidInvField.setAccessible(true);
            Object fluidInv = fluidInvField.get(self);

            // 检查是否为物品输出
            Method isItemOutput = recipe.getClass().getMethod("isItemOutput");
            boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

            if (itemOutput) {
                // 物品输出 - 检查输出槽
                Method getResultItem = recipe.getClass().getMethod("getResultItem");
                ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();

                // 模拟插入检查
                Method insertItem = outputInv.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
                ItemStack remaining = (ItemStack) insertItem.invoke(outputInv, 0, outputItem, true);
                if (!remaining.isEmpty()) {
                    return; // 输出槽满，执行原版逻辑
                }

                // 检查并消耗能量
                if (!ae2oc_tryConsumePower(self, node, recipeEnergy)) {
                    return; // 能量不足，执行原版逻辑
                }

                // === 所有条件满足，瞬间完成配方 ===
                
                // 消耗材料
                ae2oc_consumeInputs(self, recipe, inputInv, fluidInv);
                
                // 输出产物
                insertItem.invoke(outputInv, 0, outputItem, false);

            } else {
                // 流体输出 - 检查流体槽
                Method getResultFluid = recipe.getClass().getMethod("getResultFluid");
                FluidStack outputFluid = (FluidStack) getResultFluid.invoke(recipe);
                
                // 检查能否添加流体
                Method canAdd = fluidInv.getClass().getMethod("canAdd", int.class, AEKey.class, long.class);
                boolean canAddFluid = (boolean) canAdd.invoke(fluidInv, 0, 
                        AEFluidKey.of(outputFluid), (long) outputFluid.getAmount());
                
                if (!canAddFluid) {
                    return; // 流体槽满，执行原版逻辑
                }

                // 检查并消耗能量
                if (!ae2oc_tryConsumePower(self, node, recipeEnergy)) {
                    return; // 能量不足，执行原版逻辑
                }

                // === 所有条件满足，瞬间完成配方 ===
                
                // 消耗材料
                ae2oc_consumeInputs(self, recipe, inputInv, fluidInv);
                
                // 输出流体产物
                Method add = fluidInv.getClass().getMethod("add", int.class, AEKey.class, long.class);
                add.invoke(fluidInv, 0, AEFluidKey.of(outputFluid), (long) outputFluid.getAmount());
            }

            // 重置状态
            Field processingTimeField = ae2oc_getField(self.getClass(), "processingTime");
            processingTimeField.setAccessible(true);
            processingTimeField.setInt(self, 0);
            
            // 清除缓存的配方
            Field cachedTaskField = ae2oc_getField(self.getClass(), "cachedTask");
            cachedTaskField.setAccessible(true);
            cachedTaskField.set(self, null);
            
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
     * 消耗配方所需的输入材料
     */
    @Unique
    private void ae2oc_consumeInputs(Object self, Object recipe, Object inputInv, Object fluidInv) {
        try {
            // 获取有效输入
            Method getValidInputs = recipe.getClass().getMethod("getValidInputs");
            @SuppressWarnings("unchecked")
            java.util.List<Object> validInputs = (java.util.List<Object>) getValidInputs.invoke(recipe);
            
            // 获取流体输入 (slot 1)
            Method getStack = fluidInv.getClass().getMethod("getStack", int.class);
            Object fluid = getStack.invoke(fluidInv, 1);
            FluidStack fluidStack = null;
            if (fluid != null) {
                Method getWhat = fluid.getClass().getMethod("what");
                Object aeKey = getWhat.invoke(fluid);
                if (aeKey instanceof AEFluidKey key) {
                    Method getAmount = fluid.getClass().getMethod("amount");
                    long amount = (long) getAmount.invoke(fluid);
                    fluidStack = key.toStack((int) amount);
                }
            }
            
            // 获取 inputInv 大小
            Method getSize = inputInv.getClass().getMethod("size");
            int invSize = (int) getSize.invoke(inputInv);
            
            for (Object input : validInputs) {
                // 尝试从物品槽消耗
                for (int x = 0; x < invSize; x++) {
                    Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, x);
                    
                    // 检查类型
                    Method checkType = input.getClass().getMethod("checkType", Object.class);
                    boolean matches = (boolean) checkType.invoke(input, stack);
                    
                    if (matches) {
                        // 消耗物品
                        Method consume = input.getClass().getMethod("consume", Object.class);
                        consume.invoke(input, stack);
                        
                        Method setItemDirect = inputInv.getClass().getMethod("setItemDirect", int.class, ItemStack.class);
                        setItemDirect.invoke(inputInv, x, stack);
                    }
                    
                    // 检查是否已消耗完
                    Method isEmpty = input.getClass().getMethod("isEmpty");
                    if ((boolean) isEmpty.invoke(input)) {
                        break;
                    }
                }
                
                // 尝试从流体槽消耗
                Method isEmpty = input.getClass().getMethod("isEmpty");
                if (fluidStack != null && !(boolean) isEmpty.invoke(input)) {
                    Method checkType = input.getClass().getMethod("checkType", Object.class);
                    if ((boolean) checkType.invoke(input, fluidStack)) {
                        Method consume = input.getClass().getMethod("consume", Object.class);
                        consume.invoke(input, fluidStack);
                    }
                }
            }
            
            // 更新流体槽
            if (fluidStack != null) {
                Method setStack = fluidInv.getClass().getMethod("setStack", int.class, GenericStack.class);
                if (fluidStack.isEmpty()) {
                    setStack.invoke(fluidInv, 1, null);
                } else {
                    setStack.invoke(fluidInv, 1, new GenericStack(
                            Objects.requireNonNull(AEFluidKey.of(fluidStack)), fluidStack.getAmount()));
                }
            }
        } catch (Exception e) {
            // 忽略
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
}
