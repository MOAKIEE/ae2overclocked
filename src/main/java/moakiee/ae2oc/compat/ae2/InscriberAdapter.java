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
import appeng.blockentity.grid.AENetworkPowerBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.ModItems;
import moakiee.Ae2OcConfig;
import moakiee.support.ParallelCardRuntime;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.core.execution.BatchProcessor;
import moakiee.ae2oc.core.execution.ProcessingState;
import moakiee.ae2oc.core.planning.BatchPlanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Typed bindings shared by AE2 and ExtendedAE inscriber threads. */
public final class InscriberAdapter {
    private final AENetworkPowerBlockEntity host;
    private final IUpgradeableObject upgrades;
    private final InternalInventory inventory;
    private final Supplier<InscriberRecipe> recipeSource;
    private final int budgetShares;
    private final BatchProcessor<AEKey> processor = new BatchProcessor<>();
    private long retryAt;
    private int retryDelay = 5;

    public InscriberAdapter(AENetworkPowerBlockEntity host, IUpgradeableObject upgrades,
                            InternalInventory inventory, Supplier<InscriberRecipe> recipeSource) {
        this(host, upgrades, inventory, recipeSource, 1);
    }

    public InscriberAdapter(AENetworkPowerBlockEntity host, IUpgradeableObject upgrades,
                            InternalInventory inventory, Supplier<InscriberRecipe> recipeSource, int budgetShares) {
        this.host = host;
        this.upgrades = upgrades;
        this.inventory = inventory;
        this.recipeSource = recipeSource;
        this.budgetShares = budgetShares;
    }

    /** Null delegates a completely unmodified machine to upstream. */
    public TickRateModulation tick() {
        boolean disabled = Ae2OcConfig.isMachineDisabled(host);
        boolean overclock = !disabled && upgrades.getUpgrades().getInstalledUpgrades(ModItems.OVERCLOCK_CARD.get()) > 0;
        int multiplier = disabled ? 1 : ParallelCardRuntime.getParallelMultiplier(host);
        if (processor.snapshot() == null && !overclock && multiplier <= 1) return null;
        if (!host.getMainNode().isActive() || host.getLevel() == null) return TickRateModulation.IDLE;
        long now = host.getLevel().getGameTime();
        if (now < retryAt) return TickRateModulation.SLOWER;
        boolean progressed = false;
        if (processor.snapshot() == null) {
            progressed = reserve(overclock, multiplier);
            if (!progressed) return blocked(now);
        }
        try {
            progressed |= processor.advance(this::extractEnergy);
            progressed |= processor.drain(1_048_576 / budgetShares, 64 / budgetShares, this::insertOutput);
        } finally {
            // Includes partial energy payments and each accepted output, even after a later failure.
            host.saveChanges();
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
        InscriberRecipe recipe = recipeSource.get();
        if (recipe == null || recipe.getResultItem().isEmpty()) return false;
        var output = recipe.getResultItem();
        ItemStack[] before = new ItemStack[3];
        for (int slot = 0; slot < 3; slot++) before[slot] = inventory.getStackInSlot(slot).copy();
        long materialLimit = before[2].getCount();
        boolean press = recipe.getProcessType() == InscriberProcessType.PRESS;
        if (press) for (int slot = 0; slot < 2; slot++) {
            if (!before[slot].isEmpty()) materialLimit = Math.min(materialLimit, before[slot].getCount());
        }
        int speed = switch (upgrades.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD)) {
            case 1 -> 3; case 2 -> 5; case 3 -> 10; case 4 -> 50; default -> 2;
        };
        // AE2 advances 200 steps, at 10 AE per step, and completes after exceeding 200.
        int normalTicks = 200 / speed + 1;
        double unitEnergy = normalTicks * speed * 10.0;
        double available = simulateEnergy();
        var limit = multiplier == Integer.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(multiplier);
        var plan = BatchPlanner.plan(limit, materialLimit, 1_048_576L / output.getCount(), 4096 / budgetShares, available, unitEnergy);
        int count = (int) plan.operations();
        if (count == 0) return false;
        List<ResourceAmount<AEKey>> inputs = new ArrayList<>();
        for (int slot = 0; slot < 3; slot++) {
            if (slot < 2 && !press || before[slot].isEmpty()) continue;
            inputs.add(new ResourceAmount<>(AEItemKey.of(before[slot]), count));
        }
        var state = new ProcessingState<AEKey>(recipe.getId().toString(), inputs,
                List.of(new ResourceAmount<>(AEItemKey.of(output), (long) count * output.getCount())),
                plan.energyCost(), 0, overclock ? Ae2OcConfig.getOverclockCardProcessTicks() : normalTicks);
        // All input slots are local and snapshotted. No external output is touched during reservation.
        try {
            for (int slot = 0; slot < 3; slot++) {
                if (slot < 2 && !press || before[slot].isEmpty()) continue;
                inventory.setItemDirect(slot, before[slot].copyWithCount(before[slot].getCount() - count));
            }
        } catch (RuntimeException failure) {
            for (int slot = 0; slot < 3; slot++) inventory.setItemDirect(slot, before[slot]);
            throw failure;
        }
        processor.begin(state);
        host.saveChanges();
        return true;
    }

    private double simulateEnergy() {
        double internal = host.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        var grid = host.getMainNode().getGrid();
        double network = grid == null ? 0 : grid.getEnergyService().extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        return Math.min(Double.MAX_VALUE, internal + network);
    }

    private double extractEnergy(double requested) {
        double paid = host.extractAEPower(requested, Actionable.MODULATE, PowerMultiplier.CONFIG);
        var grid = host.getMainNode().getGrid();
        if (paid < requested && grid != null) {
            paid += grid.getEnergyService().extractAEPower(requested - paid, Actionable.MODULATE, PowerMultiplier.CONFIG);
        }
        return paid;
    }

    private long insertOutput(ResourceAmount<AEKey> resource) {
        var grid = host.getMainNode().getGrid();
        if (grid != null) {
            long accepted = grid.getStorageService().getInventory().insert(resource.key(), resource.amount(),
                    Actionable.MODULATE, IActionSource.ofMachine(host));
            if (accepted > 0) return accepted;
        }
        if (resource.key() instanceof AEItemKey item) {
            int count = (int) Math.min(resource.amount(), item.toStack().getMaxStackSize());
            return count - inventory.insertItem(3, item.toStack(count), false).getCount();
        }
        return 0;
    }

    public void save(CompoundTag tag) {
        if (processor.snapshot() != null) tag.put("ae2ocProcessing", ProcessingCodec.write(processor.snapshot()));
        else tag.remove("ae2ocProcessing");
    }

    public void load(CompoundTag tag) {
        if (tag.contains("ae2ocProcessing")) processor.restore(ProcessingCodec.read(tag.getCompound("ae2ocProcessing")));
    }

    public void addDrops(List<ItemStack> drops) {
        for (var resource : processor.ownedResources()) resource.key().addDrops(resource.amount(), drops, host.getLevel(), host.getBlockPos());
    }
}
