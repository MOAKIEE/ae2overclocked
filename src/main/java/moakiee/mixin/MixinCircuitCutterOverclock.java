package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import moakiee.support.OverclockCardRuntime;
import moakiee.support.ParallelCardRuntime;
import moakiee.support.ParallelEngine;
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
 * 并行卡 + 超频卡 功能注入 — ExtendedAE 电路切片器
 *
 * 行为定义：
 * - 仅超频卡：瞬间完成，单倍产出
 * - 仅并行卡：正常进度条速度，完成时多倍产出
 * - 两者兼有：瞬间完成 + 多倍产出
 *
 * 实现策略（仅并行模式）：
 * - HEAD 注入：缓存当前进度和配方信息，不取消原版
 * - RETURN 注入：检测配方是否刚刚完成（progress 从非零变为零），追加 (P-1) 倍额外产出
 */
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter", remap = false)
public abstract class MixinCircuitCutterOverclock {

    @Unique
    private static final double AE2OC_CUTTER_RECIPE_ENERGY = 2000.0;

    // ===== 跨 HEAD/RETURN 缓存 =====

    /** 重入保护标志，防止 saveChanges 触发的 wakeDevice 导致递归 tick */
    @Unique
    private boolean ae2oc_processing = false;

    /** 上一次进入 HEAD 时记录的 progress */
    @Unique
    private int ae2oc_prevProgress = -1;

    /** 待执行的并行数（含原版已执行的 1 次） */
    @Unique
    private int ae2oc_pendingParallel = 0;

    /** 缓存的配方引用（原版完成后会清除 currentRecipe，所以需要提前缓存） */
    @Unique
    private Object ae2oc_cachedRecipe = null;

    /** 缓存的配方单份产物 */
    @Unique
    private ItemStack ae2oc_cachedOutput = ItemStack.EMPTY;

