package moakiee.ae2oc.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.api.networking.energy.IEnergySource;
import moakiee.ModItems;
import moakiee.Ae2OcConfig;
import moakiee.support.ParallelCardRuntime;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.api.PerformanceBudget;
import moakiee.ae2oc.core.execution.BatchProcessor;
import moakiee.ae2oc.core.execution.ProcessingState;
import moakiee.ae2oc.core.execution.RetryBackoff;
import moakiee.ae2oc.core.execution.SlotTransaction;
import moakiee.ae2oc.core.planning.BatchPlanner;
import moakiee.ae2oc.core.planning.MachineBudget;
import moakiee.ae2oc.core.observability.ProcessingMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** AE2 resource and energy ports for the shared processing state machine. */
public class MachineProcessorAdapter {
    private final AEBaseBlockEntity host;
    private final IUpgradeableObject upgrades;
    private final InternalInventory inventory;
    private final Supplier<RecipeBatch> recipeSource;
    private final int budgetShares;
    private final int outputSlot;
    private final BatchProcessor<AEKey> processor = new BatchProcessor<>();
    private ResourceAmount<AEKey> completedPrimaryOutput;
    private RetryBackoff retryBackoff;
    private long retryConfigRevision = Long.MIN_VALUE;

    public MachineProcessorAdapter(AEBaseBlockEntity host, IUpgradeableObject upgrades,
                            InternalInventory inventory, Supplier<RecipeBatch> recipeSource) {
        this(host, upgrades, inventory, recipeSource, 1, 3);
    }

    public MachineProcessorAdapter(AEBaseBlockEntity host, IUpgradeableObject upgrades,
                            InternalInventory inventory, Supplier<RecipeBatch> recipeSource, int budgetShares, int outputSlot) {
        this.host = host;
        this.upgrades = upgrades;
        this.inventory = inventory;
        this.recipeSource = recipeSource;
        if (budgetShares < 1 || budgetShares > 64) throw new IllegalArgumentException("Invalid machine budget shares");
        this.budgetShares = budgetShares;
        this.outputSlot = outputSlot;
        if (host instanceof MachineItemContents.Owner owner) owner.ae2oc$itemContents().register(processor, () -> {
            long total = 0;
            for (var resource : processor.ownedResources()) if (resource.key() instanceof AEItemKey)
                total = moakiee.ae2oc.core.quantity.SaturatedMath.addNonNegative(total, resource.amount());
            return total;
        });
    }

    public boolean hasPendingBatch() { return !processor.isIdle(); }

    /** Current immutable batch snapshot, or null while idle. Used for client-visible progress sync. */
    public ProcessingState<AEKey> processing() { return processor.snapshot(); }

    /** One-shot visual event emitted when processing, rather than output draining, completes. */
    public ResourceAmount<AEKey> pollCompletedPrimaryOutput() {
        var result = completedPrimaryOutput;
        completedPrimaryOutput = null;
        return result;
    }

    /** Null delegates a completely unmodified machine to upstream. */
    public TickRateModulation tick() {
        var profile = UpgradeProfileCache.of(host);
        boolean overclock = profile.overclock();
        int multiplier = profile.parallelLimit();
        if (processor.snapshot() == null && !overclock && multiplier <= 1) return null;
        ProcessingMetrics.tickCalled();
        // Upstream machines never gate on the main node being active: they keep draining their own
        // internal buffer while disconnected and only stall once nothing is left to pay with. Mirror
        // that contract so a machine that still owns a paid batch keeps progressing (or reports a slow
        // retry) instead of freezing, and so the grid energy service stays an optional top-up rather
        // than a precondition for work. The grid is also only ever an output target, never a requirement.
        if (host.getLevel() == null) return TickRateModulation.IDLE;
        long now = host.getLevel().getGameTime();
        PerformanceBudget budget = Ae2OcConfig.performance();
        RetryBackoff retries = retryBackoff(budget);
        if (!retries.ready(now)) {
            ProcessingMetrics.backoffSkipped();
            return TickRateModulation.SLOWER;
        }
        boolean progressed = false;
        if (processor.snapshot() == null) {
            progressed = reserve(overclock, multiplier, budget);
            if (!progressed) return blocked(now, retries);
        }
        var before = processor.snapshot();
        try {
            var beforeAdvance = processor.snapshot();
            progressed |= processor.advance(this::extractEnergy);
            var afterAdvance = processor.snapshot();
            if (beforeAdvance != null && !beforeAdvance.finished()
                    && afterAdvance != null && afterAdvance.finished()
                    && !afterAdvance.outputs().isEmpty()) {
                completedPrimaryOutput = afterAdvance.outputs().get(0);
            }
            progressed |= processor.drain(MachineBudget.share(budget.transferAmount(), budgetShares),
                    MachineBudget.share(budget.transferKeys(), budgetShares), this::insertOutput);
        } finally {
            // Includes partial energy payments and each accepted output, even after a later failure.
            if (processor.snapshot() != before) host.saveChanges();
        }
        if (!progressed) return blocked(now, retries);
        retries.reset();
        return TickRateModulation.URGENT;
    }

    private TickRateModulation blocked(long now, RetryBackoff retries) {
        ProcessingMetrics.blocked();
        retries.blocked(now);
        return TickRateModulation.SLOWER;
    }

