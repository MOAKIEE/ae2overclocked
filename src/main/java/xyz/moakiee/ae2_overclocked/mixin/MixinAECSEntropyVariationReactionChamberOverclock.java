package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.networking.storage.IStorageService;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.helpers.externalstorage.GenericStackInv;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Adds Overclock Card and Parallel Card support to AE2CS (ae2cs)
 * EntropyVariationReactionChamberBlockEntity.
 * <p>
 * This machine uses AE2 EntropyRecipe. Both input and output are handled by GenericStackInv
 * and can contain items or fluids.
 * Each recipe craft consumes a fixed 1600 AE (RECIPE_DEFAULT_COST_ENERGY).
 * Recipe assembly is encapsulated in a static method on EntropyVariationReactionChamberBlockEntity.
 */
@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity",
        remap = false)
public abstract class MixinAECSEntropyVariationReactionChamberOverclock implements IUpgradeableObject {

    @Shadow
    public abstract GenericStackInv getInputInv();

    @Shadow
    public abstract GenericStackInv getOutputInv();

    @Shadow
    public abstract IUpgradeInventory getUpgrades();

    @Unique
    private static final int AE2OC_RECIPE_DEFAULT_ENERGY = 1600;

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

    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2oc_headTick(CallbackInfo ci) {
        if (ae2oc_processing) return;

        boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(this);
        int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(this);
        if (!hasOverclock && parallelMultiplier <= 1) return;

        try {
            if (hasOverclock) {
                // Gate craft cycles by configurable tick interval.
                ae2oc_tickAccumulator++;
                if (ae2oc_tickAccumulator < Ae2OcConfig.getOverclockIntervalTicks()) {
                    ci.cancel();
                    return;
                }
                ae2oc_tickAccumulator = 0;

                ae2oc_processing = true;
                try {
                    ae2oc_instantCraft(Math.max(parallelMultiplier, 1));
                } finally {
                    ae2oc_processing = false;
                }
                ci.cancel();
                return;
            }

            ae2oc_prevProgress = ae2oc_getRecipeProgress();
            ae2oc_cachedRecipe = ae2oc_getActiveRecipe();

            if (ae2oc_cachedRecipe != null) {
                ae2oc_pendingParallel = ae2oc_calculateParallel(parallelMultiplier);
            } else {
                ae2oc_pendingParallel = 0;
            }

        } catch (Exception ignored) {
        }
    }

    @Inject(method = "serverTick", at = @At("RETURN"), require = 0)
    private void ae2oc_tailTick(CallbackInfo ci) {
        if (ae2oc_pendingParallel <= 1 || ae2oc_prevProgress <= 0 || ae2oc_cachedRecipe == null) {
            ae2oc_resetCache();
            return;
        }

        try {
            int currentProgress = ae2oc_getRecipeProgress();
            if (currentProgress == 0) {
                ae2oc_processing = true;
                try {
                    ae2oc_doExtraOutputs(ae2oc_pendingParallel - 1, ae2oc_cachedRecipe);
                } finally {
                    ae2oc_processing = false;
                }
            }
        } catch (Exception ignored) {
        }

        ae2oc_resetCache();
    }

    @Unique
    private void ae2oc_resetCache() {
        ae2oc_prevProgress = -1;
        ae2oc_pendingParallel = 0;
        ae2oc_cachedRecipe = null;
    }

    @Unique
    private void ae2oc_instantCraft(int maxRounds) throws Exception {
        if (maxRounds <= 0) return;

        ae2oc_flushOutputToMENetwork();
        ae2oc_forceRefreshRecipe();

        Object recipe = ae2oc_getActiveRecipe();
        if (recipe == null) return;

        int energyCost = AE2OC_RECIPE_DEFAULT_ENERGY;

        int crafted = 0;
        for (int i = 0; i < maxRounds; i++) {
            List<GenericStack> outputs = ae2oc_getRecipeOutputs(recipe);
            if (outputs == null || outputs.isEmpty()) break;
            if (!ae2oc_canInsertOutputs(outputs) && !ae2oc_canOutputAllToMENetwork(outputs)) break;
            if (!ae2oc_canConsumeInputs(recipe)) break;
            double extracted = ae2oc_extractPower(energyCost, Actionable.SIMULATE);
            if (extracted < energyCost - 0.01) break;
            ae2oc_extractPower(energyCost, Actionable.MODULATE);
            ae2oc_consumeInputs(recipe);
            ae2oc_insertOutputsWithMEFallback(outputs);
            crafted++;
            ae2oc_setNeedRefresh(true);
            ae2oc_forceRefreshRecipe();
            recipe = ae2oc_getActiveRecipe();
            if (recipe == null) break;
        }

        if (crafted > 0) {
            ae2oc_flushOutputToMENetwork();
            ae2oc_setRecipeProgress(0);
            ae2oc_setChanged();
        }
    }

