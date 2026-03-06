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
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;

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
                    ae2oc_instantCraft(self, node, Math.max(parallelMultiplier, 1));
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
            Field progressField = ae2oc_getField(self.getClass(), "progress");
            progressField.setAccessible(true);
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
            Method testRecipe = ctx.getClass().getMethod("testRecipe", RecipeHolder.class);
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

        Field ctxField = ae2oc_getField(self.getClass(), "ctx");
        ctxField.setAccessible(true);
        Object ctx = ctxField.get(self);

        Field recipeField = ae2oc_getField(ctx.getClass(), "currentRecipe");
        recipeField.setAccessible(true);
        Object recipe = recipeField.get(ctx);
        if (recipe == null) {
            Method shouldTick = ctx.getClass().getMethod("shouldTick");
            if ((boolean) shouldTick.invoke(ctx)) {
                Method findRecipe = ae2oc_getMethod(ctx.getClass(), "findRecipe");
                findRecipe.setAccessible(true);
                findRecipe.invoke(ctx);
                recipe = recipeField.get(ctx);
            }
        }
        if (recipe == null) {
            return;
        }

        int crafted = ae2oc_craftRounds(self, node, ctx, recipe, maxRounds);
        if (crafted <= 0) {
            return;
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
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds, Object recipe) {
        if (extraRounds <= 0 || recipe == null) {
            return;
        }

        try {
            Field ctxField = ae2oc_getField(self.getClass(), "ctx");
            ctxField.setAccessible(true);
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
        Field outputField = ae2oc_getField(self.getClass(), "output");
        outputField.setAccessible(true);
        Object outputInv = outputField.get(self);

        Method insertItem = outputInv.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
        Method testRecipe = ctx.getClass().getMethod("testRecipe", RecipeHolder.class);
        Method runRecipe = ctx.getClass().getMethod("runRecipe", RecipeHolder.class);

        // recipe is RecipeHolder, need to get the actual recipe value
        Method recipeValueMethod = recipe.getClass().getMethod("value");
        Object recipeValue = recipeValueMethod.invoke(recipe);
        Field recipeOutputField = ae2oc_getField(recipeValue.getClass(), "output");
        recipeOutputField.setAccessible(true);
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
            Method getStackInSlot = outputInv.getClass().getMethod("getStackInSlot", int.class);
            Method extractItem = outputInv.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
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