    private RetryBackoff retryBackoff(PerformanceBudget budget) {
        long revision = Ae2OcConfig.revision();
        if (retryBackoff == null || retryConfigRevision != revision) {
            retryBackoff = new RetryBackoff(budget.retryMinTicks(), budget.retryMaxTicks());
            retryConfigRevision = revision;
        }
        return retryBackoff;
    }

    private boolean reserve(boolean overclock, int multiplier, PerformanceBudget budget) {
        RecipeBatch recipe = recipeSource.get();
        if (recipe == null || recipe.outputs().isEmpty()) return false;
        long materialLimit = Long.MAX_VALUE;
        for (var debit : recipe.inputs()) {
            if (!java.util.Objects.equals(debit.before(), debit.slot().read())) return false;
            if (debit.consumed() > 0) materialLimit = Math.min(materialLimit, debit.before().amount() / debit.consumed());
        }
        long outputAmount = 0;
        for (var output : recipe.outputs()) outputAmount = moakiee.ae2oc.core.quantity.SaturatedMath.addNonNegative(outputAmount, output.amount());
        if (outputAmount == 0) return false;
        var limit = multiplier == Integer.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(multiplier);
        long outputLimit = MachineBudget.share(budget.pendingOutputAmount(), budgetShares) / outputAmount;
        var plan = BatchPlanner.plan(limit, materialLimit, outputLimit,
                MachineBudget.share(budget.recipeOperations(), budgetShares),
                simulateEnergy(), recipe.unitEnergy());
        long count = plan.operations();
        if (count == 0) return false;
        List<ResourceAmount<AEKey>> inputs = new ArrayList<>();
        for (var debit : recipe.inputs()) if (debit.consumed() > 0) {
            inputs.add(new ResourceAmount<>(debit.before().key(), count * debit.consumed()));
        }
        var outputs = recipe.outputs().stream().map(output -> new ResourceAmount<>(output.key(), count * output.amount())).toList();
        var state = new ProcessingState<AEKey>(recipe.id(), inputs, outputs, plan.energyCost(), 0,
                overclock ? Ae2OcConfig.getOverclockCardProcessTicks() : recipe.normalTicks());
        SlotTransaction.apply(recipe.inputs().stream().filter(debit -> debit.consumed() > 0)
                .map(debit -> new SlotTransaction.Write<>(debit.slot()::write, debit.before(),
                        new ResourceAmount<>(debit.before().key(), debit.before().amount() - count * debit.consumed())))
                .toList());
        processor.begin(state);
        ProcessingMetrics.batchReserved(count);
        host.saveChanges();
        return true;
    }
    private double simulateEnergy() {
        double internal = ((IEnergySource) host).extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        var grid = ((IGridConnectedBlockEntity) host).getMainNode().getGrid();
        double network = grid == null ? 0 : grid.getEnergyService().extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        return Math.min(Double.MAX_VALUE, internal + network);
    }

    private double extractEnergy(double requested) {
        // One external extraction per advance makes a partial payment an explicit save boundary.
        double internal = ((IEnergySource) host).extractAEPower(requested, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        if (internal > 0) return ((IEnergySource) host).extractAEPower(Math.min(requested, internal), Actionable.MODULATE, PowerMultiplier.CONFIG);
        var grid = ((IGridConnectedBlockEntity) host).getMainNode().getGrid();
        return grid == null ? 0 : grid.getEnergyService().extractAEPower(requested, Actionable.MODULATE, PowerMultiplier.CONFIG);
    }

    protected long insertLocalOutput(ResourceAmount<AEKey> resource) {
        if (resource.key() instanceof AEItemKey item) {
            int count = (int) Math.min(resource.amount(), item.toStack().getMaxStackSize());
            return count - inventory.insertItem(outputSlot, item.toStack(count), false).getCount();
        }
        return 0;
    }

    private long insertOutput(ResourceAmount<AEKey> resource) {
        var grid = ((IGridConnectedBlockEntity) host).getMainNode().getGrid();
        if (grid != null) {
            long accepted = grid.getStorageService().getInventory().insert(resource.key(), resource.amount(),
                    Actionable.MODULATE, IActionSource.ofMachine((IGridConnectedBlockEntity) host));
            if (accepted > 0) return accepted;
        }
        return insertLocalOutput(resource);
    }

    public void save(CompoundTag tag) {
        if (processor.snapshot() != null) tag.put("ae2ocProcessing", ProcessingCodec.write(processor.snapshot()));
        else tag.remove("ae2ocProcessing");
    }

    public void load(CompoundTag tag) {
        // Decode fully before replacing live ownership. This method belongs to full host save loading.
        var saved = tag.contains("ae2ocProcessing") ? ProcessingCodec.read(tag.getCompound("ae2ocProcessing")) : null;
        processor.restore(saved);
        completedPrimaryOutput = null;
        if (retryBackoff != null) retryBackoff.reset();
    }

    public void addDrops(List<ItemStack> drops) {
        for (var resource : processor.ownedResources()) {
            // Destruction returns items only; fluids are intentionally discarded.
            if (resource.key() instanceof AEItemKey item && resource.amount() > 0) drops.add(moakiee.item.StoredResourcesItem.pack(item, resource.amount()));
        }
    }
}
