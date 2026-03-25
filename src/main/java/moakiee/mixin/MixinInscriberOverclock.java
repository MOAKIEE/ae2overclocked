package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
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

    /** 超频模式 tick 计数器 */
    @Unique
    private int ae2oc_tickCounter = 0;

    /** 超频模式是否已激活（已扣电，正在等待 N tick） */
    @Unique
    private boolean ae2oc_overclockActive = false;

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

        // 主动刷新：将输出槽残留物品转移到 ME 网络（防止死锁）
        ae2oc_tryFlushOutputSlot(self);

        // 超频模式正在计时中
        if (this.ae2oc_overclockActive) {
            this.ae2oc_tickCounter++;
            int targetTicks = OverclockCardRuntime.getProcessTicks();

            if (this.ae2oc_tickCounter >= targetTicks) {
                // 到达 N tick，触发 smash 动画
                this.ae2oc_overclockActive = false;
                this.ae2oc_tickCounter = 0;
                this.setSmash(true);
                this.finalStep = 0;
                ae2oc_markForUpdate(self);
            }
            cir.setReturnValue(TickRateModulation.URGENT);
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
            return;
        }

        if (hasOverclock) {
            // === 超频模式：一次性扣电，开始计时 ===
            double totalEnergy = actualParallel * AE2OC_INSCRIBER_RECIPE_ENERGY;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) {
                return;
            }

            this.ae2oc_pendingParallel = actualParallel;
            this.ae2oc_overclockActive = true;
            this.ae2oc_tickCounter = 0;
            ae2oc_markForUpdate(self);
            cir.setReturnValue(TickRateModulation.URGENT);
        } else {
            // 仅并行模式：让原版正常推进度条， smash 触发时用 pendingParallel 多倍结算
            this.ae2oc_pendingParallel = actualParallel;
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

            // 可用能量
            double availableEnergy = ae2oc_getAvailableEnergy(self, node);

            // 有并行卡时，产物优先输出到ME网络，不受本地槽限制（与切片器行为一致）
            if (cardMultiplier > 1) {
                return ParallelEngine.calculateSimple(
                        cardMultiplier,
                        inputCount, recipeInputCount,
                        Integer.MAX_VALUE,
                        availableEnergy,
                        AE2OC_INSCRIBER_RECIPE_ENERGY
                ).actualParallel();
            }

            // 无并行卡时使用本地槽限制
            java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    cardMultiplier,
                    inputCount, recipeInputCount,
                    outputStack,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertMethod.invoke(sideHandler, 1, stack, simulate);
                        } catch (Exception e) {
                            return stack;
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
     * 原子化执行多倍配方结算。
     * 有并行卡/超频卡时，产物优先直出 ME 网络，剩余放本地槽。
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
            int singleOutputCount = recipe.getResultItem().getCount();

            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
            boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(self);
            boolean directToNetwork = parallelMultiplier > 1 || hasOverclock;

            int actualInserted;
            if (directToNetwork) {
                // 有并行卡/超频卡：产物优先直出 ME 网络
                int insertedToNetwork = ae2oc_tryDirectOutputToNetwork(self, outputCopy.copyWithCount(totalOutput));
                int remaining = totalOutput - insertedToNetwork;
                actualInserted = insertedToNetwork;
                // 剩余放本地槽
                if (remaining > 0) {
                    java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                            int.class, ItemStack.class, boolean.class);
                    ItemStack leftover = (ItemStack) insertMethod.invoke(sideHandler, 1,
                            outputCopy.copyWithCount(remaining), false);
                    actualInserted += remaining - leftover.getCount();
                }
            } else {
                // 无卡：放入本地输出槽
                outputCopy.setCount(totalOutput);
                java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                        int.class, ItemStack.class, boolean.class);
                ItemStack leftover = (ItemStack) insertMethod.invoke(sideHandler, 1, outputCopy, false);
                actualInserted = totalOutput - leftover.getCount();
            }

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

        } catch (Exception e) {
            // ignored
        }

        ae2oc_saveChanges(self);
    }

    /**
     * 直接将产物输出到 ME 网络，返回实际插入数量
     */
    @Unique
    private int ae2oc_tryDirectOutputToNetwork(InscriberBlockEntity self, ItemStack outputStack) {
        try {
            java.lang.reflect.Method getMainNode = self.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(self);
            if (mainNode == null) return 0;

            java.lang.reflect.Method getGrid = mainNode.getClass().getMethod("getGrid");
            Object grid = getGrid.invoke(mainNode);
            if (grid == null) return 0;

            IStorageService storageService = (IStorageService) ((appeng.api.networking.IGrid) grid).getService(IStorageService.class);
            if (storageService == null) return 0;

            AEItemKey key = AEItemKey.of(outputStack);
            if (key == null) return 0;

            appeng.api.networking.security.IActionSource actionSource = ae2oc_getActionSource(self, mainNode);
            long inserted = storageService.getInventory().insert(key, outputStack.getCount(), Actionable.MODULATE, actionSource);
            return (int) inserted;
        } catch (Exception e) {
            return 0;
        }
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
            
            // 获取 IActionSource 用于 ME 网络操作（修复 AE2 Additions 超级存储元件兼容性问题）
            appeng.api.networking.security.IActionSource actionSource = ae2oc_getActionSource(self, mainNode);

            long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, actionSource);

            if (inserted > 0) {
                // 从本地槽取出已转移的数量
                extractItem.invoke(sideHandler, 1, (int) inserted, false);
            }
            
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 获取有效的 IActionSource 用于 ME 网络操作
     * 修复 AE2 Additions 超级存储元件因 null 参数导致的兼容性问题
     */
    @Unique
    private appeng.api.networking.security.IActionSource ae2oc_getActionSource(InscriberBlockEntity self, Object mainNode) {
        try {
            if (mainNode != null) {
                java.lang.reflect.Method getNode = mainNode.getClass().getMethod("getNode");
                Object node = getNode.invoke(mainNode);
                if (node instanceof appeng.api.networking.IGridNode gridNode) {
                    Object owner = gridNode.getOwner();
                    if (owner instanceof appeng.api.networking.security.IActionHost actionHost) {
                        return appeng.api.networking.security.IActionSource.ofMachine(actionHost);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return appeng.api.networking.security.IActionSource.empty();
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
        // 1. 模拟检查总可用能量（内部 + 网络）
        double internalAvail = self.extractAEPower(powerNeeded, Actionable.SIMULATE, PowerMultiplier.CONFIG);

        double networkAvail = 0;
        IEnergyService energyService = null;
        var grid = node.getGrid();
        if (grid != null) {
            energyService = grid.getEnergyService();
            networkAvail = energyService.extractAEPower(powerNeeded, Actionable.SIMULATE,
                    PowerMultiplier.CONFIG);
        }

        if (internalAvail + networkAvail < powerNeeded - 0.01) {
            return false; // 总量不够
        }

        // 2. 实际扣除：先扣内部，剩余扣网络
        double remaining = powerNeeded;
        double actualInternal = self.extractAEPower(remaining, Actionable.MODULATE, PowerMultiplier.CONFIG);
        remaining -= actualInternal;

        if (remaining > 0.01 && energyService != null) {
            energyService.extractAEPower(remaining, Actionable.MODULATE, PowerMultiplier.CONFIG);
        }

        return true;
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

    /**
     * 主动刷新输出槽到 ME 网络，防止 ME 短暂掉线后死锁
     */
    @Unique
    private void ae2oc_tryFlushOutputSlot(InscriberBlockEntity self) {
        try {
            java.lang.reflect.Field sideField = InscriberBlockEntity.class.getDeclaredField("sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            java.lang.reflect.Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            ItemStack stack = (ItemStack) getStackInSlot.invoke(sideHandler, 1);
            if (stack.isEmpty()) return;

            ae2oc_transferOutputToNetwork(self, sideHandler);
        } catch (Exception ignored) {
        }
    }
}
