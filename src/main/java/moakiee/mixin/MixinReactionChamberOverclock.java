package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
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

            // 批量消耗材料（替代循环调用单份消耗）
            ae2oc_consumeBatchWithRecipe(recipe, inputInv, fluidInv, actualParallel);

            // 先放入本地输出槽
            ItemStack outputStack = outputItem.copy();
            int totalOutput = outputStack.getCount() * actualParallel;
            outputStack.setCount(totalOutput);
            insertItem.invoke(outputInv, 0, outputStack, false);
            
            // 如果有并行卡，把本地输出槽的产物转移到 ME 网络
            if (parallelMultiplier > 1) {
                ae2oc_transferItemOutputToNetwork(node, outputInv);
            }
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

            // 批量消耗材料（替代循环调用单份消耗）
            ae2oc_consumeBatchWithRecipe(recipe, inputInv, fluidInv, actualParallel);

            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            int totalFluidAmount = outputFluid.getAmount() * actualParallel;
            
            // 先放入本地流体槽
            Method addMethod = fluidInv.getClass().getMethod("add", int.class, AEFluidKey.class, int.class);
            addMethod.invoke(fluidInv, 0, fluidKey, totalFluidAmount);
            
            // 如果有并行卡，把本地槽的流体转移到 ME 网络
            if (parallelMultiplier > 1) {
                ae2oc_transferFluidOutputToNetwork(node, fluidInv);
            }
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
                // 物品产出 - 批量处理
                Field outputInvField = ae2oc_getField(self.getClass(), "outputInv");
                outputInvField.setAccessible(true);
                Object outputInv = outputInvField.get(self);

                Method insertItem = outputInv.getClass().getMethod("insertItem",
                        int.class, ItemStack.class, boolean.class);

                // 计算总产出
                ItemStack totalOutput = this.ae2oc_cachedItemOutput.copy();
                totalOutput.setCount(totalOutput.getCount() * extraRounds);
                
                // 先放入本地槽
                ItemStack leftover = (ItemStack) insertItem.invoke(outputInv, 0, totalOutput, false);
                int actualInserted = totalOutput.getCount() - leftover.getCount();
                
                // 计算实际完成的轮数
                int singleOutputCount = this.ae2oc_cachedItemOutput.getCount();
                int actualExtra = singleOutputCount > 0 ? actualInserted / singleOutputCount : 0;
                
                // 批量消耗材料
                if (actualExtra > 0) {
                    ae2oc_consumeBatchWithRecipe(this.ae2oc_cachedRecipe, inputInv, fluidInv, actualExtra);
                }
                
                // 如果有并行卡，把本地槽的产物转移到 ME 网络
                int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
                if (parallelMultiplier > 1) {
                    ae2oc_transferItemOutputToNetwork(node, outputInv);
                }
            } else {
                // 流体产出 - 批量处理
                AEFluidKey fluidKey = AEFluidKey.of(this.ae2oc_cachedFluidOutput);
                Method addMethod = fluidInv.getClass().getMethod("add", int.class, AEFluidKey.class, int.class);

                // 计算总流体量
                int singleFluidAmount = this.ae2oc_cachedFluidOutput.getAmount();
                int totalFluidAmount = singleFluidAmount * extraRounds;
                
                // 先放入本地槽
                Method canAdd = fluidInv.getClass().getMethod("canAdd", int.class, AEFluidKey.class, int.class);
                int actualInserted = 0;
                if ((boolean) canAdd.invoke(fluidInv, 0, fluidKey, totalFluidAmount)) {
                    addMethod.invoke(fluidInv, 0, fluidKey, totalFluidAmount);
                    actualInserted = totalFluidAmount;
                }
                
                // 计算实际完成的轮数
                int actualExtra = singleFluidAmount > 0 ? actualInserted / singleFluidAmount : 0;
                
                // 批量消耗材料
                if (actualExtra > 0) {
                    ae2oc_consumeBatchWithRecipe(this.ae2oc_cachedRecipe, inputInv, fluidInv, actualExtra);
                }
                
                // 如果有并行卡，把本地槽的流体转移到 ME 网络
                int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
                if (parallelMultiplier > 1) {
                    ae2oc_transferFluidOutputToNetwork(node, fluidInv);
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
    private static final int AE2OC_MAX_PARALLEL_LIMIT = 1_000_000_000; // 避免整数溢出
    
    @Unique
    private int ae2oc_getFluidOutputLimit(Object fluidInv, FluidStack outputFluid, int maxParallel) {
        try {
            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            Method canAdd = fluidInv.getClass().getMethod("canAdd", int.class, AEFluidKey.class, int.class);

            // 限制并行数防止整数溢出
            int safeMaxParallel = Math.min(maxParallel, AE2OC_MAX_PARALLEL_LIMIT);
            
            int lo = 0, hi = safeMaxParallel;
            while (lo < hi) {
                // 使用安全的中点计算方式避免溢出
                int mid = lo + (hi - lo + 1) / 2;
                // 使用 long 计算避免溢出
                long testAmount = (long) outputFluid.getAmount() * mid;
                if (testAmount > Integer.MAX_VALUE) {
                    hi = mid - 1;
                    continue;
                }
                if ((boolean) canAdd.invoke(fluidInv, 0, fluidKey, (int) testAmount)) {
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

    /**
     * 批量消耗输入材料（替代循环调用单份消耗）
     * 一次性扣除 batchCount 份材料，避免大量循环导致游戏卡死
     */
    @Unique
    private void ae2oc_consumeBatchWithRecipe(Object recipe, Object inputInv, Object fluidInv, int batchCount) {
        if (batchCount <= 0) return;
        
        try {
            Method getValidInputs = recipe.getClass().getMethod("getValidInputs");
            @SuppressWarnings("unchecked")
            java.util.List<Object> validInputs = (java.util.List<Object>) getValidInputs.invoke(recipe);

            // 获取流体槽内容
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

            // 处理每种输入要求
            for (Object input : validInputs) {
                // 获取单份消耗量
                Method getAmount = input.getClass().getMethod("getAmount");
                int singleConsumeAmount = (int) getAmount.invoke(input);
                if (singleConsumeAmount <= 0) singleConsumeAmount = 1;
                
                // 计算总消耗量
                int totalConsumeAmount = singleConsumeAmount * batchCount;
                int remainingToConsume = totalConsumeAmount;
                
                // 从物品槽消耗
                for (int x = 0; x < invSize && remainingToConsume > 0; x++) {
                    Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, x);
                    
                    if (stack.isEmpty()) continue;

                    Method checkType = input.getClass().getMethod("checkType", Object.class);
                    if ((boolean) checkType.invoke(input, stack)) {
                        int consumeFromSlot = Math.min(stack.getCount(), remainingToConsume);
                        stack.shrink(consumeFromSlot);
                        remainingToConsume -= consumeFromSlot;

                        Method setItemDirect = inputInv.getClass().getMethod("setItemDirect",
                                int.class, ItemStack.class);
                        setItemDirect.invoke(inputInv, x, stack);
                    }
                }
                
                // 从流体槽消耗（如果还有剩余）
                if (fluidStack != null && remainingToConsume > 0) {
                    Method checkType = input.getClass().getMethod("checkType", Object.class);
                    if ((boolean) checkType.invoke(input, fluidStack)) {
                        int consumeFromFluid = Math.min(fluidStack.getAmount(), remainingToConsume);
                        fluidStack.shrink(consumeFromFluid);
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
    
    /**
     * 把本地物品输出槽的产物转移到 ME 网络
     */
    @Unique
    private void ae2oc_transferItemOutputToNetwork(IGridNode node, Object outputInv) {
        try {
            var grid = node.getGrid();
            if (grid == null) return;
            
            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;
            
            Method getStackInSlot = outputInv.getClass().getMethod("getStackInSlot", int.class);
            Method setItemDirect = outputInv.getClass().getMethod("setItemDirect", int.class, ItemStack.class);
            
            // 检查输出槽 0
            ItemStack stack = (ItemStack) getStackInSlot.invoke(outputInv, 0);
            if (stack.isEmpty()) return;
            
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return;
            
            long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, null);
            
            if (inserted >= stack.getCount()) {
                // 全部插入成功，清空本地槽
                setItemDirect.invoke(outputInv, 0, ItemStack.EMPTY);
            } else if (inserted > 0) {
                // 部分插入，减少本地槽数量
                stack.shrink((int) inserted);
                setItemDirect.invoke(outputInv, 0, stack);
            }
            
        } catch (Exception e) {
            // 忽略
        }
    }
    
    /**
     * 把本地流体输出槽的流体转移到 ME 网络
     */
    @Unique
    private void ae2oc_transferFluidOutputToNetwork(IGridNode node, Object fluidInv) {
        try {
            var grid = node.getGrid();
            if (grid == null) return;
            
            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;
            
            Method getStack = fluidInv.getClass().getMethod("getStack", int.class);
            Method setStack = fluidInv.getClass().getMethod("setStack", int.class, GenericStack.class);
            
            // 检查流体输出槽 0
            Object gs = getStack.invoke(fluidInv, 0);
            if (gs == null) return;
            
            Field whatField = gs.getClass().getDeclaredField("what");
            whatField.setAccessible(true);
            Object aeKey = whatField.get(gs);
            
            Field amountField = gs.getClass().getDeclaredField("amount");
            amountField.setAccessible(true);
            long amount = amountField.getLong(gs);
            
            if (!(aeKey instanceof AEFluidKey fluidKey) || amount <= 0) return;
            
            long inserted = storageService.getInventory().insert(fluidKey, amount, Actionable.MODULATE, null);
            
            if (inserted >= amount) {
                // 全部插入成功，清空本地槽
                setStack.invoke(fluidInv, 0, null);
            } else if (inserted > 0) {
                // 部分插入，减少本地槽数量
                setStack.invoke(fluidInv, 0, new GenericStack(fluidKey, amount - inserted));
            }
            
        } catch (Exception e) {
            // 忽略
        }
    }
}
