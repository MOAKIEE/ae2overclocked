package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.support.OverclockCardRuntime;
import moakiee.support.ParallelCardRuntime;
import moakiee.support.ParallelEngine;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 并行卡 + 超频卡 功能注入 — ExtendedAE 扩展压印器线程
 *
 * ExtendedAE 的 TileExInscriber 内部使用 4 个 InscriberThread。
 * 每个线程独立运行，超频/并行卡通过 host 字段检测。
 *
 * 结算顺序与压印器一致：先定并行量 → 再算总电 → 判超频 → 秒结算
 */
@Mixin(targets = "com.glodblock.github.extendedae.common.me.InscriberThread", remap = false)
public abstract class MixinExInscriberThreadOverclock {

    @Unique
    private static final double AE2OC_THREAD_RECIPE_ENERGY = 2000.0;

    /** 缓存并行结算结果 */
    @Unique
    private int ae2oc_pendingParallel = 0;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ae2oc_parallelOverclockThreadTick(CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;

        try {
            // 获取 host
            Field hostField = ae2oc_getField(self.getClass(), "host");
            hostField.setAccessible(true);
            Object host = hostField.get(self);

            boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(host);
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(host);

            // 无卡 → 走原版
            if (!hasOverclock && parallelMultiplier <= 1) {
                return;
            }

            // 获取 smash/finalStep 字段
            Field smashField = ae2oc_getField(self.getClass(), "smash");
            smashField.setAccessible(true);
            boolean smash = smashField.getBoolean(self);

            Field finalStepField = ae2oc_getField(self.getClass(), "finalStep");
            finalStepField.setAccessible(true);
            int finalStep = finalStepField.getInt(self);

            // 正在播放 smash 动画
            if (smash) {
                finalStep += 4;
                finalStepField.setInt(self, finalStep);

                if (finalStep >= 8 && finalStep < 16) {
                    int parallel = Math.max(this.ae2oc_pendingParallel, 1);
                    ae2oc_finishCraftParallel(self, host, parallel);
                    this.ae2oc_pendingParallel = 0;
                    finalStepField.setInt(self, 16);
                }
                if (finalStep >= 16) {
                    finalStepField.setInt(self, 0);
                    Method setSmash = self.getClass().getMethod("setSmash", boolean.class);
                    setSmash.invoke(self, false);
                    ae2oc_markHostForUpdate(host);
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }

            // 获取配方
            Method getTask = self.getClass().getMethod("getTask");
            InscriberRecipe recipe = (InscriberRecipe) getTask.invoke(self);
            if (recipe == null) {
                return;
            }

            // 计算并行数
            int actualParallel = ae2oc_calculateParallel(self, host, recipe, parallelMultiplier);
            if (actualParallel < 1) {
                return;
            }

            if (hasOverclock) {
                // 超频模式
                double totalEnergy = actualParallel * AE2OC_THREAD_RECIPE_ENERGY;
                if (!ae2oc_tryConsumePower(host, totalEnergy)) {
                    return;
                }

                this.ae2oc_pendingParallel = actualParallel;
                Method setSmash = self.getClass().getMethod("setSmash", boolean.class);
                setSmash.invoke(self, true);
                finalStepField.setInt(self, 0);
                ae2oc_markHostForUpdate(host);
                cir.setReturnValue(TickRateModulation.URGENT);
            } else {
                // 仅并行模式：缓存并行数，让原版推进度条
                this.ae2oc_pendingParallel = actualParallel;
            }

        } catch (Exception e) {
            // 反射失败，走原版
        }
    }

    /**
     * 计算实际并行数（木桶效应）
     */
    @Unique
    private int ae2oc_calculateParallel(Object self, Object host, InscriberRecipe recipe, int cardMultiplier) {
        try {
            Field sideField = ae2oc_getField(self.getClass(), "sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            ItemStack inputStack = (ItemStack) getStackInSlot.invoke(sideHandler, 0);
            int inputCount = inputStack.getCount();

            // PRESS 模板约束
            if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                Field topField = ae2oc_getField(self.getClass(), "topItemHandler");
                topField.setAccessible(true);
                Object topHandler = topField.get(self);

                Field bottomField = ae2oc_getField(self.getClass(), "bottomItemHandler");
                bottomField.setAccessible(true);
                Object bottomHandler = bottomField.get(self);

                ItemStack topStack = (ItemStack) getStackInSlot.invoke(topHandler, 0);
                ItemStack bottomStack = (ItemStack) getStackInSlot.invoke(bottomHandler, 0);

                int topCount = topStack.isEmpty() ? Integer.MAX_VALUE : topStack.getCount();
                int bottomCount = bottomStack.isEmpty() ? Integer.MAX_VALUE : bottomStack.getCount();
                cardMultiplier = Math.min(cardMultiplier, Math.min(topCount, bottomCount));
            }

            ItemStack outputStack = recipe.getResultItem().copy();

            Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            double availableEnergy = ae2oc_getAvailableEnergy(host);

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    cardMultiplier, inputCount, 1, outputStack,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertMethod.invoke(sideHandler, 1, stack, simulate);
                        } catch (Exception e) {
                            return stack;
                        }
                    },
                    availableEnergy, AE2OC_THREAD_RECIPE_ENERGY
            );

