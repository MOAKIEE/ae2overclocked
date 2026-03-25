package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCrystalAssembler", remap = false)
public abstract class MixinCrystalAssemblerOverclock {

    @Unique
    private static final double AE2OC_CRYSTAL_RECIPE_ENERGY = 2000.0;

    @Unique
    private boolean ae2oc_processing = false;

    @Unique
    private int ae2oc_prevProgress = -1;

    @Unique
    private int ae2oc_pendingParallel = 0;

    @Unique
    private Object ae2oc_cachedRecipe = null;

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
                    ae2oc_instantCraft(self, node, Math.max(parallelMultiplier, 1));
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
            if (recipe == null) {
                this.ae2oc_pendingParallel = 0;
                this.ae2oc_cachedRecipe = null;
                return;
            }

            this.ae2oc_cachedRecipe = recipe;
            this.ae2oc_pendingParallel = ae2oc_calculateParallel(self, node, ctx, recipe, parallelMultiplier);
        } catch (Exception e) {
        }
    }

    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_tailTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_prevProgress <= 0 || this.ae2oc_cachedRecipe == null) {
            ae2oc_resetCache();
            return;
        }

        try {
            Object self = this;
            Field progressField = ReflectionCache.getFieldHierarchy(self.getClass(), "progress");
            if (progressField == null) { ae2oc_resetCache(); return; }
            int currentProgress = progressField.getInt(self);

            if (currentProgress == 0) {
                this.ae2oc_processing = true;
                try {
                    ae2oc_doExtraOutputs(self, node, this.ae2oc_pendingParallel - 1, this.ae2oc_cachedRecipe);
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
        this.ae2oc_prevProgress = -1;
        this.ae2oc_pendingParallel = 0;
        this.ae2oc_cachedRecipe = null;
    }

    @Unique
    private int ae2oc_calculateParallel(Object self, IGridNode node, Object ctx, Object recipe, int cardMultiplier) {
        try {
            Method testRecipe = ReflectionCache.getMethod(ctx.getClass(), "testRecipe", RecipeHolder.class);
            if (testRecipe == null) return 0;
            if (!(boolean) testRecipe.invoke(ctx, recipe)) {
                return 0;
            }

            double availableEnergy = ae2oc_getAvailableEnergy(self, node);
            int energyLimit = (int) (availableEnergy / AE2OC_CRYSTAL_RECIPE_ENERGY);
            return Math.max(0, Math.min(cardMultiplier, energyLimit));
        } catch (Exception e) {
            return 1;
        }
    }

    @Unique
    private void ae2oc_instantCraft(Object self, IGridNode node, int maxRounds) throws Exception {
        if (maxRounds <= 0) {
            return;
        }

        Field ctxField = ReflectionCache.getFieldHierarchy(self.getClass(), "ctx");
        if (ctxField == null) return;
        Object ctx = ctxField.get(self);

        Field recipeField = ReflectionCache.getFieldHierarchy(ctx.getClass(), "currentRecipe");
        if (recipeField == null) return;
        Object recipe = recipeField.get(ctx);
        if (recipe == null) {
            Method shouldTick = ReflectionCache.getMethod(ctx.getClass(), "shouldTick");
            if (shouldTick != null && (boolean) shouldTick.invoke(ctx)) {
                Method findRecipe = ReflectionCache.getDeclaredMethodHierarchy(ctx.getClass(), "findRecipe");
                if (findRecipe != null) {
                    findRecipe.invoke(ctx);
                    recipe = recipeField.get(ctx);
                }
            }
        }
        if (recipe == null) {
            return;
        }

        int crafted = ae2oc_craftRounds(self, node, ctx, recipe, maxRounds);
        if (crafted <= 0) {
            return;
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
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds, Object recipe) {
        if (extraRounds <= 0 || recipe == null) {
            return;
        }

        try {
            Field ctxField = ReflectionCache.getFieldHierarchy(self.getClass(), "ctx");
            if (ctxField == null) return;
            Object ctx = ctxField.get(self);

            int crafted = ae2oc_craftRounds(self, node, ctx, recipe, extraRounds);
            if (crafted > 0) {
                ae2oc_saveChanges(self);
            }
        } catch (Exception e) {
        }
    }

    @Unique
    private int ae2oc_craftRounds(Object self, IGridNode node, Object ctx, Object recipe, int maxRounds) throws Exception {
        Field outputField = ReflectionCache.getFieldHierarchy(self.getClass(), "output");
        if (outputField == null) return 0;
        Object outputInv = outputField.get(self);

        Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem", int.class, ItemStack.class, boolean.class);
        Method testRecipe = ReflectionCache.getMethod(ctx.getClass(), "testRecipe", RecipeHolder.class);
        Method runRecipe = ReflectionCache.getMethod(ctx.getClass(), "runRecipe", RecipeHolder.class);
        if (insertItem == null || testRecipe == null || runRecipe == null) return 0;

        // recipe is RecipeHolder, need to get the actual recipe value
        Method recipeValueMethod = ReflectionCache.getMethod(recipe.getClass(), "value");
        if (recipeValueMethod == null) return 0;
        Object recipeValue = recipeValueMethod.invoke(recipe);
        Field recipeOutputField = ReflectionCache.getFieldHierarchy(recipeValue.getClass(), "output");
        if (recipeOutputField == null) return 0;
        ItemStack recipeOutput = ((ItemStack) recipeOutputField.get(recipeValue)).copy();
        if (recipeOutput.isEmpty()) {
            return 0;
        }

        boolean directToNetwork = ParallelCardRuntime.getParallelMultiplier(self) > 1 || OverclockCardRuntime.hasOverclockCard(self);
        int crafted = 0;
        for (int i = 0; i < maxRounds; i++) {
            if (!(boolean) testRecipe.invoke(ctx, recipe)) {
                break;
            }

            ItemStack output = recipeOutput.copy();
            if (!ae2oc_canStoreOutput(node, outputInv, insertItem, output, directToNetwork)) {
                break;
            }

            if (!ae2oc_tryConsumePower(self, node, AE2OC_CRYSTAL_RECIPE_ENERGY)) {
                break;
            }

            runRecipe.invoke(ctx, recipe);
            if (directToNetwork) {
                int insertedToNetwork = ae2oc_tryOutputToNetwork(node, output, Actionable.MODULATE);
                if (insertedToNetwork < output.getCount()) {
                    ItemStack remaining = output.copy();
                    remaining.setCount(output.getCount() - insertedToNetwork);
                    insertItem.invoke(outputInv, 0, remaining, false);
                }
            } else {
                insertItem.invoke(outputInv, 0, output, false);
            }
            crafted++;
        }

        if (directToNetwork && crafted > 0) {
            ae2oc_transferOutputToNetwork(node, outputInv);
        }

        return crafted;
    }

    @Unique
    private boolean ae2oc_canStoreOutput(IGridNode node, Object outputInv, Method insertItem, ItemStack output, boolean directToNetwork) {
        try {
            if (directToNetwork) {
                int insertedToNetwork = ae2oc_tryOutputToNetwork(node, output, Actionable.SIMULATE);
                if (insertedToNetwork >= output.getCount()) {
                    return true;
                }

                ItemStack remaining = output.copy();
                remaining.setCount(output.getCount() - insertedToNetwork);
                return ((ItemStack) insertItem.invoke(outputInv, 0, remaining, true)).isEmpty();
            }

            return ((ItemStack) insertItem.invoke(outputInv, 0, output, true)).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private int ae2oc_tryOutputToNetwork(IGridNode node, ItemStack outputStack, Actionable mode) {
        if (outputStack.isEmpty()) {
            return 0;
        }
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

            long inserted = storageService.getInventory().insert(
                    key,
                    outputStack.getCount(),
                    mode,
                    IActionSource.empty()
            );
            if (inserted <= 0) {
                return 0;
            }
            return (int) Math.min(inserted, Integer.MAX_VALUE);
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private void ae2oc_transferOutputToNetwork(IGridNode node, Object outputInv) {
        try {
            Method getStackInSlot = ReflectionCache.getMethod(outputInv.getClass(), "getStackInSlot", int.class);
            Method extractItem = ReflectionCache.getMethod(outputInv.getClass(), "extractItem", int.class, int.class, boolean.class);
            if (getStackInSlot == null || extractItem == null) return;
            ItemStack stack = (ItemStack) getStackInSlot.invoke(outputInv, 0);
            if (stack.isEmpty()) {
                return;
            }

            int inserted = ae2oc_tryOutputToNetwork(node, stack, Actionable.MODULATE);
            if (inserted > 0) {
                extractItem.invoke(outputInv, 0, inserted, false);
            }
        } catch (Exception e) {
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
