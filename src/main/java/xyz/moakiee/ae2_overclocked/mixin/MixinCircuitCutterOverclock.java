package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelEngine;
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
                this.ae2oc_processing = true;
                try {
                    ae2oc_instantCraft(self, node, parallelMultiplier);
                } finally {
                    this.ae2oc_processing = false;
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }
            Field progressField = ae2oc_getField(self.getClass(), "progress");
            progressField.setAccessible(true);
            this.ae2oc_prevProgress = progressField.getInt(self);
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
            Field progressField = ae2oc_getField(self.getClass(), "progress");
            progressField.setAccessible(true);
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
        Field ctxField = ae2oc_getField(self.getClass(), "ctx");
        ctxField.setAccessible(true);
        Object ctx = ctxField.get(self);

        Field recipeField = ae2oc_getField(ctx.getClass(), "currentRecipe");
        recipeField.setAccessible(true);
        Object currentRecipe = recipeField.get(ctx);
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

        Method testRecipe = ctx.getClass().getMethod("testRecipe", RecipeHolder.class);
        if (!(boolean) testRecipe.invoke(ctx, currentRecipe)) return;
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
        ParallelEngine.ParallelResult result;
        if (parallelMultiplier > 1) {
            result = ParallelEngine.calculateSimple(
                    parallelMultiplier, inputCount, 1,
                    Integer.MAX_VALUE, // ??????????????ME??
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
        Method runRecipe = ctx.getClass().getMethod("runRecipe", RecipeHolder.class);
        int crafted = 0;
        boolean directToNetwork = parallelMultiplier > 1;
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

        Field progressField = ae2oc_getField(self.getClass(), "progress");
        progressField.setAccessible(true);
        progressField.setInt(self, 0);
        recipeField.set(ctx, null);

        Method setWorking = self.getClass().getMethod("setWorking", boolean.class);
        setWorking.invoke(self, false);

        ae2oc_markForUpdate(self);
        ae2oc_saveChanges(self);
    }

    
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
            if (cardMultiplier > 1) {
                return ParallelEngine.calculateSimple(
                        cardMultiplier, inputCount, 1,
                        Integer.MAX_VALUE, // ??????????????ME??
                        availableEnergy, AE2OC_CUTTER_RECIPE_ENERGY
                ).actualParallel();
            }
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

            Method runRecipe = ctx.getClass().getMethod("runRecipe", RecipeHolder.class);
            Method testRecipe = ctx.getClass().getMethod("testRecipe", RecipeHolder.class);
            double extraEnergy = extraRounds * AE2OC_CUTTER_RECIPE_ENERGY;
            if (!ae2oc_tryConsumePower(self, node, extraEnergy)) {
                double available = ae2oc_getAvailableEnergy(self, node);
                extraRounds = (int) (available / AE2OC_CUTTER_RECIPE_ENERGY);
                if (extraRounds <= 0) return;
                extraEnergy = extraRounds * AE2OC_CUTTER_RECIPE_ENERGY;
                if (!ae2oc_tryConsumePower(self, node, extraEnergy)) return;
            }
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
            boolean directToNetwork = parallelMultiplier > 1;
            if (directToNetwork) {
                ae2oc_transferOutputToNetwork(node, outputInv);
            }
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
                        outputInv.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class)
                                .invoke(outputInv, 0, remaining, false);
                    }
                } else {
                    if (!((ItemStack) outputInv.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class)
                            .invoke(outputInv, 0, output, true)).isEmpty()) {
                        break;
                    }
                    outputInv.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class)
                            .invoke(outputInv, 0, output, false);
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
            
            Method getStackInSlot = outputInv.getClass().getMethod("getStackInSlot", int.class);
            Method extractItem = outputInv.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
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
