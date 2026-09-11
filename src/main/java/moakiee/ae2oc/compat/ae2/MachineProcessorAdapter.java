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
import moakiee.ae2oc.core.execution.BatchProcessor;
import moakiee.ae2oc.core.execution.ProcessingState;
import moakiee.ae2oc.core.planning.BatchPlanner;
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
    private long retryAt;
    private int retryDelay = 5;

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

    /** Null delegates a completely unmodified machine to upstream. */
    public TickRateModulation tick() {
        var profile = UpgradeProfileCache.of(host);
        boolean overclock = profile.overclock();
        int multiplier = profile.parallelLimit();
        if (processor.snapshot() == null && !overclock && multiplier <= 1) return null;
        if (!((IGridConnectedBlockEntity) host).getMainNode().isActive() || host.getLevel() == null) return TickRateModulation.IDLE;
        long now = host.getLevel().getGameTime();
        if (now < retryAt) return TickRateModulation.SLOWER;
        boolean progressed = false;
        if (processor.snapshot() == null) {
            progressed = reserve(overclock, multiplier);
            if (!progressed) return blocked(now);
        }
        var before = processor.snapshot();
        try {
            progressed |= processor.advance(this::extractEnergy);
            progressed |= processor.drain(Ae2OcConfig.getMaxTransferAmountPerMachineTick() / budgetShares, 64 / budgetShares, this::insertOutput);
        } finally {
            // Includes partial energy payments and each accepted output, even after a later failure.
            if (processor.snapshot() != before) host.saveChanges();
        }
        if (!progressed) return blocked(now);
        retryDelay = 5;
        retryAt = 0;
        return TickRateModulation.URGENT;
    }

    private TickRateModulation blocked(long now) {
        retryAt = now + retryDelay;
        retryDelay = Math.min(100, retryDelay * 2);
        return TickRateModulation.SLOWER;
    }

    private boolean reserve(boolean overclock, int multiplier) {
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
        var plan = BatchPlanner.plan(limit, materialLimit, 1_048_576L / outputAmount,
                Ae2OcConfig.getMaxRecipeOperationsPerMachineTick() / budgetShares, simulateEnergy(), recipe.unitEnergy());
        long count = plan.operations();
        if (count == 0) return false;
        List<ResourceAmount<AEKey>> inputs = new ArrayList<>();
        for (var debit : recipe.inputs()) if (debit.consumed() > 0) {
            inputs.add(new ResourceAmount<>(debit.before().key(), count * debit.consumed()));
        }
        var outputs = recipe.outputs().stream().map(output -> new ResourceAmount<>(output.key(), count * output.amount())).toList();
        var state = new ProcessingState<AEKey>(recipe.id(), inputs, outputs, plan.energyCost(), 0,
                overclock ? Ae2OcConfig.getOverclockCardProcessTicks() : recipe.normalTicks());
        try {
            for (var debit : recipe.inputs()) if (debit.consumed() > 0) {
                debit.slot().write(new ResourceAmount<>(debit.before().key(), debit.before().amount() - count * debit.consumed()));
            }
        } catch (RuntimeException failure) {
            for (var debit : recipe.inputs()) debit.slot().write(debit.before());
            throw failure;
        }
        processor.begin(state);
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
        if (tag.contains("ae2ocProcessing")) processor.restore(ProcessingCodec.read(tag.getCompound("ae2ocProcessing")));
    }

    public void addDrops(List<ItemStack> drops) {
        for (var resource : processor.ownedResources()) {
            if (resource.amount() > 0) drops.add(moakiee.item.StoredResourcesItem.pack(resource.key(), resource.amount()));
        }
    }
}

