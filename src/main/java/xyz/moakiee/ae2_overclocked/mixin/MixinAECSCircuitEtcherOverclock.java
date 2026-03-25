package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.util.inv.AppEngInternalInventory;
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

/**
 * Adds Overclock Card and Parallel Card support to AE2CS CircuitEtcherBlockEntity.
 * <p>
 * CircuitEtcherBlockEntity implements IUpgradeableObject and exposes getInputInv()/getOutputInv().
 * The fields recipeProgress / activeRecipeEnergyCost / needRefreshRecipeState are private and
 * accessed via reflection. Energy is drained directly via extractAEPower(double, Actionable)
 * from the machine's own internal buffer.
 */
@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity", remap = false)
public abstract class MixinAECSCircuitEtcherOverclock implements IUpgradeableObject {

    // ── Shadow ────────────────────────────────────────────────────────────────

    @Shadow
    public abstract AppEngInternalInventory getInputInv();

    @Shadow
    public abstract AppEngInternalInventory getOutputInv();

    @Shadow
    public abstract IUpgradeInventory getUpgrades();

    // ── Unique state ─────────────────────────────────────────────────────────

    /** Guard flag to prevent re-entrant injection calls inside serverTick. */
    @Unique
    private boolean ae2oc_processing = false;

    /** recipeProgress value captured at the HEAD of serverTick, used to detect recipe completion. */
    @Unique
    private int ae2oc_prevProgress = -1;

    /** Number of extra parallel rounds to execute (excluding the one run by vanilla). */
    @Unique
    private int ae2oc_pendingParallel = 0;

    /** Tick accumulator for configurable overclock interval. */
    @Unique
    private int ae2oc_tickAccumulator = 0;

    /** Cached active recipe holder, used by the TAIL injection to produce extra outputs. */
    @Unique
    private Object ae2oc_cachedRecipe = null;

    // ── serverTick HEAD: overclock / parallel pre-calculation ────────────────

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

