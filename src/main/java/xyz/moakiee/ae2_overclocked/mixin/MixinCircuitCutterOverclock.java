package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelEngine;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter", remap = false)
public abstract class MixinCircuitCutterOverclock {

    @Unique
    private static final double AE2OC_CUTTER_RECIPE_ENERGY = 2000.0;

    @Unique
    private boolean ae2oc_processing = false;

    @Unique
    private int ae2oc_prevProgress = -1;

    @Unique
    private int ae2oc_pendingParallel = 0;

    @Unique
    private Object ae2oc_cachedRecipe = null;

    @Unique
    private ItemStack ae2oc_cachedOutput = ItemStack.EMPTY;

    @Unique
    private int ae2oc_tickAccumulator = 0;


    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_headTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;
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
                // Gate craft cycles by configurable tick interval.
                ae2oc_tickAccumulator += ticksSinceLastCall;
                if (ae2oc_tickAccumulator < Ae2OcConfig.getOverclockIntervalTicks()) {
                    cir.setReturnValue(TickRateModulation.URGENT);
                    return;
                }
                ae2oc_tickAccumulator = 0;

                this.ae2oc_processing = true;
                try {
                    ae2oc_instantCraft(self, node, parallelMultiplier);
                } finally {
                    this.ae2oc_processing = false;
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }
            Field progressField = ReflectionCache.getFieldHierarchy(self.getClass(), "progress");
            if (progressField == null) return;
            this.ae2oc_prevProgress = progressField.getInt(self);
            Field ctxField = ReflectionCache.getFieldHierarchy(self.getClass(), "ctx");
            if (ctxField == null) return;
            Object ctx = ctxField.get(self);

            Field recipeField = ReflectionCache.getFieldHierarchy(ctx.getClass(), "currentRecipe");
            if (recipeField == null) return;
            Object recipe = recipeField.get(ctx);