    /**
     * HEAD 注入：
     * - 有超频卡 → 取消原版，瞬间完成（可选带并行）
     * - 仅并行卡 → 缓存状态，不取消，让原版推进度条
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

        // 无卡 → 走原版
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

            // ===== 仅并行模式：缓存状态，让原版跑进度条 =====

            // 记录当前 progress
            Field progressField = ae2oc_getField(self.getClass(), "progress");
            progressField.setAccessible(true);
            this.ae2oc_prevProgress = progressField.getInt(self);

            // 获取配方并缓存
            Field ctxField = ae2oc_getField(self.getClass(), "ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(self);

            Field recipeField = ae2oc_getField(ctx.getClass(), "currentRecipe");
            recipeField.setAccessible(true);
            Object recipe = recipeField.get(ctx);

            if (recipe != null) {
                this.ae2oc_cachedRecipe = recipe;
                Field outputFieldInRecipe = ae2oc_getField(recipe.getClass(), "output");
                outputFieldInRecipe.setAccessible(true);
                this.ae2oc_cachedOutput = ((ItemStack) outputFieldInRecipe.get(recipe)).copy();

                // 计算并行数
                this.ae2oc_pendingParallel = ae2oc_calculateParallel(self, node, recipe, parallelMultiplier);
            } else {
                this.ae2oc_pendingParallel = 0;
            }

            // 不取消 → 让原版 exec.execute() 正常运行

        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * RETURN 注入：
     * 检测原版是否刚刚完成了配方（progress 从非零变为零），
     * 如果完成了，追加 (P-1) 倍产出和材料消耗
     */
    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_tailTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        // 检查是否有需要处理的并行
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_prevProgress <= 0) {
            ae2oc_resetCache();
            return;
        }

        try {
            Object self = this;

            // 检查 progress 是否被重置为 0（表示配方刚完成）
            Field progressField = ae2oc_getField(self.getClass(), "progress");
            progressField.setAccessible(true);
            int currentProgress = progressField.getInt(self);

            if (currentProgress == 0 && this.ae2oc_cachedRecipe != null) {
                // 配方刚刚完成！原版已执行 1 次，补充 (P-1) 次
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
        this.ae2oc_prevProgress = -1;
        this.ae2oc_cachedRecipe = null;
        this.ae2oc_cachedOutput = ItemStack.EMPTY;
    }

    /**
     * 超频模式瞬间完成
     */
    @Unique
    private void ae2oc_instantCraft(Object self, IGridNode node, int parallelMultiplier) throws Exception {
        // 获取 ctx 和配方
        Field ctxField = ae2oc_getField(self.getClass(), "ctx");
        ctxField.setAccessible(true);
        Object ctx = ctxField.get(self);

        Field recipeField = ae2oc_getField(ctx.getClass(), "currentRecipe");
        recipeField.setAccessible(true);
        Object currentRecipe = recipeField.get(ctx);

        // 无配方则尝试查找
        if (currentRecipe == null) {
            Method shouldTick = ctx.getClass().getMethod("shouldTick");
            if ((boolean) shouldTick.invoke(ctx)) {
                Method findRecipe = ae2oc_getMethod(ctx.getClass(), "findRecipe");
                findRecipe.setAccessible(true);
                findRecipe.invoke(ctx);
                currentRecipe = recipeField.get(ctx);
            }
        }

        if (currentRecipe == null) return;

        Method testRecipe = ctx.getClass().getMethod("testRecipe", Recipe.class);
        if (!(boolean) testRecipe.invoke(ctx, currentRecipe)) return;

        // 获取材料信息
        Field inputField = ae2oc_getField(self.getClass(), "input");
        inputField.setAccessible(true);
        Object inputInv = inputField.get(self);

        Field outputField = ae2oc_getField(self.getClass(), "output");
        outputField.setAccessible(true);
        Object outputInv = outputField.get(self);

        Field recipeOutputField = ae2oc_getField(currentRecipe.getClass(), "output");
        recipeOutputField.setAccessible(true);
        ItemStack recipeOutput = ((ItemStack) recipeOutputField.get(currentRecipe)).copy();

        Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
        ItemStack inputStack = (ItemStack) getStackInSlot.invoke(inputInv, 0);
        int inputCount = inputStack.getCount();

        Method insertItem = outputInv.getClass().getMethod("insertItem",
                int.class, ItemStack.class, boolean.class);

        double availableEnergy = ae2oc_getAvailableEnergy(self, node);

        // 计算实际并行数
        // 当有并行卡时，产物优先输出到ME网络，不受本地槽限制
        ParallelEngine.ParallelResult result;
        if (parallelMultiplier > 1) {
            result = ParallelEngine.calculateSimple(
                    parallelMultiplier, inputCount, 1,
                    Integer.MAX_VALUE, // 输出空间不限制，因为会输出到ME网络
                    availableEnergy, AE2OC_CUTTER_RECIPE_ENERGY
            );
        } else {
            result = ParallelEngine.calculate(
                    parallelMultiplier, inputCount, 1, recipeOutput,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertItem.invoke(outputInv, 0, stack, simulate);
                        } catch (Exception e) {
                            return stack;
                        }
                    },
                    availableEnergy, AE2OC_CUTTER_RECIPE_ENERGY
            );
        }

        int actualParallel = result.actualParallel();
        if (actualParallel < 1) return;

        double totalEnergy = actualParallel * AE2OC_CUTTER_RECIPE_ENERGY;
        if (!ae2oc_tryConsumePower(self, node, totalEnergy)) return;

        // 原子化结算 - runRecipe 会消耗材料并产出到本地槽
        Method runRecipe = ctx.getClass().getMethod("runRecipe", Recipe.class);
        for (int i = 0; i < actualParallel; i++) {
            runRecipe.invoke(ctx, currentRecipe);
        }

        // 如果有并行卡，把本地输出槽的产物转移到 ME 网络
        if (parallelMultiplier > 1) {
            ae2oc_transferOutputToNetwork(node, outputInv);
        }

        Field progressField = ae2oc_getField(self.getClass(), "progress");
        progressField.setAccessible(true);
        progressField.setInt(self, 0);
        recipeField.set(ctx, null);

        Method setWorking = self.getClass().getMethod("setWorking", boolean.class);
        setWorking.invoke(self, false);

        ae2oc_markForUpdate(self);
        ae2oc_saveChanges(self);
    }

    /**
     * 计算并行数（用于仅并行模式 HEAD 阶段）
     * 当有并行卡时，产物会优先输出到ME网络，所以输出空间不受本地槽限制
     */
    @Unique
    private int ae2oc_calculateParallel(Object self, IGridNode node, Object recipe, int cardMultiplier) {
        try {
            Field inputField = ae2oc_getField(self.getClass(), "input");
            inputField.setAccessible(true);
            Object inputInv = inputField.get(self);

            Field outputField = ae2oc_getField(self.getClass(), "output");
            outputField.setAccessible(true);
            Object outputInv = outputField.get(self);

            Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
            ItemStack inputStack = (ItemStack) getStackInSlot.invoke(inputInv, 0);
            int inputCount = inputStack.getCount();

            Field recipeOutputField = ae2oc_getField(recipe.getClass(), "output");
            recipeOutputField.setAccessible(true);
            ItemStack recipeOutput = ((ItemStack) recipeOutputField.get(recipe)).copy();

            double availableEnergy = ae2oc_getAvailableEnergy(self, node);

            // 当有并行卡(cardMultiplier > 1)时，产物优先输出到ME网络，使用简化计算（不受本地槽限制）
            if (cardMultiplier > 1) {
                return ParallelEngine.calculateSimple(
                        cardMultiplier, inputCount, 1,
                        Integer.MAX_VALUE, // 输出空间不限制，因为会输出到ME网络
                        availableEnergy, AE2OC_CUTTER_RECIPE_ENERGY
                ).actualParallel();
            }

            // 无并行卡时使用本地槽限制
            Method insertItem = outputInv.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    cardMultiplier, inputCount, 1, recipeOutput,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertItem.invoke(outputInv, 0, stack, simulate);
                        } catch (Exception e) {
                            return stack;
                        }
                    },
                    availableEnergy, AE2OC_CUTTER_RECIPE_ENERGY
            );
            return result.actualParallel();
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 追加 (P-1) 倍额外产出（原版已处理了 1 倍）
     * 
     * 如果安装了并行卡，产物优先输出到 ME 网络
     */
    @Unique
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds) {
        if (extraRounds <= 0 || this.ae2oc_cachedRecipe == null) return;

        try {
            Field ctxField = ae2oc_getField(self.getClass(), "ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(self);

            Field outputField = ae2oc_getField(self.getClass(), "output");
            outputField.setAccessible(true);
            Object outputInv = outputField.get(self);

            Method runRecipe = ctx.getClass().getMethod("runRecipe", Recipe.class);
            Method testRecipe = ctx.getClass().getMethod("testRecipe", Recipe.class);

            // 消耗额外能量
            double extraEnergy = extraRounds * AE2OC_CUTTER_RECIPE_ENERGY;
            if (!ae2oc_tryConsumePower(self, node, extraEnergy)) {
                // 能量不够全部额外轮次，尽可能做几轮
                double available = ae2oc_getAvailableEnergy(self, node);
                extraRounds = (int) (available / AE2OC_CUTTER_RECIPE_ENERGY);
                if (extraRounds <= 0) return;
                extraEnergy = extraRounds * AE2OC_CUTTER_RECIPE_ENERGY;
                if (!ae2oc_tryConsumePower(self, node, extraEnergy)) return;
            }

            // 检查是否有并行卡
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
            
            // 逐轮执行（验证材料 → 消耗 + 产出）
            int actualExtra = 0;
            for (int i = 0; i < extraRounds; i++) {
                // 验证材料
                if (!(boolean) testRecipe.invoke(ctx, this.ae2oc_cachedRecipe)) break;
                
                // 执行配方（消耗材料 + 产出到本地槽）
                runRecipe.invoke(ctx, this.ae2oc_cachedRecipe);
                actualExtra++;
            }
            
            // 如果有并行卡，把本地输出槽的产物转移到 ME 网络
            if (parallelMultiplier > 1 && actualExtra > 0) {
                ae2oc_transferOutputToNetwork(node, outputInv);
            }

            if (actualExtra > 0) {
                ae2oc_saveChanges(self);
            }

        } catch (Exception e) {
            // 忽略
        }
    }
    
    /**
     * 把本地输出槽的产物转移到 ME 网络
     */
    @Unique
    private void ae2oc_transferOutputToNetwork(IGridNode node, Object outputInv) {
        try {
            var grid = node.getGrid();
            if (grid == null) return;
            
            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;
            
            Method getStackInSlot = outputInv.getClass().getMethod("getStackInSlot", int.class);
            Method setItemDirect = outputInv.getClass().getMethod("setItemDirect", int.class, ItemStack.class);
            
            // 只检查输出槽 0
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
     * 尝试将产物输出到 ME 网络
     * @return 实际插入的物品数量
     */
    @Unique
    private int ae2oc_tryOutputToNetwork(IGridNode node, ItemStack outputStack) {
        try {
            var grid = node.getGrid();
            if (grid == null) {
                return 0;
            }
            
            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) {
                return 0;
            }
            
            AEItemKey key = AEItemKey.of(outputStack);
            if (key == null) {
                return 0;
            }
            
            long inserted = storageService.getInventory().insert(key, outputStack.getCount(), Actionable.MODULATE, null);
            
            return (int) inserted;
            
        } catch (Exception e) {
            return 0;
        }
    }

    // ===== 工具方法 =====

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