    @Unique
    private void ae2oc_doExtraOutputs(int extraRounds, Object recipe) throws Exception {
        if (extraRounds <= 0 || recipe == null) return;

        ae2oc_flushOutputToMENetwork();

        int energyCost = AE2OC_RECIPE_DEFAULT_ENERGY;

        int crafted = 0;
        for (int i = 0; i < extraRounds; i++) {
            List<GenericStack> outputs = ae2oc_getRecipeOutputs(recipe);
            if (outputs == null || outputs.isEmpty()) break;
            if (!ae2oc_canInsertOutputs(outputs) && !ae2oc_canOutputAllToMENetwork(outputs)) break;
            if (!ae2oc_canConsumeInputs(recipe)) break;
            double extracted = ae2oc_extractPower(energyCost, Actionable.SIMULATE);
            if (extracted < energyCost - 0.01) break;
            ae2oc_extractPower(energyCost, Actionable.MODULATE);
            ae2oc_consumeInputs(recipe);
            ae2oc_insertOutputsWithMEFallback(outputs);
            crafted++;
        }

        if (crafted > 0) {
            ae2oc_flushOutputToMENetwork();
            ae2oc_setNeedRefresh(true);
            ae2oc_setChanged();
        }
    }

    @Unique
    private int ae2oc_calculateParallel(int cardMultiplier) {
        double available = ae2oc_extractPower(Double.MAX_VALUE, Actionable.SIMULATE);
        int energyLimit = (int) (available / AE2OC_RECIPE_DEFAULT_ENERGY);
        return Math.max(0, Math.min(cardMultiplier, energyLimit));
    }

    // GenericStackInv output helpers.