            if (recipe != null) {
                this.ae2oc_cachedRecipe = recipe;
                Method valueMethod = ReflectionCache.getMethod(recipe.getClass(), "value");
                if (valueMethod == null) return;
                Object recipeValue = valueMethod.invoke(recipe);
                Field outputFieldInRecipe = ReflectionCache.getFieldHierarchy(recipeValue.getClass(), "output");
                if (outputFieldInRecipe == null) return;
                this.ae2oc_cachedOutput = ((ItemStack) outputFieldInRecipe.get(recipeValue)).copy();
                this.ae2oc_pendingParallel = ae2oc_calculateParallel(self, node, recipe, parallelMultiplier);
            } else {
                this.ae2oc_pendingParallel = 0;
            }

        } catch (Exception e) {
        }
    }


    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_tailTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_prevProgress <= 0) {
            ae2oc_resetCache();
            return;
        }

        try {
            Object self = this;
            Field progressField = ReflectionCache.getFieldHierarchy(self.getClass(), "progress");
            if (progressField == null) { ae2oc_resetCache(); return; }
            int currentProgress = progressField.getInt(self);

            if (currentProgress == 0 && this.ae2oc_cachedRecipe != null) {
                int extraRounds = this.ae2oc_pendingParallel - 1;
                this.ae2oc_processing = true;
                try {
                    ae2oc_doExtraOutputs(self, node, extraRounds);
                } finally {
                    this.ae2oc_processing = false;
                }
            }

        } catch (Exception e) {
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


    @Unique
    private void ae2oc_instantCraft(Object self, IGridNode node, int parallelMultiplier) throws Exception {
        Field ctxField = ReflectionCache.getFieldHierarchy(self.getClass(), "ctx");
        if (ctxField == null) return;
        Object ctx = ctxField.get(self);

        Field recipeField = ReflectionCache.getFieldHierarchy(ctx.getClass(), "currentRecipe");
        if (recipeField == null) return;
        Object currentRecipe = recipeField.get(ctx);
        if (currentRecipe == null) {
            Method shouldTick = ReflectionCache.getMethod(ctx.getClass(), "shouldTick");
            if (shouldTick != null && (boolean) shouldTick.invoke(ctx)) {
                Method findRecipe = ReflectionCache.getDeclaredMethodHierarchy(ctx.getClass(), "findRecipe");
                if (findRecipe != null) {
                    findRecipe.invoke(ctx);
                    currentRecipe = recipeField.get(ctx);
                }
            }
        }

        if (currentRecipe == null) return;

        Method testRecipe = ReflectionCache.getMethod(ctx.getClass(), "testRecipe", RecipeHolder.class);
        if (testRecipe == null || !(boolean) testRecipe.invoke(ctx, currentRecipe)) return;

        Field inputField = ReflectionCache.getFieldHierarchy(self.getClass(), "input");
        if (inputField == null) return;
        Object inputInv = inputField.get(self);

        Field outputField = ReflectionCache.getFieldHierarchy(self.getClass(), "output");
        if (outputField == null) return;
        Object outputInv = outputField.get(self);

        Method recipeValueMethod = ReflectionCache.getMethod(currentRecipe.getClass(), "value");
        if (recipeValueMethod == null) return;
        Object recipeValue = recipeValueMethod.invoke(currentRecipe);
        Field recipeOutputField = ReflectionCache.getFieldHierarchy(recipeValue.getClass(), "output");
        if (recipeOutputField == null) return;
        ItemStack recipeOutput = ((ItemStack) recipeOutputField.get(recipeValue)).copy();

        Method getStackInSlot = ReflectionCache.getMethod(inputInv.getClass(), "getStackInSlot", int.class);
        if (getStackInSlot == null) return;
        ItemStack inputStack = (ItemStack) getStackInSlot.invoke(inputInv, 0);
        int inputCount = inputStack.getCount();

        Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                int.class, ItemStack.class, boolean.class);
        if (insertItem == null) return;

        double availableEnergy = ae2oc_getAvailableEnergy(self, node);
        boolean directToNetwork = parallelMultiplier > 1 || OverclockCardRuntime.hasOverclockCard(self);
        ParallelEngine.ParallelResult result;
        if (directToNetwork) {
            result = ParallelEngine.calculateSimple(
                    parallelMultiplier, inputCount, 1,
                    Integer.MAX_VALUE,
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
        Method runRecipe = ReflectionCache.getMethod(ctx.getClass(), "runRecipe", RecipeHolder.class);
        if (runRecipe == null) return;
        int crafted = 0;
        for (int i = 0; i < actualParallel; i++) {
            ItemStack singleOutput = recipeOutput.copy();

            if (directToNetwork) {
                runRecipe.invoke(ctx, currentRecipe);
                int insertedToNetwork = ae2oc_tryOutputToNetwork(node, singleOutput);
                if (insertedToNetwork < singleOutput.getCount()) {
                    ItemStack remaining = singleOutput.copy();
                    remaining.setCount(singleOutput.getCount() - insertedToNetwork);
                    insertItem.invoke(outputInv, 0, remaining, false);
                }
            } else {
                if (!((ItemStack) insertItem.invoke(outputInv, 0, singleOutput, true)).isEmpty()) {
                    break;
                }
                runRecipe.invoke(ctx, currentRecipe);
                insertItem.invoke(outputInv, 0, singleOutput, false);
            }
            crafted++;
        }

        Field progressField = ReflectionCache.getFieldHierarchy(self.getClass(), "progress");
        if (progressField != null) progressField.setInt(self, 0);
        recipeField.set(ctx, null);

        Method setWorking = ReflectionCache.getMethod(self.getClass(), "setWorking", boolean.class);
        if (setWorking != null) setWorking.invoke(self, false);

        ae2oc_markForUpdate(self);
        ae2oc_saveChanges(self);
    }


    @Unique
    private int ae2oc_calculateParallel(Object self, IGridNode node, Object recipe, int cardMultiplier) {
        try {
            Field inputField = ReflectionCache.getFieldHierarchy(self.getClass(), "input");
            if (inputField == null) return 1;
            Object inputInv = inputField.get(self);

            Field outputField = ReflectionCache.getFieldHierarchy(self.getClass(), "output");
            if (outputField == null) return 1;
            Object outputInv = outputField.get(self);

            Method getStackInSlot = ReflectionCache.getMethod(inputInv.getClass(), "getStackInSlot", int.class);
            if (getStackInSlot == null) return 1;
            ItemStack inputStack = (ItemStack) getStackInSlot.invoke(inputInv, 0);
            int inputCount = inputStack.getCount();

            Method recipeValueMethod = ReflectionCache.getMethod(recipe.getClass(), "value");
            if (recipeValueMethod == null) return 1;
            Object recipeValue = recipeValueMethod.invoke(recipe);
            Field recipeOutputField = ReflectionCache.getFieldHierarchy(recipeValue.getClass(), "output");
            if (recipeOutputField == null) return 1;
            ItemStack recipeOutput = ((ItemStack) recipeOutputField.get(recipeValue)).copy();

            double availableEnergy = ae2oc_getAvailableEnergy(self, node);
            if (cardMultiplier > 1 || OverclockCardRuntime.hasOverclockCard(self)) {
                return ParallelEngine.calculateSimple(
                        cardMultiplier, inputCount, 1,
                        Integer.MAX_VALUE,
                        availableEnergy, AE2OC_CUTTER_RECIPE_ENERGY
                ).actualParallel();
            }
            Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                    int.class, ItemStack.class, boolean.class);
            if (insertItem == null) return 1;

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


    @Unique
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds) {
        if (extraRounds <= 0 || this.ae2oc_cachedRecipe == null) return;

        try {
            Field ctxField = ReflectionCache.getFieldHierarchy(self.getClass(), "ctx");
            if (ctxField == null) return;
            Object ctx = ctxField.get(self);

            Field outputField = ReflectionCache.getFieldHierarchy(self.getClass(), "output");
            if (outputField == null) return;
            Object outputInv = outputField.get(self);

            Method runRecipe = ReflectionCache.getMethod(ctx.getClass(), "runRecipe", RecipeHolder.class);
            Method testRecipe = ReflectionCache.getMethod(ctx.getClass(), "testRecipe", RecipeHolder.class);
            if (runRecipe == null || testRecipe == null) return;

            double extraEnergy = extraRounds * AE2OC_CUTTER_RECIPE_ENERGY;
            if (!ae2oc_tryConsumePower(self, node, extraEnergy)) {
                double available = ae2oc_getAvailableEnergy(self, node);
                extraRounds = (int) (available / AE2OC_CUTTER_RECIPE_ENERGY);
                if (extraRounds <= 0) return;
                extraEnergy = extraRounds * AE2OC_CUTTER_RECIPE_ENERGY;
                if (!ae2oc_tryConsumePower(self, node, extraEnergy)) return;
            }
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
            boolean directToNetwork = parallelMultiplier > 1 || OverclockCardRuntime.hasOverclockCard(self);
            if (directToNetwork) {
                ae2oc_transferOutputToNetwork(node, outputInv);
            }

            Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                    int.class, ItemStack.class, boolean.class);
            if (insertItem == null) return;

            int actualExtra = 0;
            for (int i = 0; i < extraRounds; i++) {
                if (!(boolean) testRecipe.invoke(ctx, this.ae2oc_cachedRecipe)) break;
                runRecipe.invoke(ctx, this.ae2oc_cachedRecipe);

                ItemStack output = this.ae2oc_cachedOutput.copy();
                if (directToNetwork) {
                    int insertedToNetwork = ae2oc_tryOutputToNetwork(node, output);
                    if (insertedToNetwork < output.getCount()) {
                        ItemStack remaining = output.copy();
                        remaining.setCount(output.getCount() - insertedToNetwork);
                        insertItem.invoke(outputInv, 0, remaining, false);
                    }
                } else {
                    if (!((ItemStack) insertItem.invoke(outputInv, 0, output, true)).isEmpty()) {
                        break;
                    }
                    insertItem.invoke(outputInv, 0, output, false);
                }
                actualExtra++;
            }

            if (actualExtra > 0) {
                ae2oc_saveChanges(self);
            }

        } catch (Exception e) {
        }
    }


    @Unique
    private void ae2oc_transferOutputToNetwork(IGridNode node, Object outputInv) {
        try {
            var grid = node.getGrid();
            if (grid == null) return;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;

            Method getStackInSlot = ReflectionCache.getMethod(outputInv.getClass(), "getStackInSlot", int.class);
            Method extractItem = ReflectionCache.getMethod(outputInv.getClass(), "extractItem", int.class, int.class, boolean.class);
            if (getStackInSlot == null || extractItem == null) return;
            ItemStack stack = (ItemStack) getStackInSlot.invoke(outputInv, 0);
            if (stack.isEmpty()) return;

            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return;

                long inserted = storageService.getInventory().insert(
                    key,
                    stack.getCount(),
                    Actionable.MODULATE,
                    IActionSource.empty()
                );

            if (inserted >= stack.getCount()) {
                extractItem.invoke(outputInv, 0, stack.getCount(), false);
            } else if (inserted > 0) {
                extractItem.invoke(outputInv, 0, (int) inserted, false);
            }

        } catch (Exception e) {
        }
    }


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

            var inventory = storageService.getInventory();

            long inserted = inventory.insert(
                key,
                outputStack.getCount(),
                Actionable.MODULATE,
                IActionSource.empty()
            );

            return (int) inserted;

        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private double ae2oc_getAvailableEnergy(Object self, IGridNode node) {
        double total = 0;
        try {
            Method extractMethod = ReflectionCache.getMethod(self.getClass(),
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            if (extractMethod != null) {
                total += (double) extractMethod.invoke(self, Double.MAX_VALUE,
                        Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }

            var grid = node.getGrid();
            if (grid != null) {
                IEnergyService energyService = grid.getEnergyService();
                total += energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }
        } catch (Exception e) {
        }
        return total;
    }

    @Unique
    private boolean ae2oc_tryConsumePower(Object self, IGridNode node, double powerNeeded) {
        try {
            Method extractMethod = ReflectionCache.getMethod(self.getClass(),
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            if (extractMethod == null) return false;

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
        }
        return false;
    }

    @Unique
    private void ae2oc_markForUpdate(Object self) {
        try {
            Method method = ReflectionCache.getMethod(self.getClass(), "markForUpdate");
            if (method != null) method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_saveChanges(Object self) {
        try {
            Method method = ReflectionCache.getMethod(self.getClass(), "saveChanges");
            if (method != null) method.invoke(self);
        } catch (Exception ignored) {
        }
    }
}
