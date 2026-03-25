package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelEngine;

@Mixin(value = InscriberBlockEntity.class, remap = false)
public abstract class MixinInscriberOverclock {

    @Unique
    private static final double AE2OC_INSCRIBER_RECIPE_ENERGY = 2000.0;

    @Unique
    private static final double AE2OC_ENERGY_EPS = 0.01;

    @Unique
    private int ae2ocPendingParallel = 0;

    @Unique
    private int ae2oc_tickAccumulator = 0;

    @Shadow
    private boolean smash;

    @Shadow
    private int finalStep;

    @Shadow
    @Final
    private AppEngInternalInventory topItemHandler;

    @Shadow
    @Final
    private AppEngInternalInventory bottomItemHandler;

    @Shadow
    @Final
    private AppEngInternalInventory sideItemHandler;

    @Shadow
    public abstract InscriberRecipe getTask();

    @Shadow
    protected abstract void setSmash(boolean smash);

    @Invoker("setProcessingTime")
    protected abstract void ae2oc$setProcessingTime(int processingTime);

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_parallelOverclockTick(
            IGridNode node,
            int ticksSinceLastCall,
            CallbackInfoReturnable<TickRateModulation> cir
    ) {
        InscriberBlockEntity self = (InscriberBlockEntity) (Object) this;

        boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(self);
        int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);
        if (!hasOverclock && parallelMultiplier <= 1) {
            return;
        }

        if (this.smash) {
            ae2oc_handleSmashAnimation(self);
            cir.setReturnValue(TickRateModulation.URGENT);
            return;
        }

        InscriberRecipe recipe = this.getTask();
        if (recipe == null) {
            return;
        }

        int actualParallel = ae2oc_calculateParallel(self, node, recipe, parallelMultiplier);
        if (actualParallel < 1) {
            return;
        }

        if (hasOverclock) {
            // Gate new craft cycles by configurable tick interval.
            ae2oc_tickAccumulator += ticksSinceLastCall;
            if (ae2oc_tickAccumulator < Ae2OcConfig.getOverclockIntervalTicks()) {
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }
            ae2oc_tickAccumulator = 0;

            double totalEnergy = actualParallel * AE2OC_INSCRIBER_RECIPE_ENERGY;
            if (!ae2oc_tryConsumePower(self, node, totalEnergy)) {
                // Fall back to normal parallel flow when instant overclock energy is not available.
                this.ae2ocPendingParallel = actualParallel;
                return;
            }

            this.ae2ocPendingParallel = actualParallel;
            this.setSmash(true);
            this.finalStep = 0;
            self.markForUpdate();
            cir.setReturnValue(TickRateModulation.URGENT);
            return;
        }

