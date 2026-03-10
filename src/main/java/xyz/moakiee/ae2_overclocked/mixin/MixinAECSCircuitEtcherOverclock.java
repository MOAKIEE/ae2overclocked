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
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    @Unique
    private static final ConcurrentHashMap<String, Optional<Field>>  ae2oc_fieldCache  = new ConcurrentHashMap<>();
    @Unique
    private static final ConcurrentHashMap<String, Optional<Method>> ae2oc_methodCache = new ConcurrentHashMap<>();

    /** Guard flag to prevent re-entrant injection calls inside serverTick. */
    @Unique
    private boolean ae2oc_processing = false;

    /** recipeProgress value captured at the HEAD of serverTick, used to detect recipe completion. */
    @Unique
    private int ae2oc_prevProgress = -1;

    /** Number of extra parallel rounds to execute (excluding the one run by vanilla). */
    @Unique
    private int ae2oc_pendingParallel = 0;

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
            Field f = ae2oc_findField(this.getClass(), "recipeProgress");
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private void ae2oc_setRecipeProgress(int value) {
        try {
            Field f = ae2oc_findField(this.getClass(), "recipeProgress");
            f.setAccessible(true);
            f.setInt(this, value);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private int ae2oc_getActiveRecipeEnergyCost() {
        try {
            Field f = ae2oc_findField(this.getClass(), "activeRecipeEnergyCost");
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private Object ae2oc_getActiveRecipe() {
        try {
            Field f = ae2oc_findField(this.getClass(), "activeRecipe");
            f.setAccessible(true);
            return f.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private void ae2oc_setNeedRefresh(boolean value) {
        try {
            Field f = ae2oc_findField(this.getClass(), "needRefreshRecipeState");
            f.setAccessible(true);
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
            Method m = ae2oc_findMethod(this.getClass(), "updateActiveRecipe");
            m.setAccessible(true);
            m.invoke(this);
            ae2oc_setNeedRefresh(false);
        } catch (Exception ignored) {
        }
    }

    /** Reads energyCost from a RecipeHolder via reflection. */
    @Unique
    private int ae2oc_getActiveRecipeEnergyCostFromRecipe(Object recipeHolder) {
        try {
            Method valueMethod = recipeHolder.getClass().getMethod("value");
            Object recipeValue = valueMethod.invoke(recipeHolder);
            Method energyCostMethod = recipeValue.getClass().getMethod("energyCost");
            return (int) energyCostMethod.invoke(recipeValue);
        } catch (Exception e) {
            return ae2oc_getActiveRecipeEnergyCost();
        }
    }

    /** Returns a copy of the recipe output by calling result() on the unwrapped recipe value. */
    @Unique
    private ItemStack ae2oc_getRecipeResult(Object recipeHolder) {
        try {
            Method valueMethod = recipeHolder.getClass().getMethod("value");
            Object recipeValue = valueMethod.invoke(recipeHolder);
            // Call result() directly instead of assemble() to avoid constructing a ThreeItemStackRecipeInput.
            Method resultMethod = recipeValue.getClass().getMethod("result");
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
            Method valueMethod = recipeHolder.getClass().getMethod("value");
            Object recipeValue = valueMethod.invoke(recipeHolder);

            // Retrieve the List<SizedIngredient> of effective inputs.
            Method requiredMethod = recipeValue.getClass().getMethod("required");
            java.util.List<?> required = (java.util.List<?>) requiredMethod.invoke(recipeValue);

            // Build a ThreeItemStackRecipeInput and call findMatch to get the slot mapping.
            Method findMatchMethod = recipeValue.getClass().getMethod("findMatch",
                    Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput"));
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
            Method valueMethod = recipeHolder.getClass().getMethod("value");
            Object recipeValue = valueMethod.invoke(recipeHolder);

            Method requiredMethod = recipeValue.getClass().getMethod("required");
            java.util.List<?> required = (java.util.List<?>) requiredMethod.invoke(recipeValue);

            Method findMatchMethod = recipeValue.getClass().getMethod("findMatch",
                    Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput"));
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
            Method ofMethod = clazz.getMethod("of", ItemStack.class, ItemStack.class, ItemStack.class);
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
            Method countMethod = sized.getClass().getMethod("count");
            return (int) countMethod.invoke(sized);
        } catch (Exception e) {
            return 1;
        }
    }

    @Unique
    private double ae2oc_extractPower(double amount, Actionable mode) {
        try {
            Method m = ae2oc_findMethod(this.getClass(), "extractAEPower",
                    double.class, Actionable.class);
            return (double) m.invoke(this, amount, mode);
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private void ae2oc_setChanged() {
        try {
            Method m = this.getClass().getMethod("setChanged");
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
            Method getMC = ae2oc_findMethod(this.getClass(), "getMachineComponents");
            getMC.setAccessible(true);
            Object machineComponents = getMC.invoke(this);
            if (machineComponents == null) return;

            Method getLevel = this.getClass().getMethod("getLevel");
            Object level = getLevel.invoke(this);
            if (level == null) return;

            Field wpField = ae2oc_findField(this.getClass(), "worldPosition");
            wpField.setAccessible(true);
            Object worldPosition = wpField.get(this);

            Method getBlockState = this.getClass().getMethod("getBlockState");
            Object blockState = getBlockState.invoke(this);

            Class<?> ctxClass = Class.forName("io.github.lounode.ae2cs.common.machine.MachineContext");
            Class<?> hostClass = Class.forName("io.github.lounode.ae2cs.common.machine.IMachineHost");
            Object ctx = ctxClass.getConstructors()[0].newInstance(
                    hostClass.cast(this), level, worldPosition, blockState);

            Method onServerTick = machineComponents.getClass().getMethod("onServerTick", ctxClass);
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
            Method getMainNode = this.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(this);
            if (mainNode == null) return null;
            Method getNode = mainNode.getClass().getMethod("getNode");
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

    @Unique
    private static Field ae2oc_findField(Class<?> clazz, String name) throws NoSuchFieldException {
        String key = clazz.getName() + "#F#" + name;
        Optional<Field> opt = ae2oc_fieldCache.computeIfAbsent(key, k -> {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return Optional.empty();
        });
        if (opt.isPresent()) return opt.get();
        throw new NoSuchFieldException(name);
    }

    @Unique
    private static Method ae2oc_findMethod(Class<?> clazz, String name, Class<?>... params) throws NoSuchMethodException {
        StringBuilder sb = new StringBuilder(clazz.getName()).append("#M#").append(name);
        for (Class<?> p : params) sb.append(',').append(p.getName());
        String key = sb.toString();
        Optional<Method> opt = ae2oc_methodCache.computeIfAbsent(key, k -> {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
            return Optional.empty();
        });
        if (opt.isPresent()) return opt.get();
        throw new NoSuchMethodException(name);
    }
}
