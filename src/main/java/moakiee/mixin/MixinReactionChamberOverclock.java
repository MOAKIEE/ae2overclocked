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
import moakiee.support.ParallelCardRuntime;
import moakiee.support.ParallelEngine;
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
 * 并行卡 + 超频卡 功能注入 — AdvancedAE 反应仓
 *
 * 行为定义：
 * - 仅超频卡：瞬间完成，单倍产出
 * - 仅并行卡：正常进度条速度，完成时多倍产出
 * - 两者兼有：瞬间完成 + 多倍产出
 *
 * 实现策略（仅并行模式）：
 * - HEAD 注入：缓存 processingTime 和配方信息
 * - RETURN 注入：检测 processingTime 从非零变为零 → 追加 (P-1) 倍产出
 */
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberOverclock {

    // ===== 跨 HEAD/RETURN 缓存 =====
    @Unique
    private boolean ae2oc_processing = false;
    @Unique
    private int ae2oc_prevProcessingTime = -1;
    @Unique
    private int ae2oc_pendingParallel = 0;
    @Unique
    private boolean ae2oc_cachedIsItemOutput = true;
    @Unique
    private ItemStack ae2oc_cachedItemOutput = ItemStack.EMPTY;
    @Unique
    private FluidStack ae2oc_cachedFluidOutput = null;
    @Unique
    private double ae2oc_cachedUnitEnergy = 0;
    @Unique
    private Object ae2oc_cachedRecipe = null;

    /**
     * HEAD 注入
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_headTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;

        // 重入保护
        if (this.ae2oc_processing) {
            return;
        }

        boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(self);
        int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);

        if (!hasOverclock && parallelMultiplier <= 1) {
            return;
        }

        try {
            if (hasOverclock) {
                // ===== 超频模式（可带并行）：瞬间完成 =====
                this.ae2oc_processing = true;
                try {
                    ae2oc_instantCraft(self, node, parallelMultiplier);
                } finally {
                    this.ae2oc_processing = false;
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }

            // ===== 仅并行模式：缓存状态 =====
            Field ptField = ae2oc_getField(self.getClass(), "processingTime");
            ptField.setAccessible(true);
            this.ae2oc_prevProcessingTime = ptField.getInt(self);

            // 获取配方
            Method getTask = self.getClass().getDeclaredMethod("getTask");
            getTask.setAccessible(true);
            Object recipe = getTask.invoke(self);

            if (recipe != null) {
                // 缓存配方信息
                Method getEnergy = recipe.getClass().getMethod("getEnergy");
                this.ae2oc_cachedUnitEnergy = (int) getEnergy.invoke(recipe);

                Method isItemOutput = recipe.getClass().getMethod("isItemOutput");
                this.ae2oc_cachedIsItemOutput = (boolean) isItemOutput.invoke(recipe);

                if (this.ae2oc_cachedIsItemOutput) {
                    Method getResultItem = recipe.getClass().getMethod("getResultItem");
                    this.ae2oc_cachedItemOutput = ((ItemStack) getResultItem.invoke(recipe)).copy();
                } else {
                    Method getResultFluid = recipe.getClass().getMethod("getResultFluid");
                    this.ae2oc_cachedFluidOutput = ((FluidStack) getResultFluid.invoke(recipe)).copy();
                }

                // 计算并行数
                this.ae2oc_cachedRecipe = recipe;
                this.ae2oc_pendingParallel = ae2oc_calculateParallel(
                        self, node, recipe, parallelMultiplier);
            } else {
                this.ae2oc_pendingParallel = 0;
            }

            // 不取消 → 让原版运行

        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * RETURN 注入：检测配方完成后追加 (P-1) 倍产出
     */
    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_tailTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_prevProcessingTime <= 0) {
            ae2oc_resetCache();
            return;
        }

        try {
            Object self = this;

            Field ptField = ae2oc_getField(self.getClass(), "processingTime");
            ptField.setAccessible(true);
            int currentPT = ptField.getInt(self);

            if (currentPT == 0) {
                // 配方刚完成！追加 (P-1) 次
                int extraRounds = this.ae2oc_pendingParallel - 1;
                this.ae2oc_processing = true;
                try {
                    ae2oc_doExtraOutputs(self, node, extraRounds);
                } finally {
                    this.ae2oc_processing = false;
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        ae2oc_resetCache();
    }

    @Unique
    private void ae2oc_resetCache() {
        this.ae2oc_pendingParallel = 0;
        this.ae2oc_prevProcessingTime = -1;
        this.ae2oc_cachedItemOutput = ItemStack.EMPTY;
        this.ae2oc_cachedFluidOutput = null;
        this.ae2oc_cachedUnitEnergy = 0;
        this.ae2oc_cachedRecipe = null;
    }

    /**
     * 超频模式瞬间完成
     */
    @Unique
    private void ae2oc_instantCraft(Object self, IGridNode node, int parallelMultiplier) throws Exception {
        // 处理 dirty
        ae2oc_handleDirty(self);

        Method getTask = self.getClass().getDeclaredMethod("getTask");
        getTask.setAccessible(true);
        Object recipe = getTask.invoke(self);
        if (recipe == null) return;

        Method getEnergy = recipe.getClass().getMethod("getEnergy");
        double unitEnergy = (int) getEnergy.invoke(recipe);

        Field outputInvField = ae2oc_getField(self.getClass(), "outputInv");
        outputInvField.setAccessible(true);
        Object outputInv = outputInvField.get(self);

        Field inputInvField = ae2oc_getField(self.getClass(), "inputInv");
        inputInvField.setAccessible(true);
        Object inputInv = inputInvField.get(self);

        Field fluidInvField = ae2oc_getField(self.getClass(), "fluidInv");
        fluidInvField.setAccessible(true);
        Object fluidInv = fluidInvField.get(self);

        Method isItemOutput = recipe.getClass().getMethod("isItemOutput");
        boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

        int minInputCount = ae2oc_getMinInputCount(recipe, inputInv, fluidInv);
        double availableEnergy = ae2oc_getAvailableEnergy(self, node);

        int actualParallel;

        if (itemOutput) {
            Method getResultItem = recipe.getClass().getMethod("getResultItem");
            ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();

            Method insertItem = outputInv.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    parallelMultiplier, minInputCount, 1, outputItem,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertItem.invoke(outputInv, 0, stack, simulate);
                        } catch (Exception e) {
                            return stack;
                        }
                    },
                    availableEnergy, unitEnergy
            );
            actualParallel = result.actualParallel();
            if (actualParallel < 1) return;

            double totalEnergy = actualParallel * unitEnergy;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) return;

            for (int i = 0; i < actualParallel; i++) {
                ae2oc_consumeOnceWithRecipe(recipe, inputInv, fluidInv);
            }

            ItemStack outputStack = outputItem.copy();
            outputStack.setCount(outputStack.getCount() * actualParallel);
            insertItem.invoke(outputInv, 0, outputStack, false);
        } else {
            Method getResultFluid = recipe.getClass().getMethod("getResultFluid");
            FluidStack outputFluid = (FluidStack) getResultFluid.invoke(recipe);

            int fluidOutputLimit = ae2oc_getFluidOutputLimit(fluidInv, outputFluid, parallelMultiplier);

            ParallelEngine.ParallelResult result = ParallelEngine.calculateSimple(
                    parallelMultiplier, minInputCount, 1,
                    fluidOutputLimit, availableEnergy, unitEnergy
            );
            actualParallel = result.actualParallel();
            if (actualParallel < 1) return;

            double totalEnergy = actualParallel * unitEnergy;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) return;

            for (int i = 0; i < actualParallel; i++) {
                ae2oc_consumeOnceWithRecipe(recipe, inputInv, fluidInv);
            }

            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            int totalFluidAmount = outputFluid.getAmount() * actualParallel;
            Method addMethod = fluidInv.getClass().getMethod("add", int.class, AEFluidKey.class, int.class);
            addMethod.invoke(fluidInv, 0, fluidKey, totalFluidAmount);
        }

        // 重置
        Field ptField = ae2oc_getField(self.getClass(), "processingTime");
        ptField.setAccessible(true);
        ptField.setInt(self, 0);

        Field cachedTaskField = ae2oc_getField(self.getClass(), "cachedTask");
        cachedTaskField.setAccessible(true);
        cachedTaskField.set(self, null);

        Method setWorking = self.getClass().getMethod("setWorking", boolean.class);
        setWorking.invoke(self, false);

        ae2oc_markForUpdate(self);
        ae2oc_saveChanges(self);
    }

    /**
     * 追加 (P-1) 倍额外产出
     */
    @Unique
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds) {
        if (extraRounds <= 0) return;

        try {
            Field inputInvField = ae2oc_getField(self.getClass(), "inputInv");
            inputInvField.setAccessible(true);
            Object inputInv = inputInvField.get(self);

            Field fluidInvField = ae2oc_getField(self.getClass(), "fluidInv");
            fluidInvField.setAccessible(true);
            Object fluidInv = fluidInvField.get(self);

            // 消耗额外能量
            double extraEnergy = extraRounds * this.ae2oc_cachedUnitEnergy;
            double available = ae2oc_getAvailableEnergy(self, node);
            if (this.ae2oc_cachedUnitEnergy > 0.001) {
                int affordableRounds = (int) (available / this.ae2oc_cachedUnitEnergy);
                extraRounds = Math.min(extraRounds, affordableRounds);
            }
            if (extraRounds <= 0) return;

            extraEnergy = extraRounds * this.ae2oc_cachedUnitEnergy;
            if (!ae2oc_tryConsumePower(self, node, extraEnergy)) return;

            if (this.ae2oc_cachedIsItemOutput) {
                // 物品产出
                Field outputInvField = ae2oc_getField(self.getClass(), "outputInv");
                outputInvField.setAccessible(true);
                Object outputInv = outputInvField.get(self);

                Method insertItem = outputInv.getClass().getMethod("insertItem",
                        int.class, ItemStack.class, boolean.class);

                // 逐轮执行
                int actualExtra = 0;
                for (int i = 0; i < extraRounds; i++) {
                    ItemStack singleOutput = this.ae2oc_cachedItemOutput.copy();
                    ItemStack remaining = (ItemStack) insertItem.invoke(outputInv, 0, singleOutput, true);
                    if (!remaining.isEmpty()) break;

                    ae2oc_consumeOnceWithRecipe(this.ae2oc_cachedRecipe, inputInv, fluidInv);
                    insertItem.invoke(outputInv, 0, singleOutput, false);
                    actualExtra++;
                }
            } else {
                // 流体产出
                AEFluidKey fluidKey = AEFluidKey.of(this.ae2oc_cachedFluidOutput);
                Method addMethod = fluidInv.getClass().getMethod("add", int.class, AEFluidKey.class, int.class);

                for (int i = 0; i < extraRounds; i++) {
                    Method canAdd = fluidInv.getClass().getMethod("canAdd", int.class, AEFluidKey.class, int.class);
                    if (!(boolean) canAdd.invoke(fluidInv, 0, fluidKey, this.ae2oc_cachedFluidOutput.getAmount())) {
                        break;
                    }
                    ae2oc_consumeOnceWithRecipe(this.ae2oc_cachedRecipe, inputInv, fluidInv);
                    addMethod.invoke(fluidInv, 0, fluidKey, this.ae2oc_cachedFluidOutput.getAmount());
                }
            }

            ae2oc_saveChanges(self);

        } catch (Exception e) {
            // 忽略
        }
    }

    // ===== 共享工具方法 =====

    @Unique
    private void ae2oc_handleDirty(Object self) {
        try {
            Field dirtyField = ae2oc_getField(self.getClass(), "dirty");
            dirtyField.setAccessible(true);
            boolean dirty = dirtyField.getBoolean(self);

            if (dirty) {
                Field levelField = ae2oc_getField(self.getClass(), "level");
                levelField.setAccessible(true);
                Object level = levelField.get(self);

                if (level != null) {
                    Method findRecipe = self.getClass().getDeclaredMethod("findRecipe",
                            net.minecraft.world.level.Level.class);
                    findRecipe.setAccessible(true);
                    Object recipe = findRecipe.invoke(self, level);
                    if (recipe == null) {
                        Field ptField = ae2oc_getField(self.getClass(), "processingTime");
                        ptField.setAccessible(true);
                        ptField.setInt(self, 0);

                        Method setWorking = self.getClass().getMethod("setWorking", boolean.class);
                        setWorking.invoke(self, false);

                        Field cachedTaskField = ae2oc_getField(self.getClass(), "cachedTask");
                        cachedTaskField.setAccessible(true);
                        cachedTaskField.set(self, null);
                    }
                }
                ae2oc_markForUpdate(self);
                dirtyField.setBoolean(self, false);
            }
        } catch (Exception ignored) {
        }
    }

    @Unique
    private int ae2oc_calculateParallel(Object self, IGridNode node, Object recipe, int cardMultiplier) {
        try {
            Field inputInvField = ae2oc_getField(self.getClass(), "inputInv");
            inputInvField.setAccessible(true);
            Object inputInv = inputInvField.get(self);

            Field fluidInvField = ae2oc_getField(self.getClass(), "fluidInv");
            fluidInvField.setAccessible(true);
            Object fluidInv = fluidInvField.get(self);

            int minInputCount = ae2oc_getMinInputCount(recipe, inputInv, fluidInv);
            double availableEnergy = ae2oc_getAvailableEnergy(self, node);

            Method getEnergy = recipe.getClass().getMethod("getEnergy");
            double unitEnergy = (int) getEnergy.invoke(recipe);

            Method isItemOutput = recipe.getClass().getMethod("isItemOutput");
            boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

            if (itemOutput) {
                Field outputInvField = ae2oc_getField(self.getClass(), "outputInv");
                outputInvField.setAccessible(true);
                Object outputInv = outputInvField.get(self);

                Method getResultItem = recipe.getClass().getMethod("getResultItem");
                ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();

                Method insertItem = outputInv.getClass().getMethod("insertItem",
                        int.class, ItemStack.class, boolean.class);

                return ParallelEngine.calculate(
                        cardMultiplier, minInputCount, 1, outputItem,
                        (stack, simulate) -> {
                            try {
                                return (ItemStack) insertItem.invoke(outputInv, 0, stack, simulate);
                            } catch (Exception e) {
                                return stack;
                            }
                        },
                        availableEnergy, unitEnergy
                ).actualParallel();
            } else {
                Method getResultFluid = recipe.getClass().getMethod("getResultFluid");
                FluidStack outputFluid = (FluidStack) getResultFluid.invoke(recipe);
                int fluidOutputLimit = ae2oc_getFluidOutputLimit(fluidInv, outputFluid, cardMultiplier);

                return ParallelEngine.calculateSimple(
                        cardMultiplier, minInputCount, 1,
                        fluidOutputLimit, availableEnergy, unitEnergy
                ).actualParallel();
            }
        } catch (Exception e) {
            return 1;
        }
    }

    @Unique
    private int ae2oc_getMinInputCount(Object recipe, Object inputInv, Object fluidInv) {
        int minCount = Integer.MAX_VALUE;
        try {
            Method getValidInputs = recipe.getClass().getMethod("getValidInputs");
            @SuppressWarnings("unchecked")
            java.util.List<Object> validInputs = (java.util.List<Object>) getValidInputs.invoke(recipe);

            Method getSize = inputInv.getClass().getMethod("size");
            int invSize = (int) getSize.invoke(inputInv);

            for (Object input : validInputs) {
                Method getAmount = input.getClass().getMethod("getAmount");
                int requiredAmount = (int) getAmount.invoke(input);
                if (requiredAmount <= 0) requiredAmount = 1;

                int available = 0;

                for (int x = 0; x < invSize; x++) {
                    Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, x);
                    if (!stack.isEmpty()) {
                        Method checkType = input.getClass().getMethod("checkType", Object.class);
                        if ((boolean) checkType.invoke(input, stack)) {
                            available += stack.getCount();
                        }
                    }
                }

                // 流体输入 (slot 1)
                try {
                    Method getStack = fluidInv.getClass().getMethod("getStack", int.class);
                    Object gs = getStack.invoke(fluidInv, 1);
                    if (gs != null) {
                        Field whatField = gs.getClass().getDeclaredField("what");
                        whatField.setAccessible(true);
                        Object aeKey = whatField.get(gs);

                        Field amountField = gs.getClass().getDeclaredField("amount");
                        amountField.setAccessible(true);
                        long amount = amountField.getLong(gs);

                        if (aeKey instanceof AEFluidKey key) {
                            FluidStack fs = key.toStack((int) amount);
                            Method checkType = input.getClass().getMethod("checkType", Object.class);
                            if ((boolean) checkType.invoke(input, fs)) {
                                available += (int) amount / requiredAmount * requiredAmount;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                minCount = Math.min(minCount, available / requiredAmount);
            }
        } catch (Exception e) {
            return 1;
        }
        return minCount == Integer.MAX_VALUE ? 1 : minCount;
    }

    @Unique
    private int ae2oc_getFluidOutputLimit(Object fluidInv, FluidStack outputFluid, int maxParallel) {
        try {
            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            Method canAdd = fluidInv.getClass().getMethod("canAdd", int.class, AEFluidKey.class, int.class);

            int lo = 0, hi = maxParallel;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                int testAmount = outputFluid.getAmount() * mid;
                if ((boolean) canAdd.invoke(fluidInv, 0, fluidKey, testAmount)) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        } catch (Exception e) {
            return 0;
        }
    }



    /**
     * 消耗单份输入 — 完整版（需要 recipe 引用）
     */
    @Unique
    private void ae2oc_consumeOnceWithRecipe(Object recipe, Object inputInv, Object fluidInv) {
        try {
            Method getValidInputs = recipe.getClass().getMethod("getValidInputs");
            @SuppressWarnings("unchecked")
            java.util.List<Object> validInputs = (java.util.List<Object>) getValidInputs.invoke(recipe);

            Method getStack = fluidInv.getClass().getMethod("getStack", int.class);
            Object gs = getStack.invoke(fluidInv, 1);
            FluidStack fluidStack = null;
            if (gs != null) {
                Field whatField = gs.getClass().getDeclaredField("what");
                whatField.setAccessible(true);
                Object aeKey = whatField.get(gs);
                if (aeKey instanceof AEFluidKey key) {
                    Field amountField = gs.getClass().getDeclaredField("amount");
                    amountField.setAccessible(true);
                    long amount = amountField.getLong(gs);
                    fluidStack = key.toStack((int) amount);
                }
            }

            Method getSize = inputInv.getClass().getMethod("size");
            int invSize = (int) getSize.invoke(inputInv);

            for (Object input : validInputs) {
                for (int x = 0; x < invSize; x++) {
                    Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, x);

                    Method checkType = input.getClass().getMethod("checkType", Object.class);
                    if ((boolean) checkType.invoke(input, stack)) {
                        Method consume = input.getClass().getMethod("consume", Object.class);
                        consume.invoke(input, stack);

                        Method setItemDirect = inputInv.getClass().getMethod("setItemDirect",
                                int.class, ItemStack.class);
                        setItemDirect.invoke(inputInv, x, stack);
                    }

                    Method isEmpty = input.getClass().getMethod("isEmpty");
                    if ((boolean) isEmpty.invoke(input)) break;
                }

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
        } catch (Exception ignored) {
        }
    }

    @Unique
    private double ae2oc_getAvailableEnergy(Object self, IGridNode node) {
        double total = 0;
        try {
            Method extractMethod = self.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            total += (double) extractMethod.invoke(self, Double.MAX_VALUE,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);

            var grid = node.getGrid();
            if (grid != null) {
                IEnergyService energyService = grid.getEnergyService();
                total += energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }
        } catch (Exception e) {
            // 忽略
        }
        return total;
    }

    @Unique
    private boolean ae2oc_tryConsumePower(Object self, IGridNode node, double powerNeeded) {
        try {
            Method extractMethod = self.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);

            double extracted = (double) extractMethod.invoke(self, powerNeeded,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (extracted >= powerNeeded - 0.01) {
                extractMethod.invoke(self, powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }

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

    @Unique
    private void ae2oc_markForUpdate(Object self) {
        try {
            Method method = self.getClass().getMethod("markForUpdate");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_saveChanges(Object self) {
        try {
            Method method = self.getClass().getMethod("saveChanges");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }
}
