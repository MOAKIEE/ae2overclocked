package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.support.OverclockCardRuntime;
import moakiee.support.ParallelCardRuntime;
import moakiee.support.ParallelEngine;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 并行卡 + 超频卡 功能注入 — AE2 原版压印器
 *
 * 结算顺序：先定并行量 → 再算总电 → 判超频 → 秒结算
 *
 * 安全机制：
 * - 木桶效应：P_actual = min(卡片倍数, 材料, 模板, 输出空间, 能量)
 * - 原子化执行：扣材料、扣电、出产物在同一方法内完成
 * - 能量不足降级：回退原版速度
 * - PRESS 类型模板约束：模板不够则压低 P_actual
 */
@Mixin(InscriberBlockEntity.class)
public abstract class MixinInscriberOverclock {

    @Unique
    private static final double AE2OC_INSCRIBER_RECIPE_ENERGY = 2000.0;

    /** 缓存并行结算结果，用于 smash 动画完成后的原子化产出 */
    @Unique
    private int ae2oc_pendingParallel = 0;

    // ========== Shadow 访问原版字段和方法 ==========

    @Shadow
    private boolean smash;

    @Shadow
    private int finalStep;

    @Shadow
    public abstract InscriberRecipe getTask();

    @Shadow
    public abstract int getMaxProcessingTime();

    @Shadow
    public abstract int getProcessingTime();

    @Shadow
    protected abstract void setSmash(boolean smash);

    /**
     * 在 tickingRequest 方法头部注入并行+超频联动逻辑
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2oc_parallelOverclockTick(IGridNode node, int ticksSinceLastCall,
                                              CallbackInfoReturnable<TickRateModulation> cir) {
        InscriberBlockEntity self = (InscriberBlockEntity) (Object) this;

        boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(self);
        int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);

        // 没有任何卡 → 走原版
        if (!hasOverclock && parallelMultiplier <= 1) {
            return;
        }

        // 正在播放 smash 动画
        if (this.smash) {
            ae2oc_handleSmashAnimation(self);
            cir.setReturnValue(TickRateModulation.URGENT);
            return;
        }

        // 获取配方
        InscriberRecipe recipe = this.getTask();
        if (recipe == null) {
            return; // 无配方，走原版
        }

        // 计算并行数
        int actualParallel = ae2oc_calculateParallel(self, node, recipe, parallelMultiplier);
        if (actualParallel < 1) {
            // 条件不满足（材料/输出/电量不够执行哪怕 1 次）
            // 如果只有并行卡没有超频卡，回退原版让它慢慢充电/等材料
            return;
        }

        if (hasOverclock) {
            // === 超频模式：一次性扣电，触发 smash 动画 ===
            double totalEnergy = actualParallel * AE2OC_INSCRIBER_RECIPE_ENERGY;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) {
                // 电量不够超频的总消耗，回退原版慢速
                return;
            }

            // 记录待结算并行数
            this.ae2oc_pendingParallel = actualParallel;
            this.setSmash(true);
            this.finalStep = 0;
            ae2oc_markForUpdate(self);
            cir.setReturnValue(TickRateModulation.URGENT);
        } else {
            // === 仅并行模式（无超频卡）：正常推进度条，完工时多倍结算 ===
            ae2oc_normalTickWithParallel(self, node, recipe, actualParallel, cir);
        }
    }

    /**
     * 处理 smash 动画阶段
     */
    @Unique
    private void ae2oc_handleSmashAnimation(InscriberBlockEntity self) {
        this.finalStep += 4; // 超频加速动画
        if (this.finalStep >= 8 && this.finalStep < 16) {
            // 动画到达结算点 → 原子化执行多倍结算
            int parallel = Math.max(this.ae2oc_pendingParallel, 1);
            ae2oc_finishCraftParallel(self, parallel);
            this.ae2oc_pendingParallel = 0;
            this.finalStep = 16;
        }
        if (this.finalStep >= 16) {
            this.finalStep = 0;
            this.setSmash(false);
            ae2oc_markForUpdate(self);
        }
    }