            return result.actualParallel();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 原子化执行多倍结算
     * 
     * 产物先输出到本地槽，再转移到 ME 网络（如果有并行卡）
     */
    @Unique
    private void ae2oc_finishCraftParallel(Object self, Object host, int parallel) {
        try {
            Method getTask = self.getClass().getMethod("getTask");
            InscriberRecipe recipe = (InscriberRecipe) getTask.invoke(self);
            if (recipe == null) return;

            Field sideField = ae2oc_getField(self.getClass(), "sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            Field topField = ae2oc_getField(self.getClass(), "topItemHandler");
            topField.setAccessible(true);
            Object topHandler = topField.get(self);

            Field bottomField = ae2oc_getField(self.getClass(), "bottomItemHandler");
            bottomField.setAccessible(true);
            Object bottomHandler = bottomField.get(self);

            // 多倍产物
            ItemStack outputCopy = recipe.getResultItem().copy();
            int totalOutput = outputCopy.getCount() * parallel;
            outputCopy.setCount(totalOutput);

            int singleOutputCount = recipe.getResultItem().getCount();
            
            // 先放入本地输出槽
            Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);
            ItemStack leftover = (ItemStack) insertMethod.invoke(sideHandler, 1, outputCopy, false);
            int actualInserted = totalOutput - leftover.getCount();

            int actualParallel = singleOutputCount > 0 ? actualInserted / singleOutputCount : 0;

            if (actualParallel > 0) {
                Method setProcessingTime = self.getClass().getDeclaredMethod("setProcessingTime", int.class);
                setProcessingTime.setAccessible(true);
                setProcessingTime.invoke(self, 0);

                if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                    Method extractItem = topHandler.getClass().getMethod("extractItem",
                            int.class, int.class, boolean.class);
                    extractItem.invoke(topHandler, 0, actualParallel, false);
                    extractItem.invoke(bottomHandler, 0, actualParallel, false);
                }

                Method sideExtract = sideHandler.getClass().getMethod("extractItem",
                        int.class, int.class, boolean.class);
                sideExtract.invoke(sideHandler, 0, actualParallel, false);
            }
            
            // 如果有并行卡，把本地输出槽的产物转移到 ME 网络
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(host);
            if (parallelMultiplier > 1) {
                ae2oc_transferOutputToNetwork(host, sideHandler);
            }

            ae2oc_saveHostChanges(host);
        } catch (Exception e) {
            // 忽略
        }
    }
    
    /**
     * 把本地输出槽的产物转移到 ME 网络
     */
    @Unique
    private void ae2oc_transferOutputToNetwork(Object host, Object sideHandler) {
        try {
            Method getMainNode = host.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(host);
            if (mainNode == null) return;
            
            Method getGrid = mainNode.getClass().getMethod("getGrid");
            Object grid = getGrid.invoke(mainNode);
            if (grid == null) return;
            
            IStorageService storageService = (IStorageService) ((appeng.api.networking.IGrid) grid).getService(IStorageService.class);
            if (storageService == null) return;
            
            Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            Method extractItem = sideHandler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
            
            // 输出槽是 slot 1
            ItemStack stack = (ItemStack) getStackInSlot.invoke(sideHandler, 1);
            if (stack.isEmpty()) return;
            
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return;
            
            // 获取 IActionSource 用于 ME 网络操作（修复 AE2 Additions 超级存储元件兼容性问题）
            appeng.api.networking.security.IActionSource actionSource = ae2oc_getActionSource(host, mainNode);

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
    private appeng.api.networking.security.IActionSource ae2oc_getActionSource(Object host, Object mainNode) {
        try {
            if (mainNode != null) {
                Method getNode = mainNode.getClass().getMethod("getNode");
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

    @Unique
    private double ae2oc_getAvailableEnergy(Object host) {
        double total = 0;
        try {
            Method extractMethod = host.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            total += (double) extractMethod.invoke(host, Double.MAX_VALUE,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);

            Method getMainNode = host.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(host);
            if (mainNode != null) {
                Method getGrid = mainNode.getClass().getMethod("getGrid");
                Object grid = getGrid.invoke(mainNode);
                if (grid != null) {
                    Method getEnergyService = grid.getClass().getMethod("getEnergyService");
                    IEnergyService energyService = (IEnergyService) getEnergyService.invoke(grid);
                    total += energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return total;
    }

    @Unique
    private boolean ae2oc_tryConsumePower(Object host, double powerNeeded) {
        try {
            Method extractMethod = host.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);

            double extracted = (double) extractMethod.invoke(host, powerNeeded,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (extracted >= powerNeeded - 0.01) {
                extractMethod.invoke(host, powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }

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
    private void ae2oc_markHostForUpdate(Object host) {
        try {
            Method method = host.getClass().getMethod("markForUpdate");
            method.invoke(host);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_saveHostChanges(Object host) {
        try {
            Method method = host.getClass().getMethod("saveChanges");
            method.invoke(host);
        } catch (Exception ignored) {
        }
    }
}
