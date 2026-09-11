package moakiee.ae2oc.platform.forge;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEBlocks;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity;

/**
 * Energy semantics of the shared processor. The crystal pulverizer is used because AE2 Crystal Science
 * machines are ticked directly by the world, while AE2, ExtendedAE and AdvancedAE machines are driven by
 * AE2's grid tick manager and therefore simply stop being ticked once their node is gone. The difference
 * between "gate on the main node being active" and "run off the machine's own buffer" is only observable
 * on the world-ticked machines, so this is where the contract is pinned down.
 *
 * Measured premise: a machine with no adjacent AE2 grid does <em>not</em> have an active main node, so a
 * lone machine is a reachable "disconnected" state rather than a theoretical one.
 */
final class AE2CSEnergySemantics {
    private static final AEItemKey FLINT = AEItemKey.of(Items.FLINT);
    private static final AEItemKey GUNPOWDER = AEItemKey.of(Items.GUNPOWDER);
    /** Eight flint at 8000 AE each: two operations per batch, four batches, 64000 AE of the 80000 buffer. */
    private static final int OPERATIONS = 8;
    private static final double CHARGE = 200_000;

    private AE2CSEnergySemantics() {}

    private static CrystalPulverizerBlockEntity place(GameTestHelper helper, BlockPos pos, boolean gridPower) {
        helper.setBlock(pos, Blocks.AIR);
        if (gridPower) helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_pulverizer")));
        return (CrystalPulverizerBlockEntity) helper.getBlockEntity(pos);
    }

    /** A parallel card takes the machine off the upstream fallback so the shared processor owns the batch. */
    private static void installProcessorCards(CrystalPulverizerBlockEntity machine) {
        machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
        machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
    }

    private static LocalResourceSlot inputSlot(CrystalPulverizerBlockEntity machine) {
        return ManagedItemStorages.slots(machine.getInputInv()).get(0);
    }

    private static long inputAmount(CrystalPulverizerBlockEntity machine) {
        var value = inputSlot(machine).read();
        return value == null ? 0 : value.amount();
    }

    private static double charge(CrystalPulverizerBlockEntity machine) {
        return ((IAEPowerStorage) machine).injectAEPower(CHARGE, Actionable.MODULATE);
    }

    /**
     * Upstream machines keep draining their own buffer while the main node is inactive and only stall once
     * nothing is left to pay with. Destroying the node removes both the grid tick manager and the grid
     * energy service, so this only passes when the processor really runs off the machine's internal storage
     * instead of freezing whenever the node is not active.
     */
    static void keepsProcessingWhileNodeIsInactive(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos, true);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Pulverizer grid is inactive");
            installProcessorCards(machine);
            inputSlot(machine).write(new ResourceAmount<AEKey>(FLINT, OPERATIONS));

            machine.getMainNode().destroy();
            helper.assertTrue(!machine.getMainNode().isActive(), "Destroyed node still reports active");
            helper.assertTrue(machine.getMainNode().getGrid() == null, "Destroyed node still exposes a grid");
            helper.assertTrue(charge(machine) > 0 && ((IAEPowerStorage) machine).getAECurrentPower() > 0,
                    "Internal buffer rejected the charge");

            long collected = 0;
            int ticks = 0;
            while (collected < OPERATIONS && ticks++ < 4000) {
                machine.serverTick();
                var output = machine.getOutputInv().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || GUNPOWDER.matches(output), "Wrong pulverizer output");
                collected += output.getCount();
            }
            helper.assertTrue(collected == OPERATIONS,
                    "Inactive node froze the batch: collected=" + collected + "/" + OPERATIONS
                            + ", active=" + machine.getMainNode().isActive());
            helper.assertTrue(inputAmount(machine) == 0, "Flint was not fully consumed");
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Inactive node left a batch behind");
            helper.succeed();
        });
    }

    /**
     * With no energy anywhere the machine must not reserve a batch it cannot pay for, and it must leave the
     * inputs exactly where they were. Once energy arrives the very same inputs must become the exact products.
     * A machine with no adjacent AE2 grid is not active either, so the resume phase also pins the contract
     * that an inactive node must not freeze work that the machine's own buffer can pay for.
     */
    static void stallsWithoutEnergyThenResumes(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos, false);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(!machine.getMainNode().isActive(),
                    "A lone machine reportedly formed an active grid; the scenario premise changed");
            helper.assertTrue(((IAEPowerStorage) machine).getAECurrentPower() == 0,
                    "Unpowered machine already held energy");
            installProcessorCards(machine);
            inputSlot(machine).write(new ResourceAmount<AEKey>(FLINT, OPERATIONS));

            long collected = 0;
            for (int tick = 0; tick < 60; tick++) {
                machine.serverTick();
                collected += machine.getOutputInv().extractItem(0, 64, false).getCount();
            }
            helper.assertTrue(collected == 0 && inputAmount(machine) == OPERATIONS,
                    "Unpowered machine consumed inputs: collected=" + collected + ", left=" + inputAmount(machine));
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Unpowered machine reserved a batch it cannot pay for");

            helper.assertTrue(charge(machine) > 0, "Internal buffer rejected the charge");
            // The blocked retry backoff is anchored to game time, so let a few world ticks pass first.
            helper.runAfterDelay(10, () -> {
                long produced = 0;
                int ticks = 0;
                while (produced < OPERATIONS && ticks++ < 4000) {
                    machine.serverTick();
                    var output = machine.getOutputInv().extractItem(0, 64, false);
                    helper.assertTrue(output.isEmpty() || GUNPOWDER.matches(output), "Wrong pulverizer output");
                    produced += output.getCount();
                }
                helper.assertTrue(produced == OPERATIONS,
                        "Refuelled machine did not finish: " + produced + "/" + OPERATIONS);
                helper.assertTrue(inputAmount(machine) == 0, "Refuelled machine retained inputs");
                helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                        "Refuelled machine left a batch behind");
                helper.succeed();
            });
        });
    }
}