    /**
     * 计算实际并行数（木桶效应）
     */
    @Unique
    private int ae2oc_calculateParallel(InscriberBlockEntity self, IGridNode node,
                                         InscriberRecipe recipe, int cardMultiplier) {
        try {
            java.lang.reflect.Field sideField = InscriberBlockEntity.class.getDeclaredField("sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            // 获取输入物品数量
            java.lang.reflect.Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            ItemStack inputStack = (ItemStack) getStackInSlot.invoke(sideHandler, 0);
            int inputCount = inputStack.getCount();

            // 配方每次消耗 1 个输入（压印器标准）
            int recipeInputCount = 1;

            // PRESS 类型需要检查模板约束
            if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                java.lang.reflect.Field topField = InscriberBlockEntity.class.getDeclaredField("topItemHandler");
                topField.setAccessible(true);
                Object topHandler = topField.get(self);

                java.lang.reflect.Field bottomField = InscriberBlockEntity.class.getDeclaredField("bottomItemHandler");
                bottomField.setAccessible(true);
                Object bottomHandler = bottomField.get(self);

                ItemStack topStack = (ItemStack) getStackInSlot.invoke(topHandler, 0);
                ItemStack bottomStack = (ItemStack) getStackInSlot.invoke(bottomHandler, 0);

                int topCount = topStack.isEmpty() ? Integer.MAX_VALUE : topStack.getCount();
                int bottomCount = bottomStack.isEmpty() ? Integer.MAX_VALUE : bottomStack.getCount();
                int templateLimit = Math.min(topCount, bottomCount);
                cardMultiplier = Math.min(cardMultiplier, templateLimit);
            }

            // 产物
            ItemStack outputStack = recipe.getResultItem().copy();

            // 使用 ParallelEngine 计算
            java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            // 可用能量
            double availableEnergy = ae2oc_getAvailableEnergy(self, node);

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    cardMultiplier,
                    inputCount, recipeInputCount,
                    outputStack,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertMethod.invoke(sideHandler, 1, stack, simulate);
                        } catch (Exception e) {
                            return stack; // 反射失败 → 无法插入
                        }
                    },
                    availableEnergy,
                    AE2OC_INSCRIBER_RECIPE_ENERGY
            );

            return result.actualParallel();

        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 仅并行（无超频卡）模式：正常推进度条
     * 进度完成时执行多倍结算
     */
    @Unique
    private void ae2oc_normalTickWithParallel(InscriberBlockEntity self, IGridNode node,
                                               InscriberRecipe recipe, int parallel,
                                               CallbackInfoReturnable<TickRateModulation> cir) {
        // 模仿原版进度推进逻辑，但在进度完成时执行多倍结算
        // 不取消原版逻辑，让原版正常推进度条
        // 但我们需要在 smash 触发时注入多倍结算

        // 缓存并行数，在 smash 动画结算时使用
        this.ae2oc_pendingParallel = parallel;

        // 不 cancel，让原版正常推进度条
        // 当原版触发 smash=true → finalStep 到 8 时，
        // 我们的 smash 处理器会用 pendingParallel 执行多倍结算

        // 但是：原版在 finalStep==8 时只结算 1 次
        // 所以我们需要拦截 —— 确保我们的 HEAD 注入在 smash 阶段能接管

        // 实际上，因为是 HEAD 注入，只要 smash=true 且 pendingParallel>1，
        // ae2oc_handleSmashAnimation 就会接管多倍结算
        // 原版的 smash 处理会被跳过（cir.cancel）

        // 不做任何事，让原版走自己的进度条
        // 当 smash 被原版触发时，我们的 HEAD 会拦截
    }

    /**
     * 原子化执行多倍配方结算
     * 
     * 在同一方法内完成：扣材料 → 出产物
     * （能量已在触发 smash 前预扣）
     * 
     * 产物先输出到本地槽，再转移到 ME 网络（如果有并行卡）
     */
    @Unique
    private void ae2oc_finishCraftParallel(InscriberBlockEntity self, int parallel) {
        InscriberRecipe recipe = this.getTask();
        if (recipe == null) {
            return;
        }

        try {
            java.lang.reflect.Field sideField = InscriberBlockEntity.class.getDeclaredField("sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            java.lang.reflect.Field topField = InscriberBlockEntity.class.getDeclaredField("topItemHandler");
            topField.setAccessible(true);
            Object topHandler = topField.get(self);

            java.lang.reflect.Field bottomField = InscriberBlockEntity.class.getDeclaredField("bottomItemHandler");
            bottomField.setAccessible(true);
            Object bottomHandler = bottomField.get(self);

            // 构造多倍产物
            ItemStack outputCopy = recipe.getResultItem().copy();
            int totalOutput = outputCopy.getCount() * parallel;
            outputCopy.setCount(totalOutput);

            int singleOutputCount = recipe.getResultItem().getCount();
            
            // 先放入本地输出槽
            java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);
            ItemStack leftover = (ItemStack) insertMethod.invoke(sideHandler, 1, outputCopy, false);
            int actualInserted = totalOutput - leftover.getCount();

            // 计算实际成功放入的份数
            int actualParallel = singleOutputCount > 0 ? actualInserted / singleOutputCount : 0;

            if (actualParallel > 0) {
                // 重置进度
                java.lang.reflect.Method setProcessingTime = InscriberBlockEntity.class.getDeclaredMethod(
                        "setProcessingTime", int.class);
                setProcessingTime.setAccessible(true);
                setProcessingTime.invoke(self, 0);

                // 扣除模板（PRESS 类型）
                if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                    java.lang.reflect.Method extractTop = topHandler.getClass().getMethod("extractItem",
                            int.class, int.class, boolean.class);
                    extractTop.invoke(topHandler, 0, actualParallel, false);

                    java.lang.reflect.Method extractBottom = bottomHandler.getClass().getMethod("extractItem",
                            int.class, int.class, boolean.class);
                    extractBottom.invoke(bottomHandler, 0, actualParallel, false);
                }

                // 扣除输入材料
                java.lang.reflect.Method extractSide = sideHandler.getClass().getMethod("extractItem",
                        int.class, int.class, boolean.class);
                extractSide.invoke(sideHandler, 0, actualParallel, false);
            }
            
            // 如果有并行卡，把本地输出槽的产物转移到 ME 网络
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
            if (parallelMultiplier > 1) {
                ae2oc_transferOutputToNetwork(self, sideHandler);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        ae2oc_saveChanges(self);
    }
    
    /**
     * 把本地输出槽的产物转移到 ME 网络
     */
    @Unique
    private void ae2oc_transferOutputToNetwork(InscriberBlockEntity self, Object sideHandler) {
        try {
            java.lang.reflect.Method getMainNode = self.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(self);
            if (mainNode == null) return;
            
            java.lang.reflect.Method getGrid = mainNode.getClass().getMethod("getGrid");
            Object grid = getGrid.invoke(mainNode);
            if (grid == null) return;
            
            IStorageService storageService = (IStorageService) ((appeng.api.networking.IGrid) grid).getService(IStorageService.class);
            if (storageService == null) return;
            
            java.lang.reflect.Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            java.lang.reflect.Method extractItem = sideHandler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
            
            // 输出槽是 slot 1
            ItemStack stack = (ItemStack) getStackInSlot.invoke(sideHandler, 1);
            if (stack.isEmpty()) return;
            
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return;
            
            long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, null);
            
            if (inserted > 0) {
                // 从本地槽取出已转移的数量
                extractItem.invoke(sideHandler, 1, (int) inserted, false);
            }
            
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 获取可用能量（内部缓存 + 网络）
     */
    @Unique
    private double ae2oc_getAvailableEnergy(InscriberBlockEntity self, IGridNode node) {
        // 内部缓存
        double internal = self.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);

        // 网络能量
        double network = 0;
        var grid = node.getGrid();
        if (grid != null) {
            IEnergyService energyService = grid.getEnergyService();
            network = energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        }

        return internal + network;
    }

    /**
     * 尝试消耗指定能量（先内部缓存，再网络）
     */
    @Unique
    private boolean ae2oc_tryConsumePower(InscriberBlockEntity self, IGridNode node, double powerNeeded) {
        // 先尝试内部缓存
        double extracted = self.extractAEPower(powerNeeded, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        if (extracted >= powerNeeded - 0.01) {
            self.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
            return true;
        }

        // 尝试网络
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

        return false;
    }

    @Unique
    private void ae2oc_markForUpdate(InscriberBlockEntity self) {
        try {
            java.lang.reflect.Method method = self.getClass().getMethod("markForUpdate");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_saveChanges(InscriberBlockEntity self) {
        try {
            java.lang.reflect.Method method = self.getClass().getMethod("saveChanges");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }
}
