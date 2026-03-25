package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelEngine;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;


@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberOverclock {
    @Unique
    private boolean ae2oc_processing = false;
    @Unique
    private int ae2oc_prevProcessingTime = -1;
    @Unique
    private int ae2oc_pendingParallel = 0;
    @Unique
    private boolean ae2oc_cachedIsItemOutput = true;
    @Unique
    private ItemStack ae2oc_cachedItemOutput = ItemStack.EMPTY;
    @Unique
    private FluidStack ae2oc_cachedFluidOutput = null;
    @Unique
    private double ae2oc_cachedUnitEnergy = 0;
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
                    ae2oc_instantCraft(self, node, parallelMultiplier);
                } finally {
                    this.ae2oc_processing = false;
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }
            Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
            if (ptField == null) return;
            this.ae2oc_prevProcessingTime = ptField.getInt(self);
            Method getTask = ReflectionCache.getDeclaredMethod(self.getClass(), "getTask");
            if (getTask == null) return;
            Object recipe = getTask.invoke(self);

            if (recipe != null) {
                Method getEnergy = ReflectionCache.getMethod(recipe.getClass(), "getEnergy");
                if (getEnergy != null) this.ae2oc_cachedUnitEnergy = (int) getEnergy.invoke(recipe);

                Method isItemOutput = ReflectionCache.getMethod(recipe.getClass(), "isItemOutput");
                if (isItemOutput != null) this.ae2oc_cachedIsItemOutput = (boolean) isItemOutput.invoke(recipe);

                if (this.ae2oc_cachedIsItemOutput) {
                    Method getResultItem = ReflectionCache.getMethod(recipe.getClass(), "getResultItem");
                    if (getResultItem != null)
                        this.ae2oc_cachedItemOutput = ((ItemStack) getResultItem.invoke(recipe)).copy();
                } else {
                    Method getResultFluid = ReflectionCache.getMethod(recipe.getClass(), "getResultFluid");
                    if (getResultFluid != null)
                        this.ae2oc_cachedFluidOutput = ((FluidStack) getResultFluid.invoke(recipe)).copy();
                }
                this.ae2oc_cachedRecipe = recipe;
                this.ae2oc_pendingParallel = ae2oc_calculateParallel(
                        self, node, recipe, parallelMultiplier);
            } else {
                this.ae2oc_pendingParallel = 0;
            }

        } catch (Exception e) {
        }
    }


    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_tailTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_prevProcessingTime <= 0) {
            ae2oc_resetCache();
            return;
        }

        try {
            Object self = this;

            Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
            if (ptField == null) { ae2oc_resetCache(); return; }
            int currentPT = ptField.getInt(self);

            if (currentPT == 0) {
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
        this.ae2oc_prevProcessingTime = -1;
        this.ae2oc_cachedItemOutput = ItemStack.EMPTY;
        this.ae2oc_cachedFluidOutput = null;
        this.ae2oc_cachedUnitEnergy = 0;
        this.ae2oc_cachedRecipe = null;
    }


    @Unique
    private void ae2oc_instantCraft(Object self, IGridNode node, int parallelMultiplier) throws Exception {
        ae2oc_handleDirty(self);

        Method getTask = ReflectionCache.getDeclaredMethod(self.getClass(), "getTask");
        if (getTask == null) return;
        Object recipe = getTask.invoke(self);
        if (recipe == null) return;

        Method getEnergy = ReflectionCache.getMethod(recipe.getClass(), "getEnergy");
        if (getEnergy == null) return;
        double unitEnergy = (int) getEnergy.invoke(recipe);

        Field outputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "outputInv");
        if (outputInvField == null) return;
        Object outputInv = outputInvField.get(self);

        Field inputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "inputInv");
        if (inputInvField == null) return;
        Object inputInv = inputInvField.get(self);

        Field fluidInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "fluidInv");
        if (fluidInvField == null) return;
        Object fluidInv = fluidInvField.get(self);

        Method isItemOutput = ReflectionCache.getMethod(recipe.getClass(), "isItemOutput");
        if (isItemOutput == null) return;
        boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

        int minInputCount = ae2oc_getMinInputCount(recipe, inputInv, fluidInv);
        double availableEnergy = ae2oc_getAvailableEnergy(self, node);

        int actualParallel;

        if (itemOutput) {
            Method getResultItem = ReflectionCache.getMethod(recipe.getClass(), "getResultItem");
            if (getResultItem == null) return;
            ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();

            Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                    int.class, ItemStack.class, boolean.class);
            if (insertItem == null) return;

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    parallelMultiplier, minInputCount, 1, outputItem,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertItem.invoke(outputInv, 0, stack, simulate);
                        } catch (Exception e) {
                            return stack;
                        }
                    },
                    availableEnergy, unitEnergy
            );
            actualParallel = result.actualParallel();
            if (actualParallel < 1) return;

            double totalEnergy = actualParallel * unitEnergy;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) return;
            ae2oc_consumeBatchWithRecipe(recipe, inputInv, fluidInv, actualParallel);
            ItemStack outputStack = outputItem.copy();
            int totalOutput = outputStack.getCount() * actualParallel;
            outputStack.setCount(totalOutput);
            insertItem.invoke(outputInv, 0, outputStack, false);
            if (parallelMultiplier > 1 || OverclockCardRuntime.hasOverclockCard(self)) {
                ae2oc_transferItemOutputToNetwork(node, outputInv);
            }
        } else {
            Method getResultFluid = ReflectionCache.getMethod(recipe.getClass(), "getResultFluid");
            if (getResultFluid == null) return;
            FluidStack outputFluid = (FluidStack) getResultFluid.invoke(recipe);

            int fluidOutputLimit = ae2oc_getFluidOutputLimit(fluidInv, outputFluid, parallelMultiplier);

            ParallelEngine.ParallelResult result = ParallelEngine.calculateSimple(
                    parallelMultiplier, minInputCount, 1,
                    fluidOutputLimit, availableEnergy, unitEnergy
            );
            actualParallel = result.actualParallel();
            if (actualParallel < 1) return;

            double totalEnergy = actualParallel * unitEnergy;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) return;
            ae2oc_consumeBatchWithRecipe(recipe, inputInv, fluidInv, actualParallel);

            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            int totalFluidAmount = outputFluid.getAmount() * actualParallel;
            Method addMethod = ReflectionCache.getMethod(fluidInv.getClass(), "add", int.class, AEFluidKey.class, int.class);
            if (addMethod != null) addMethod.invoke(fluidInv, 0, fluidKey, totalFluidAmount);
            if (parallelMultiplier > 1 || OverclockCardRuntime.hasOverclockCard(self)) {
                ae2oc_transferFluidOutputToNetwork(node, fluidInv);
            }
        }
        Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
        if (ptField != null) ptField.setInt(self, 0);

        Field cachedTaskField = ReflectionCache.getFieldHierarchy(self.getClass(), "cachedTask");
        if (cachedTaskField != null) cachedTaskField.set(self, null);

        Method setWorking = ReflectionCache.getMethod(self.getClass(), "setWorking", boolean.class);
        if (setWorking != null) setWorking.invoke(self, false);

        ae2oc_markForUpdate(self);
        ae2oc_saveChanges(self);
    }


    @Unique
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds) {
        if (extraRounds <= 0) return;

        try {
            Field inputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "inputInv");
            if (inputInvField == null) return;
            Object inputInv = inputInvField.get(self);

            Field fluidInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "fluidInv");
            if (fluidInvField == null) return;
            Object fluidInv = fluidInvField.get(self);
            double available = ae2oc_getAvailableEnergy(self, node);
            if (this.ae2oc_cachedUnitEnergy > 0.001) {
                int affordableRounds = (int) (available / this.ae2oc_cachedUnitEnergy);
                extraRounds = Math.min(extraRounds, affordableRounds);
            }
            if (extraRounds <= 0) return;

            double extraEnergy = extraRounds * this.ae2oc_cachedUnitEnergy;
            if (!ae2oc_tryConsumePower(self, node, extraEnergy)) return;

            if (this.ae2oc_cachedIsItemOutput) {
                Field outputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "outputInv");
                if (outputInvField == null) return;
                Object outputInv = outputInvField.get(self);

                Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                        int.class, ItemStack.class, boolean.class);
                if (insertItem == null) return;
                ItemStack totalOutput = this.ae2oc_cachedItemOutput.copy();
                totalOutput.setCount(totalOutput.getCount() * extraRounds);
                ItemStack leftover = (ItemStack) insertItem.invoke(outputInv, 0, totalOutput, false);
                int actualInserted = totalOutput.getCount() - leftover.getCount();
                int singleOutputCount = this.ae2oc_cachedItemOutput.getCount();
                int actualExtra = singleOutputCount > 0 ? actualInserted / singleOutputCount : 0;
                if (actualExtra > 0) {
                    ae2oc_consumeBatchWithRecipe(this.ae2oc_cachedRecipe, inputInv, fluidInv, actualExtra);
                }
                ae2oc_transferItemOutputToNetwork(node, outputInv);
            } else {
                AEFluidKey fluidKey = AEFluidKey.of(this.ae2oc_cachedFluidOutput);
                Method addMethod = ReflectionCache.getMethod(fluidInv.getClass(), "add", int.class, AEFluidKey.class, int.class);
                int singleFluidAmount = this.ae2oc_cachedFluidOutput.getAmount();
                int totalFluidAmount = singleFluidAmount * extraRounds;
                Method canAdd = ReflectionCache.getMethod(fluidInv.getClass(), "canAdd", int.class, AEFluidKey.class, int.class);
                int actualInserted = 0;
                if (canAdd != null && (boolean) canAdd.invoke(fluidInv, 0, fluidKey, totalFluidAmount)) {
                    if (addMethod != null) addMethod.invoke(fluidInv, 0, fluidKey, totalFluidAmount);
                    actualInserted = totalFluidAmount;
                }
                int actualExtra = singleFluidAmount > 0 ? actualInserted / singleFluidAmount : 0;
                if (actualExtra > 0) {
                    ae2oc_consumeBatchWithRecipe(this.ae2oc_cachedRecipe, inputInv, fluidInv, actualExtra);
                }
                ae2oc_transferFluidOutputToNetwork(node, fluidInv);
            }

            ae2oc_saveChanges(self);

        } catch (Exception e) {
        }
    }

    @Unique
    private void ae2oc_handleDirty(Object self) {
        try {
            Field dirtyField = ReflectionCache.getFieldHierarchy(self.getClass(), "dirty");
            if (dirtyField == null) return;
            boolean dirty = dirtyField.getBoolean(self);

            if (dirty) {
                Field levelField = ReflectionCache.getFieldHierarchy(self.getClass(), "level");
                Object level = levelField != null ? levelField.get(self) : null;

                if (level != null) {
                    Method findRecipe = ReflectionCache.getDeclaredMethod(self.getClass(), "findRecipe",
                            net.minecraft.world.level.Level.class);
                    if (findRecipe != null) {
                        Object recipe = findRecipe.invoke(self, level);
                        if (recipe == null) {
                            Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
                            if (ptField != null) ptField.setInt(self, 0);

                            Method setWorking = ReflectionCache.getMethod(self.getClass(), "setWorking", boolean.class);
                            if (setWorking != null) setWorking.invoke(self, false);

                            Field cachedTaskField = ReflectionCache.getFieldHierarchy(self.getClass(), "cachedTask");
                            if (cachedTaskField != null) cachedTaskField.set(self, null);
                        }
                    }
                }
                ae2oc_markForUpdate(self);
                dirtyField.setBoolean(self, false);
            }
        } catch (Exception ignored) {
        }
    }

    @Unique
    private int ae2oc_calculateParallel(Object self, IGridNode node, Object recipe, int cardMultiplier) {
        try {
            Field inputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "inputInv");
            if (inputInvField == null) return 1;
            Object inputInv = inputInvField.get(self);

            Field fluidInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "fluidInv");
            if (fluidInvField == null) return 1;
            Object fluidInv = fluidInvField.get(self);

            int minInputCount = ae2oc_getMinInputCount(recipe, inputInv, fluidInv);
            double availableEnergy = ae2oc_getAvailableEnergy(self, node);

            Method getEnergy = ReflectionCache.getMethod(recipe.getClass(), "getEnergy");
            if (getEnergy == null) return 1;
            double unitEnergy = (int) getEnergy.invoke(recipe);

            Method isItemOutput = ReflectionCache.getMethod(recipe.getClass(), "isItemOutput");
            if (isItemOutput == null) return 1;
            boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

            if (itemOutput) {
                Field outputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "outputInv");
                if (outputInvField == null) return 1;
                Object outputInv = outputInvField.get(self);

                Method getResultItem = ReflectionCache.getMethod(recipe.getClass(), "getResultItem");
                if (getResultItem == null) return 1;
                ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();

                Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                        int.class, ItemStack.class, boolean.class);
                if (insertItem == null) return 1;

                return ParallelEngine.calculate(
                        cardMultiplier, minInputCount, 1, outputItem,
                        (stack, simulate) -> {
                            try {
                                return (ItemStack) insertItem.invoke(outputInv, 0, stack, simulate);
                            } catch (Exception e) {
                                return stack;
                            }
                        },
                        availableEnergy, unitEnergy
                ).actualParallel();
            } else {
                Method getResultFluid = ReflectionCache.getMethod(recipe.getClass(), "getResultFluid");
                if (getResultFluid == null) return 1;
                FluidStack outputFluid = (FluidStack) getResultFluid.invoke(recipe);
                int fluidOutputLimit = ae2oc_getFluidOutputLimit(fluidInv, outputFluid, cardMultiplier);

                return ParallelEngine.calculateSimple(
                        cardMultiplier, minInputCount, 1,
                        fluidOutputLimit, availableEnergy, unitEnergy
                ).actualParallel();
            }
        } catch (Exception e) {
            return 1;
        }
    }

    @Unique
    private int ae2oc_getMinInputCount(Object recipe, Object inputInv, Object fluidInv) {
        int minCount = Integer.MAX_VALUE;
        try {
            Method getValidInputs = ReflectionCache.getMethod(recipe.getClass(), "getValidInputs");
            if (getValidInputs == null) return 1;
            @SuppressWarnings("unchecked")
            java.util.List<Object> validInputs = (java.util.List<Object>) getValidInputs.invoke(recipe);

            Method getSize = ReflectionCache.getMethod(inputInv.getClass(), "size");
            if (getSize == null) return 1;
            int invSize = (int) getSize.invoke(inputInv);

            // Cache these method lookups outside the loops
            Method getStackInSlot = ReflectionCache.getMethod(inputInv.getClass(), "getStackInSlot", int.class);
            Method getStack = ReflectionCache.getMethod(fluidInv.getClass(), "getStack", int.class);

            for (Object input : validInputs) {
                Class<?> inputClass = input.getClass();
                Method getAmount = ReflectionCache.getMethod(inputClass, "getAmount");
                if (getAmount == null) continue;
                int requiredAmount = (int) getAmount.invoke(input);
                if (requiredAmount <= 0) requiredAmount = 1;

                int available = 0;

                Method checkType = ReflectionCache.getMethod(inputClass, "checkType", Object.class);
                if (checkType == null) continue;

                if (getStackInSlot != null) {
                    for (int x = 0; x < invSize; x++) {
                        ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, x);
                        if (!stack.isEmpty()) {
                            if ((boolean) checkType.invoke(input, stack)) {
                                available += stack.getCount();
                            }
                        }
                    }
                }
                try {
                    if (getStack != null) {
                        Object gs = getStack.invoke(fluidInv, 1);
                        if (gs != null) {
                            Field whatField = ReflectionCache.getField(gs.getClass(), "what");
                            Field amountField = ReflectionCache.getField(gs.getClass(), "amount");

                            if (whatField != null && amountField != null) {
                                Object aeKey = whatField.get(gs);
                                long amount = amountField.getLong(gs);

                                if (aeKey instanceof AEFluidKey key) {
                                    FluidStack fs = key.toStack((int) amount);
                                    if ((boolean) checkType.invoke(input, fs)) {
                                        available += (int) amount / requiredAmount * requiredAmount;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                minCount = Math.min(minCount, available / requiredAmount);
            }
        } catch (Exception e) {
            return 1;
        }
        return minCount == Integer.MAX_VALUE ? 1 : minCount;
    }

    @Unique
    private static final int AE2OC_MAX_PARALLEL_LIMIT = 1_000_000_000;

    @Unique
    private int ae2oc_getFluidOutputLimit(Object fluidInv, FluidStack outputFluid, int maxParallel) {
        try {
            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            Method canAdd = ReflectionCache.getMethod(fluidInv.getClass(), "canAdd", int.class, AEFluidKey.class, int.class);
            if (canAdd == null) return 0;
            int safeMaxParallel = Math.min(maxParallel, AE2OC_MAX_PARALLEL_LIMIT);

            int lo = 0, hi = safeMaxParallel;
            while (lo < hi) {
                int mid = lo + (hi - lo + 1) / 2;
                long testAmount = (long) outputFluid.getAmount() * mid;
                if (testAmount > Integer.MAX_VALUE) {
                    hi = mid - 1;
                    continue;
                }
                if ((boolean) canAdd.invoke(fluidInv, 0, fluidKey, (int) testAmount)) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Batch-consume inputs for the given recipe. Instead of calling consumeOnce N times
     * (each doing 10+ uncached reflections), we resolve all methods once and directly
     * deduct totalRequired = singleAmount * batchCount from each input slot.
     */
    @Unique
    private void ae2oc_consumeBatchWithRecipe(Object recipe, Object inputInv, Object fluidInv, int batchCount) {
        if (batchCount <= 0) return;
        try {
            Method getValidInputs = ReflectionCache.getMethod(recipe.getClass(), "getValidInputs");
            if (getValidInputs == null) return;
            @SuppressWarnings("unchecked")
            java.util.List<Object> validInputs = (java.util.List<Object>) getValidInputs.invoke(recipe);

            Method getSize = ReflectionCache.getMethod(inputInv.getClass(), "size");
            if (getSize == null) return;
            int invSize = (int) getSize.invoke(inputInv);

            // Resolve all methods once
            Method getStackInSlot = ReflectionCache.getMethod(inputInv.getClass(), "getStackInSlot", int.class);
            Method setItemDirect = ReflectionCache.getMethod(inputInv.getClass(), "setItemDirect", int.class, ItemStack.class);
            Method getFluidStack = ReflectionCache.getMethod(fluidInv.getClass(), "getStack", int.class);
            Method setFluidStack = ReflectionCache.getMethod(fluidInv.getClass(), "setStack", int.class, GenericStack.class);
            if (getStackInSlot == null || setItemDirect == null) return;

            // Read current fluid state once
            FluidStack fluidStack = null;
            if (getFluidStack != null) {
                Object gs = getFluidStack.invoke(fluidInv, 1);
                if (gs != null) {
                    Field whatField = ReflectionCache.getField(gs.getClass(), "what");
                    Field amountField = ReflectionCache.getField(gs.getClass(), "amount");
                    if (whatField != null && amountField != null) {
                        Object aeKey = whatField.get(gs);
                        if (aeKey instanceof AEFluidKey key) {
                            long amount = amountField.getLong(gs);
                            fluidStack = key.toStack((int) amount);
                        }
                    }
                }
            }

            for (Object input : validInputs) {
                Class<?> inputClass = input.getClass();
                Method getAmount = ReflectionCache.getMethod(inputClass, "getAmount");
                Method checkType = ReflectionCache.getMethod(inputClass, "checkType", Object.class);
                if (getAmount == null || checkType == null) continue;

                int singleAmount = (int) getAmount.invoke(input);
                if (singleAmount <= 0) singleAmount = 1;
                int totalRequired = singleAmount * batchCount;

                // Deduct from item slots
                for (int x = 0; x < invSize && totalRequired > 0; x++) {
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, x);
                    if (stack.isEmpty()) continue;
                    if (!(boolean) checkType.invoke(input, stack)) continue;

                    int take = Math.min(stack.getCount(), totalRequired);
                    stack.shrink(take);
                    setItemDirect.invoke(inputInv, x, stack);
                    totalRequired -= take;
                }

                // Deduct from fluid slot if still needed
                if (totalRequired > 0 && fluidStack != null) {
                    if ((boolean) checkType.invoke(input, fluidStack)) {
                        int take = Math.min(fluidStack.getAmount(), totalRequired);
                        fluidStack.shrink(take);
                    }
                }
            }

            // Write back fluid state once
            if (fluidStack != null && setFluidStack != null) {
                if (fluidStack.isEmpty()) {
                    setFluidStack.invoke(fluidInv, 1, null);
                } else {
                    setFluidStack.invoke(fluidInv, 1, new GenericStack(
                            Objects.requireNonNull(AEFluidKey.of(fluidStack)), fluidStack.getAmount()));
                }
            }
        } catch (Exception ignored) {
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


    @Unique
    private void ae2oc_transferItemOutputToNetwork(IGridNode node, Object outputInv) {
        try {
            var grid = node.getGrid();
            if (grid == null) return;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;

            Method getStackInSlot = ReflectionCache.getMethod(outputInv.getClass(), "getStackInSlot", int.class);
            Method setItemDirect = ReflectionCache.getMethod(outputInv.getClass(), "setItemDirect", int.class, ItemStack.class);
            if (getStackInSlot == null || setItemDirect == null) return;
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
                setItemDirect.invoke(outputInv, 0, ItemStack.EMPTY);
            } else if (inserted > 0) {
                stack.shrink((int) inserted);
                setItemDirect.invoke(outputInv, 0, stack);
            }

        } catch (Exception e) {
        }
    }


    @Unique
    private void ae2oc_transferFluidOutputToNetwork(IGridNode node, Object fluidInv) {
        try {
            var grid = node.getGrid();
            if (grid == null) return;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;

            Method getStack = ReflectionCache.getMethod(fluidInv.getClass(), "getStack", int.class);
            Method setStack = ReflectionCache.getMethod(fluidInv.getClass(), "setStack", int.class, GenericStack.class);
            if (getStack == null || setStack == null) return;
            Object gs = getStack.invoke(fluidInv, 0);
            if (gs == null) return;

            Field whatField = ReflectionCache.getField(gs.getClass(), "what");
            Field amountField = ReflectionCache.getField(gs.getClass(), "amount");
            if (whatField == null || amountField == null) return;
            Object aeKey = whatField.get(gs);
            long amount = amountField.getLong(gs);

            if (!(aeKey instanceof AEFluidKey fluidKey) || amount <= 0) return;

            long inserted = storageService.getInventory().insert(
                    fluidKey,
                    amount,
                    Actionable.MODULATE,
                    IActionSource.empty()
            );

            if (inserted >= amount) {
                setStack.invoke(fluidInv, 0, null);
            } else if (inserted > 0) {
                setStack.invoke(fluidInv, 0, new GenericStack(fluidKey, amount - inserted));
            }

        } catch (Exception e) {
        }
    }
}