                // Set processing flag BEFORE calling runSuperServerTick to prevent
                // infinite recursion: invoke(this) dispatches to the mixin-injected
                // serverTick which would re-enter ae2oc_headTick.
                ae2oc_processing = true;
                try {
                    // Must run super.serverTick() first so EnergyComponent pulls power from the ME network.
                    ae2oc_runSuperServerTick();
                    ae2oc_instantCraft(Math.max(parallelMultiplier, 1));
                } finally {
                    ae2oc_processing = false;
                }
                ci.cancel();
                return;
            }

            // Parallel mode: snapshot current progress and recipe, let vanilla run, then check in TAIL.
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

    // ── serverTick TAIL: parallel extra-output injection ─────────────────────

    @Inject(method = "serverTick", at = @At("RETURN"), require = 0)
    private void ae2oc_tailTick(CallbackInfo ci) {
        if (ae2oc_pendingParallel <= 1 || ae2oc_prevProgress <= 0 || ae2oc_cachedRecipe == null) {
            ae2oc_resetCache();
            return;
        }

        try {
            int currentProgress = ae2oc_getRecipeProgress();
            // Vanilla completed one recipe this tick (progress reset to 0).
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

    // ── Internal helpers ──────────────────────────────────────────────────────

    @Unique
    private void ae2oc_resetCache() {
        ae2oc_prevProgress = -1;
        ae2oc_pendingParallel = 0;
        ae2oc_cachedRecipe = null;
    }

    /**
     * Overclock mode: immediately complete up to maxRounds crafting cycles
     * (drain energy + consume inputs + insert output).
     */
    @Unique
    private void ae2oc_instantCraft(int maxRounds) throws Exception {
        if (maxRounds <= 0) return;

        // Ensure recipe state is up to date.
        ae2oc_forceRefreshRecipe();

        Object recipe = ae2oc_getActiveRecipe();
        if (recipe == null) return;

        int energyCost = ae2oc_getActiveRecipeEnergyCost();
        if (energyCost <= 0) return;

        // Flush existing outputs to ME network first to free up local slot space
        ae2oc_flushOutputToMENetwork();

        int crafted = 0;
        for (int i = 0; i < maxRounds; i++) {
            // Check inputs
            if (!ae2oc_canConsumeInputs(recipe)) break;
            // Check output space
            ItemStack outputItem = ae2oc_getRecipeResult(recipe);
            if (outputItem == null || outputItem.isEmpty()) break;
            // Try local slot first, then ME network fallback
            if (!getOutputInv().insertItem(0, outputItem, true).isEmpty()
                    && !ae2oc_canOutputToMENetwork(outputItem)) break;
            // Drain energy
            double extracted = ae2oc_extractPower(energyCost, Actionable.SIMULATE);
            if (extracted < energyCost - 0.01) break;
            ae2oc_extractPower(energyCost, Actionable.MODULATE);
            // Consume inputs and write output
            ae2oc_consumeInputs(recipe);
            // Insert to local slot; if full, push to ME network
            ItemStack leftover = getOutputInv().insertItem(0, outputItem, false);
            if (!leftover.isEmpty()) {
                ae2oc_outputToMENetwork(leftover);
            }
            crafted++;
            // Refresh recipe state after consuming ingredients
            ae2oc_setNeedRefresh(true);
            ae2oc_forceRefreshRecipe();
            recipe = ae2oc_getActiveRecipe();
            if (recipe == null) break;
            energyCost = ae2oc_getActiveRecipeEnergyCost();
        }

        if (crafted > 0) {
            ae2oc_setRecipeProgress(0);
            // Final flush: push all remaining local output to ME network
            ae2oc_flushOutputToMENetwork();
            ae2oc_setChanged();
        }
    }

    /**
     * Parallel mode: produce extraRounds additional outputs without re-running vanilla logic.
     * Drains energy and consumes inputs directly for each extra round.
     */
    @Unique
    private void ae2oc_doExtraOutputs(int extraRounds, Object recipe) throws Exception {
        if (extraRounds <= 0 || recipe == null) return;

        int energyCost = ae2oc_getActiveRecipeEnergyCostFromRecipe(recipe);
        if (energyCost <= 0) return;

        // Flush existing outputs to ME network first
        ae2oc_flushOutputToMENetwork();

        int crafted = 0;
        for (int i = 0; i < extraRounds; i++) {
            if (!ae2oc_canConsumeInputs(recipe)) break;
            ItemStack outputItem = ae2oc_getRecipeResult(recipe);
            if (outputItem == null || outputItem.isEmpty()) break;
            if (!getOutputInv().insertItem(0, outputItem, true).isEmpty()
                    && !ae2oc_canOutputToMENetwork(outputItem)) break;
            double extracted = ae2oc_extractPower(energyCost, Actionable.SIMULATE);
            if (extracted < energyCost - 0.01) break;
            ae2oc_extractPower(energyCost, Actionable.MODULATE);
            ae2oc_consumeInputs(recipe);
            ItemStack leftover = getOutputInv().insertItem(0, outputItem, false);
            if (!leftover.isEmpty()) {
                ae2oc_outputToMENetwork(leftover);
            }
            crafted++;
        }

        if (crafted > 0) {
            ae2oc_setNeedRefresh(true);
            ae2oc_flushOutputToMENetwork();
            ae2oc_setChanged();
        }
    }

    @Unique
    private int ae2oc_calculateParallel(int cardMultiplier) {
        try {
            Object recipe = ae2oc_getActiveRecipe();
            if (recipe == null) return 0;

            int energyCost = ae2oc_getActiveRecipeEnergyCostFromRecipe(recipe);
            if (energyCost <= 0) return 1;

            double available = ae2oc_extractPower(Double.MAX_VALUE, Actionable.SIMULATE);
            int energyLimit = (energyCost > 0) ? (int) (available / energyCost) : cardMultiplier;

            return Math.max(0, Math.min(cardMultiplier, energyLimit));
        } catch (Exception e) {
            return 1;
        }
    }

    // ── Reflection helpers: access private fields ─────────────────────────────

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
    private int ae2oc_getActiveRecipeEnergyCost() {
        try {
            Field f = ReflectionCache.getFieldHierarchy(this.getClass(), "activeRecipeEnergyCost");
            if (f == null) return 0;
            return f.getInt(this);
        } catch (Exception e) {
            return 0;
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

    /**
     * Forces one recipe state refresh by invoking the private updateActiveRecipe() method.
     */
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

    /** Reads energyCost from a RecipeHolder via reflection. */
    @Unique
    private int ae2oc_getActiveRecipeEnergyCostFromRecipe(Object recipeHolder) {
        try {
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return ae2oc_getActiveRecipeEnergyCost();
            Object recipeValue = valueMethod.invoke(recipeHolder);
            Method energyCostMethod = ReflectionCache.getMethod(recipeValue.getClass(), "energyCost");
            if (energyCostMethod == null) return ae2oc_getActiveRecipeEnergyCost();
            return (int) energyCostMethod.invoke(recipeValue);
        } catch (Exception e) {
            return ae2oc_getActiveRecipeEnergyCost();
        }
    }

    /** Returns a copy of the recipe output by calling result() on the unwrapped recipe value. */
    @Unique
    private ItemStack ae2oc_getRecipeResult(Object recipeHolder) {
        try {
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return ItemStack.EMPTY;
            Object recipeValue = valueMethod.invoke(recipeHolder);
            // Call result() directly instead of assemble() to avoid constructing a ThreeItemStackRecipeInput.
            Method resultMethod = ReflectionCache.getMethod(recipeValue.getClass(), "result");
            if (resultMethod == null) return ItemStack.EMPTY;
            ItemStack template = (ItemStack) resultMethod.invoke(recipeValue);
            return template == null ? ItemStack.EMPTY : template.copy();
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Simulates input consumption to verify that the current input slots satisfy the recipe.
     * Uses findMatch() + required() from the recipe value.
     */
    @Unique
    private boolean ae2oc_canConsumeInputs(Object recipeHolder) {
        try {
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return false;
            Object recipeValue = valueMethod.invoke(recipeHolder);

            // Retrieve the List<SizedIngredient> of effective inputs.
            Method requiredMethod = ReflectionCache.getMethod(recipeValue.getClass(), "required");
            if (requiredMethod == null) return false;
            java.util.List<?> required = (java.util.List<?>) requiredMethod.invoke(recipeValue);

            // Build a ThreeItemStackRecipeInput and call findMatch to get the slot mapping.
            Method findMatchMethod = ReflectionCache.getMethod(recipeValue.getClass(), "findMatch",
                    Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput"));
            if (findMatchMethod == null) return false;
            Object input = ae2oc_buildThreeInput();
            if (input == null) return false;
            Object match = findMatchMethod.invoke(recipeValue, input);
            if (match == null) return false;

            int[] matchArr = (int[]) match;
            for (int i = 0; i < required.size(); i++) {
                Object sized = required.get(i);
                int slot = matchArr[i];
                int count = ae2oc_getSizedIngredientCount(sized);
                ItemStack extracted = getInputInv().extractItem(slot, count, true);
                if (extracted.isEmpty() || extracted.getCount() < count) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Consume inputs (actual extraction). */
    @Unique
    private void ae2oc_consumeInputs(Object recipeHolder) {
        try {
            Method valueMethod = ReflectionCache.getMethod(recipeHolder.getClass(), "value");
            if (valueMethod == null) return;
            Object recipeValue = valueMethod.invoke(recipeHolder);

            Method requiredMethod = ReflectionCache.getMethod(recipeValue.getClass(), "required");
            if (requiredMethod == null) return;
            java.util.List<?> required = (java.util.List<?>) requiredMethod.invoke(recipeValue);

            Method findMatchMethod = ReflectionCache.getMethod(recipeValue.getClass(), "findMatch",
                    Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput"));
            if (findMatchMethod == null) return;
            Object input = ae2oc_buildThreeInput();
            if (input == null) return;
            Object match = findMatchMethod.invoke(recipeValue, input);
            if (match == null) return;

            int[] matchArr = (int[]) match;
            for (int i = 0; i < required.size(); i++) {
                Object sized = required.get(i);
                int slot = matchArr[i];
                int count = ae2oc_getSizedIngredientCount(sized);
                getInputInv().extractItem(slot, count, false);
            }
        } catch (Exception ignored) {
        }
    }

    /** Build a ThreeItemStackRecipeInput. */
    @Unique
    private Object ae2oc_buildThreeInput() {
        try {
            Class<?> clazz = Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput");
            Method ofMethod = ReflectionCache.getMethod(clazz, "of", ItemStack.class, ItemStack.class, ItemStack.class);
            if (ofMethod == null) return null;
            return ofMethod.invoke(null,
                    getInputInv().getStackInSlot(0),
                    getInputInv().getStackInSlot(1),
                    getInputInv().getStackInSlot(2));
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private int ae2oc_getSizedIngredientCount(Object sized) {
        try {
            Method countMethod = ReflectionCache.getMethod(sized.getClass(), "count");
            if (countMethod == null) return 1;
            return (int) countMethod.invoke(sized);
        } catch (Exception e) {
            return 1;
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

    /**
     * Directly invoke machineComponents.onServerTick() to let EnergyComponent pull power
     * from the ME network and SideConfigComponent push outputs.
     * <p>
     * We must NOT use reflective serverTick() because Method.invoke uses virtual dispatch,
     * which would call the mixin-injected serverTick() and either cause infinite recursion
     * or execute the full original method body unintentionally.
     */
    @Unique
    private void ae2oc_runSuperServerTick() {
        try {
            Method getMC = ReflectionCache.getDeclaredMethodHierarchy(this.getClass(), "getMachineComponents");
            if (getMC == null) return;
            Object machineComponents = getMC.invoke(this);
            if (machineComponents == null) return;

            Method getLevel = ReflectionCache.getMethod(this.getClass(), "getLevel");
            if (getLevel == null) return;
            Object level = getLevel.invoke(this);
            if (level == null) return;

            Field wpField = ReflectionCache.getFieldHierarchy(this.getClass(), "worldPosition");
            if (wpField == null) return;
            Object worldPosition = wpField.get(this);

            Method getBlockState = ReflectionCache.getMethod(this.getClass(), "getBlockState");
            if (getBlockState == null) return;
            Object blockState = getBlockState.invoke(this);

            Class<?> ctxClass = Class.forName("io.github.lounode.ae2cs.common.machine.MachineContext");
            Class<?> hostClass = Class.forName("io.github.lounode.ae2cs.common.machine.IMachineHost");
            Object ctx = ctxClass.getConstructors()[0].newInstance(
                    hostClass.cast(this), level, worldPosition, blockState);

            Method onServerTick = ReflectionCache.getMethod(machineComponents.getClass(), "onServerTick", ctxClass);
            if (onServerTick == null) return;
            onServerTick.invoke(machineComponents, ctx);
        } catch (Exception ignored) {
        }
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
     * Check if the ME network can accept the given output stack (simulate).
     */
    @Unique
    private boolean ae2oc_canOutputToMENetwork(ItemStack outputStack) {
        try {
            IGridNode gridNode = ae2oc_getGridNode();
            if (gridNode == null || gridNode.getGrid() == null) return false;
            IStorageService storage = gridNode.getGrid().getService(IStorageService.class);
            if (storage == null) return false;
            AEItemKey key = AEItemKey.of(outputStack);
            if (key == null) return false;
            long accepted = storage.getInventory().insert(key, outputStack.getCount(), Actionable.SIMULATE, IActionSource.empty());
            return accepted >= outputStack.getCount();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Push a stack directly into the ME network storage.
     */
    @Unique
    private void ae2oc_outputToMENetwork(ItemStack outputStack) {
        try {
            IGridNode gridNode = ae2oc_getGridNode();
            if (gridNode == null || gridNode.getGrid() == null) return;
            IStorageService storage = gridNode.getGrid().getService(IStorageService.class);
            if (storage == null) return;
            AEItemKey key = AEItemKey.of(outputStack);
            if (key == null) return;
            storage.getInventory().insert(key, outputStack.getCount(), Actionable.MODULATE, IActionSource.empty());
        } catch (Exception ignored) {
        }
    }

    /**
     * Flush all items from the local output slot(s) into the ME network.
     * CircuitEtcher has a single output slot (index 0).
     */
    @Unique
    private void ae2oc_flushOutputToMENetwork() {
        try {
            IGridNode gridNode = ae2oc_getGridNode();
            if (gridNode == null || gridNode.getGrid() == null) return;
            IStorageService storage = gridNode.getGrid().getService(IStorageService.class);
            if (storage == null) return;

            ItemStack stack = getOutputInv().getStackInSlot(0);
            if (stack.isEmpty()) return;

            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return;

            long inserted = storage.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, IActionSource.empty());
            if (inserted >= stack.getCount()) {
                getOutputInv().setItemDirect(0, ItemStack.EMPTY);
            } else if (inserted > 0) {
                stack.shrink((int) inserted);
            }
        } catch (Exception ignored) {
        }
    }
}