        this.ae2ocPendingParallel = actualParallel;
    }

    @Unique
    private void ae2oc_handleSmashAnimation(InscriberBlockEntity self) {
        this.finalStep += 4;
        if (this.finalStep >= 8 && this.finalStep < 16) {
            int parallel = Math.max(this.ae2ocPendingParallel, 1);
            ae2oc_finishCraftParallel(self, parallel);
            this.ae2ocPendingParallel = 0;
            this.finalStep = 16;
        }

        if (this.finalStep >= 16) {
            this.finalStep = 0;
            this.setSmash(false);
            self.markForUpdate();
        }
    }

    @Unique
    private int ae2oc_calculateParallel(
            InscriberBlockEntity self,
            IGridNode node,
            InscriberRecipe recipe,
            int cardMultiplier
    ) {
        ItemStack inputStack = this.sideItemHandler.getStackInSlot(0);
        int inputCount = inputStack.getCount();
        int recipeInputCount = 1;

        if (recipe.getProcessType() == InscriberProcessType.PRESS) {
            ItemStack topStack = this.topItemHandler.getStackInSlot(0);
            ItemStack bottomStack = this.bottomItemHandler.getStackInSlot(0);
            int topCount = topStack.isEmpty() ? Integer.MAX_VALUE : topStack.getCount();
            int bottomCount = bottomStack.isEmpty() ? Integer.MAX_VALUE : bottomStack.getCount();
            cardMultiplier = Math.min(cardMultiplier, Math.min(topCount, bottomCount));
        }

        ItemStack outputStack = recipe.getResultItem().copy();
        double availableEnergy = ae2oc_getAvailableEnergy(self, node);
        if (node.getGrid() == null) {
            // Without an ME grid, external FE can still sustain crafting over time.
            // Do not hard-cap parallel by instantaneous internal AE buffer here.
            availableEnergy = Double.MAX_VALUE;
        }

        ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                cardMultiplier,
                inputCount,
                recipeInputCount,
                outputStack,
                (stack, simulate) -> this.sideItemHandler.insertItem(1, stack, simulate),
                availableEnergy,
                AE2OC_INSCRIBER_RECIPE_ENERGY
        );

        return result.actualParallel();
    }

    @Unique
    private void ae2oc_finishCraftParallel(InscriberBlockEntity self, int parallel) {
        InscriberRecipe recipe = this.getTask();
        if (recipe == null || parallel <= 0) {
            return;
        }

        ItemStack singleOutput = recipe.getResultItem().copy();
        int singleOutputCount = Math.max(singleOutput.getCount(), 1);
        long totalOutputLong = (long) singleOutputCount * parallel;
        int totalOutput = (int) Math.min(totalOutputLong, Integer.MAX_VALUE);

        ItemStack outputCopy = singleOutput.copy();
        outputCopy.setCount(totalOutput);

        ItemStack leftover = this.sideItemHandler.insertItem(1, outputCopy, false);
        int actualInserted = totalOutput - leftover.getCount();
        int actualParallel = actualInserted / singleOutputCount;

        if (actualParallel > 0) {
            this.ae2oc$setProcessingTime(0);

            if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                this.topItemHandler.extractItem(0, actualParallel, false);
                this.bottomItemHandler.extractItem(0, actualParallel, false);
            }

            this.sideItemHandler.extractItem(0, actualParallel, false);
        }

        // Always transfer: this method is only called when overclock or parallel card is active.
        ae2oc_transferOutputToNetwork(self);

        self.saveChanges();
    }

    @Unique
    private void ae2oc_transferOutputToNetwork(InscriberBlockEntity self) {
        IManagedGridNode mainNode = self.getMainNode();
        if (mainNode == null) {
            return;
        }

        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return;
        }

        IStorageService storageService = grid.getStorageService();
        if (storageService == null) {
            return;
        }

        ItemStack stack = this.sideItemHandler.getStackInSlot(1);
        if (stack.isEmpty()) {
            return;
        }

        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return;
        }

        IActionSource actionSource = ae2oc_getActionSource(mainNode);
        long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, actionSource);
        if (inserted > 0) {
            int toExtract = (int) Math.min(inserted, Integer.MAX_VALUE);
            this.sideItemHandler.extractItem(1, toExtract, false);
        }
    }

    @Unique
    private IActionSource ae2oc_getActionSource(IManagedGridNode mainNode) {
        IGridNode node = mainNode.getNode();
        if (node != null && node.getOwner() instanceof IActionHost actionHost) {
            return IActionSource.ofMachine(actionHost);
        }
        return IActionSource.empty();
    }

    @Unique
    private double ae2oc_getAvailableEnergy(InscriberBlockEntity self, IGridNode node) {
        double internal = self.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        double network = 0;
        IGrid grid = node.getGrid();
        if (grid != null) {
            IEnergyService energyService = grid.getEnergyService();
            if (energyService != null) {
                network = energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }
        }
        return internal + network;
    }

    @Unique
    private boolean ae2oc_tryConsumePower(InscriberBlockEntity self, IGridNode node, double powerNeeded) {
        double internalAvailable = self.extractAEPower(powerNeeded, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        double remaining = powerNeeded - internalAvailable;
        if (remaining <= AE2OC_ENERGY_EPS) {
            self.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
            return true;
        }

        IGrid grid = node.getGrid();
        if (grid == null) {
            return false;
        }

        IEnergyService energyService = grid.getEnergyService();
        if (energyService == null) {
            return false;
        }

        double networkAvailable = energyService.extractAEPower(remaining, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        if (networkAvailable + AE2OC_ENERGY_EPS < remaining) {
            return false;
        }

        if (internalAvailable > AE2OC_ENERGY_EPS) {
            self.extractAEPower(internalAvailable, Actionable.MODULATE, PowerMultiplier.CONFIG);
        }
        energyService.extractAEPower(remaining, Actionable.MODULATE, PowerMultiplier.CONFIG);
        return true;
    }
}