    /** Fetch recipe outputs by calling static getRecipeOutput via reflection. */
    @Unique
    @SuppressWarnings("unchecked")
    private List<GenericStack> ae2oc_getRecipeOutputs(Object recipeHolder) {
        try {
            // recipeHolder is RecipeHolder<EntropyRecipe>.
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return java.util.Collections.emptyList();
            Object recipeValue = valueMethod.invoke(recipeHolder);
            // Call EntropyVariationReactionChamberBlockEntity.getRecipeOutput(EntropyRecipe).
            // This is a private static method, so reflection is required.
            Method m = ReflectionCache.getDeclaredMethodHierarchy(this.getClass(), "getRecipeOutput",
                    Class.forName("appeng.recipes.entropy.EntropyRecipe"));
            if (m == null) return java.util.Collections.emptyList();
            return (List<GenericStack>) m.invoke(null, recipeValue);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /** Check whether all outputs can be inserted. */
    @Unique
    private boolean ae2oc_canInsertOutputs(List<GenericStack> outputs) {
        try {
            IActionSource src = ae2oc_getActionSource();
            for (GenericStack stack : outputs) {
                long inserted = getOutputInv().insert(stack.what(), stack.amount(), Actionable.SIMULATE, src);
                if (inserted < stack.amount()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Insert outputs into GenericStackInv, with ME network fallback for any that don't fit. */
    @Unique
    private void ae2oc_insertOutputsWithMEFallback(List<GenericStack> outputs) {
        try {
            IActionSource src = ae2oc_getActionSource();
            for (GenericStack stack : outputs) {
                long inserted = getOutputInv().insert(stack.what(), stack.amount(), Actionable.MODULATE, src);
                if (inserted < stack.amount()) {
                    // Remainder goes to ME network
                    long remainder = stack.amount() - inserted;
                    ae2oc_outputToMENetwork(stack.what(), remainder);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Read the actionSource field. */
    @Unique
    private IActionSource ae2oc_getActionSource() {
        try {
            Field f = ReflectionCache.getFieldHierarchy(this.getClass(), "actionSource");
            if (f == null) return IActionSource.empty();
            return (IActionSource) f.get(this);
        } catch (Exception e) {
            return IActionSource.empty();
        }
    }

    // Input-consumption helpers (mirrors consumeInputs via reflection).

    @Unique
    private boolean ae2oc_canConsumeInputs(Object recipeHolder) {
        try {
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return false;
            Object recipeValue = valueMethod.invoke(recipeHolder);

            // EntropyRecipe.getInput() returns EntropyRecipe.Input.
            Method getInput = ReflectionCache.getMethod(recipeValue.getClass(), "getInput");
            if (getInput == null) return false;
            Object input = getInput.invoke(recipeValue);

            // Check block input.
            Method blockOpt = ReflectionCache.getMethod(input.getClass(), "block");
            if (blockOpt == null) return false;
            Object blockOptional = blockOpt.invoke(input);
            if (ae2oc_isPresent(blockOptional)) {
                Object blockInput = ae2oc_getOptionalValue(blockOptional);
                Method blockMethod = ReflectionCache.getMethod(blockInput.getClass(), "block");
                if (blockMethod == null) return false;
                net.minecraft.world.level.block.Block block =
                        (net.minecraft.world.level.block.Block) blockMethod.invoke(blockInput);
                net.minecraft.world.item.Item blockItem = block.asItem();
                // asItem() returns AIR for blocks without an item form; skip AIR.
                if (blockItem != net.minecraft.world.item.Items.AIR) {
                    AEItemKey key = AEItemKey.of(blockItem);
                    if (key != null) {
                        long extracted = getInputInv().extract(0, key, 1, Actionable.SIMULATE);
                        if (extracted < 1) return false;
                    }
                }
            }

            // Check fluid input.
            Method fluidOpt = ReflectionCache.getMethod(input.getClass(), "fluid");
            if (fluidOpt == null) return false;
            Object fluidOptional = fluidOpt.invoke(input);
            if (ae2oc_isPresent(fluidOptional)) {
                Object fluidInput = ae2oc_getOptionalValue(fluidOptional);
                Method fluidMethod = ReflectionCache.getMethod(fluidInput.getClass(), "fluid");
                if (fluidMethod == null) return false;
                net.minecraft.world.level.material.Fluid fluid =
                        (net.minecraft.world.level.material.Fluid) fluidMethod.invoke(fluidInput);
                if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                    AEFluidKey fluidKey = AEFluidKey.of(fluid);
                    long extracted = getInputInv().extract(0, fluidKey, 1000, Actionable.SIMULATE);
                    if (extracted < 1000) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private void ae2oc_consumeInputs(Object recipeHolder) {
        try {
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return;
            Object recipeValue = valueMethod.invoke(recipeHolder);

            Method getInput = ReflectionCache.getMethod(recipeValue.getClass(), "getInput");
            if (getInput == null) return;
            Object input = getInput.invoke(recipeValue);

            Method blockOpt = ReflectionCache.getMethod(input.getClass(), "block");
            if (blockOpt == null) return;
            Object blockOptional = blockOpt.invoke(input);
            if (ae2oc_isPresent(blockOptional)) {
                Object blockInput = ae2oc_getOptionalValue(blockOptional);
                Method blockMethod = ReflectionCache.getMethod(blockInput.getClass(), "block");
                if (blockMethod == null) return;
                net.minecraft.world.level.block.Block block =
                        (net.minecraft.world.level.block.Block) blockMethod.invoke(blockInput);
                net.minecraft.world.item.Item blockItem = block.asItem();
                if (blockItem != net.minecraft.world.item.Items.AIR) {
                    AEItemKey key = AEItemKey.of(blockItem);
                    if (key != null) {
                        getInputInv().extract(0, key, 1, Actionable.MODULATE);
                    }
                }
            }

            Method fluidOpt = ReflectionCache.getMethod(input.getClass(), "fluid");
            if (fluidOpt == null) return;
            Object fluidOptional = fluidOpt.invoke(input);
            if (ae2oc_isPresent(fluidOptional)) {
                Object fluidInput = ae2oc_getOptionalValue(fluidOptional);
                Method fluidMethod = ReflectionCache.getMethod(fluidInput.getClass(), "fluid");
                if (fluidMethod == null) return;
                net.minecraft.world.level.material.Fluid fluid =
                        (net.minecraft.world.level.material.Fluid) fluidMethod.invoke(fluidInput);
                if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                    AEFluidKey fluidKey = AEFluidKey.of(fluid);
                    getInputInv().extract(0, fluidKey, 1000, Actionable.MODULATE);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Unique
    private boolean ae2oc_isPresent(Object optional) {
        try {
            Method isPresent = ReflectionCache.getMethod(optional.getClass(), "isPresent");
            if (isPresent == null) return false;
            return (boolean) isPresent.invoke(optional);
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private Object ae2oc_getOptionalValue(Object optional) throws Exception {
        Method get = ReflectionCache.getMethod(optional.getClass(), "get");
        if (get == null) throw new NoSuchMethodException("get");
        return get.invoke(optional);
    }

    // ── ME network output helpers ─────────────────────────────────────────────

    /**
     * Get the IGridNode for this AE2CS machine via getMainNode().getNode().
     */
    @Unique
    private IGridNode ae2oc_getGridNode() {
        try {
            Method getMainNode = ReflectionCache.getMethod(this.getClass(), "getMainNode");
            if (getMainNode == null) return null;
            Object mainNode = getMainNode.invoke(this);
            if (mainNode == null) return null;
            Method getNode = ReflectionCache.getMethod(mainNode.getClass(), "getNode");
            if (getNode == null) return null;
            Object node = getNode.invoke(mainNode);
            return (node instanceof IGridNode) ? (IGridNode) node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if the ME network can accept ALL outputs (simulate).
     * Works with both items and fluids via AEKey.
     */
    @Unique
    private boolean ae2oc_canOutputAllToMENetwork(List<GenericStack> outputs) {
        try {
            IGridNode gridNode = ae2oc_getGridNode();
            if (gridNode == null || gridNode.getGrid() == null) return false;
            IStorageService storage = gridNode.getGrid().getService(IStorageService.class);
            if (storage == null) return false;
            for (GenericStack stack : outputs) {
                long accepted = storage.getInventory().insert(stack.what(), stack.amount(),
                        Actionable.SIMULATE, IActionSource.empty());
                if (accepted < stack.amount()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Push a single AEKey (item or fluid) directly into the ME network storage.
     */
    @Unique
    private void ae2oc_outputToMENetwork(AEKey what, long amount) {
        try {
            IGridNode gridNode = ae2oc_getGridNode();
            if (gridNode == null || gridNode.getGrid() == null) return;
            IStorageService storage = gridNode.getGrid().getService(IStorageService.class);
            if (storage == null) return;
            storage.getInventory().insert(what, amount, Actionable.MODULATE, IActionSource.empty());
        } catch (Exception ignored) {
        }
    }

    /**
     * Flush all items/fluids from the local GenericStackInv output into the ME network.
     * EntropyVariation outputs can be items or fluids.
     */
    @Unique
    private void ae2oc_flushOutputToMENetwork() {
        try {
            IGridNode gridNode = ae2oc_getGridNode();
            if (gridNode == null || gridNode.getGrid() == null) return;
            IStorageService storage = gridNode.getGrid().getService(IStorageService.class);
            if (storage == null) return;

            GenericStackInv outputInv = getOutputInv();
            for (int slot = 0; slot < outputInv.size(); slot++) {
                GenericStack stack = outputInv.getStack(slot);
                if (stack == null) continue;
                AEKey what = stack.what();
                long amount = stack.amount();
                if (what == null || amount <= 0) continue;

                long inserted = storage.getInventory().insert(what, amount,
                        Actionable.MODULATE, IActionSource.empty());
                if (inserted >= amount) {
                    // Fully pushed — clear the slot
                    outputInv.extract(slot, what, amount, Actionable.MODULATE);
                } else if (inserted > 0) {
                    // Partially pushed — shrink the slot
                    outputInv.extract(slot, what, inserted, Actionable.MODULATE);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // Reflection helpers.

    @Unique
    private int ae2oc_getRecipeProgress() {
        try {
            Field f = ReflectionCache.getFieldHierarchy(this.getClass(), "recipeProgress");
            if (f == null) return 0;
            return f.getInt(this);
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private void ae2oc_setRecipeProgress(int value) {
        try {
            Field f = ReflectionCache.getFieldHierarchy(this.getClass(), "recipeProgress");
            if (f == null) return;
            f.setInt(this, value);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private Object ae2oc_getActiveRecipe() {
        try {
            Field f = ReflectionCache.getFieldHierarchy(this.getClass(), "activeRecipe");
            if (f == null) return null;
            return f.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private void ae2oc_setNeedRefresh(boolean value) {
        try {
            Field f = ReflectionCache.getFieldHierarchy(this.getClass(), "needRefreshRecipeState");
            if (f == null) return;
            f.setBoolean(this, value);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_forceRefreshRecipe() {
        try {
            ae2oc_setNeedRefresh(true);
            Method m = ReflectionCache.getDeclaredMethodHierarchy(this.getClass(), "updateActiveRecipe");
            if (m == null) return;
            m.invoke(this);
            ae2oc_setNeedRefresh(false);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private double ae2oc_extractPower(double amount, Actionable mode) {
        try {
            Method m = ReflectionCache.getDeclaredMethodHierarchy(this.getClass(), "extractAEPower",
                    double.class, Actionable.class);
            if (m == null) return 0;
            return (double) m.invoke(this, amount, mode);
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private void ae2oc_setChanged() {
        try {
            Method m = ReflectionCache.getMethod(this.getClass(), "setChanged");
            if (m == null) return;
            m.invoke(this);
        } catch (Exception ignored) {
        }
    }
}
