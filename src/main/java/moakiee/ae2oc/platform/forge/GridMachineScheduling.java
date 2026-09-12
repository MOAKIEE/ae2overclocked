package moakiee.ae2oc.platform.forge;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Natural scheduling of the grid-driven machines (AE2, ExtendedAE).
 *
 * <p>These devices are ticked by AE2's grid tick manager rather than the level ticker, so the
 * representative scenario is not "the world ticker keeps calling us" but "something else decides
 * whether we are ticked at all". Two contracts follow from that and are pinned here:
 *
 * <ol>
 * <li>A machine without a grid peer or without grid energy must not consume, reserve or produce
 * anything, while still exposing a satisfied recipe — the fixture is runnable, only the schedule
 * and the power are missing.</li>
 * <li>Once the grid exists, the very same inputs must become the exact products and the batch must
 * pay its full energy, purely from world ticks. This fixture never calls {@code tickingRequest} or
 * {@code serverTick}; the only nudge it gives the machine is writing its real inventories, which is
 * what a hopper, a player or an export bus does.</li>
 * </ol>
 */
final class GridMachineScheduling {
    private GridMachineScheduling() {}

    private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);
    private static final BlockPos CELL_POS = MACHINE_POS.west();
    /** Grid formation and power propagation need a few real ticks before the scenario starts. */
    private static final int GRID_SETUP_TICKS = 40;
    private static final int STALL_TICKS = 100;
    private static final int NATURAL_TICK_BUDGET = 400;
    /** One mold plus four gold ingots: two parallel batches of two, or four operations in one lane. */
    private static final int OPERATIONS = 4;

    private static final AEItemKey MOLD = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRESS.asItem());
    private static final AEItemKey MIDDLE = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey RESULT = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRINT.asItem());
    private static final String RECIPE = "ae2:inscriber/logic_processor_print";

    /**
     * One inscriber's observable ports. {@code batchContainer} maps the host save tag to the compound
     * that owns this machine's batch: the host tag itself for AE2, the lane child for ExtendedAE.
     */
    private record Fixture(List<LocalResourceSlot> slots, InternalInventory inventory,
                           Supplier<CompoundTag> savedTag, Function<CompoundTag, CompoundTag> batchContainer,
                           BooleanSupplier connected, BooleanSupplier sleeping) {
        CompoundTag batchTag() {
            return batchContainer.apply(savedTag.get());
        }

        boolean holdsBatch() {
            return batchTag().contains("ae2ocProcessing");
        }

        long amount(int slot) {
            var value = slots.get(slot).read();
            return value == null ? 0 : value.amount();
        }
    }

    /** AE2 inscriber: the representative grid-driven machine available in every runtime. */
    static void ae2Inscriber(GameTestHelper helper) {
        helper.setBlock(MACHINE_POS, Blocks.AIR);
        helper.setBlock(MACHINE_POS, AEBlocks.INSCRIBER.block());
        var machine = (InscriberBlockEntity) helper.getBlockEntity(MACHINE_POS);
        helper.runAfterDelay(GRID_SETUP_TICKS, () -> {
            helper.assertTrue(!machine.getMainNode().isActive(),
                    "A lone machine already reports an active node; the scenario premise changed");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
            machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            var fixture = new Fixture(ManagedItemStorages.slots(machine.getInternalInventory()),
                    machine.getInternalInventory(), machine::saveWithFullMetadata, tag -> tag,
                    () -> machine.getMainNode().isActive(),
                    () -> machine.getTickingRequest(machine.getMainNode().getNode()).isSleeping());
            prime(helper, fixture, () -> machine.getTask() != null, () -> {
                helper.setBlock(CELL_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());
                helper.runAfterDelay(GRID_SETUP_TICKS, () -> {
                    helper.assertTrue(fixture.connected().getAsBoolean(), "The machine never joined the new grid");
                    observe(helper, fixture, 0, 0, false);
                });
            });
        });
    }

    /** ExtendedAE inscriber: four lanes share one host, so lane 0 owns its own child save tag. */
    static void extendedInscriber(GameTestHelper helper) {
        helper.setBlock(MACHINE_POS, Blocks.AIR);
        helper.setBlock(MACHINE_POS, ForgeRegistries.BLOCKS
                .getValue(ResourceLocation.fromNamespaceAndPath("expatternprovider", "ex_inscriber")));
        var machine = (TileExInscriber) helper.getBlockEntity(MACHINE_POS);
        helper.runAfterDelay(GRID_SETUP_TICKS, () -> {
            helper.assertTrue(!machine.getMainNode().isActive(),
                    "A lone machine already reports an active node; the scenario premise changed");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
            machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            var fixture = new Fixture(ManagedItemStorages.slots(machine.getIndexInventory(0)),
                    machine.getIndexInventory(0), machine::saveWithFullMetadata,
                    tag -> tag.getCompound("ae2ocThread0"),
                    () -> machine.getMainNode().isActive(),
                    () -> machine.getTickingRequest(machine.getMainNode().getNode()).isSleeping());
            prime(helper, fixture, () -> machine.getTask(0) != null, () -> {
                helper.setBlock(CELL_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());
                helper.runAfterDelay(GRID_SETUP_TICKS, () -> {
                    helper.assertTrue(fixture.connected().getAsBoolean(), "The machine never joined the new grid");
                    observe(helper, fixture, 0, 0, false);
                });
            });
        });
    }

    /** Load a satisfiable real recipe, then require that nothing happens without grid energy. */
    private static void prime(GameTestHelper helper, Fixture fixture, BooleanSupplier recipeSatisfied, Runnable resume) {
        fixture.slots().get(0).write(new ResourceAmount<AEKey>(MOLD, 1));
        fixture.slots().get(2).write(new ResourceAmount<AEKey>(MIDDLE, OPERATIONS));
        helper.assertTrue(recipeSatisfied.getAsBoolean(),
                "The fixture has no runnable recipe; the machine is not merely unscheduled");
        stall(helper, fixture, STALL_TICKS, resume);
    }

    private static void stall(GameTestHelper helper, Fixture fixture, int remaining, Runnable resume) {
        helper.assertTrue(fixture.amount(0) == 1 && fixture.amount(2) == OPERATIONS && fixture.amount(3) == 0,
                "A machine without grid energy consumed or produced resources");
        helper.assertTrue(!fixture.holdsBatch(),
                "A machine without grid energy reserved a batch it cannot schedule");
        if (remaining == 0) {
            resume.run();
            return;
        }
        helper.runAfterDelay(1, () -> stall(helper, fixture, remaining - 1, resume));
    }

    private static void observe(GameTestHelper helper, Fixture fixture, long collected, int elapsed,
            boolean sawPayment) {
        var output = fixture.inventory().extractItem(3, 64, false);
        helper.assertTrue(output.isEmpty() || RESULT.matches(output) && output.getCount() <= output.getMaxStackSize(),
                "Natural scheduling delivered an unexpected or oversized stack");
        long produced = collected + output.getCount();
        long pending = 0;
        if (fixture.holdsBatch()) {
            var state = ProcessingCodec.read(fixture.batchTag().getCompound("ae2ocProcessing"));
            helper.assertTrue(state.recipe().equals(RECIPE),
                    "Natural scheduling ran an unexpected recipe: " + state.recipe());
            helper.assertTrue(state.energyRequired() > 0, "A batch was reserved without an energy requirement");
            sawPayment |= state.energyPaid() > 0;
            pending = state.finished() ? state.outputs().stream().mapToLong(ResourceAmount::amount).sum()
                    : state.inputs().stream().filter(input -> input.key().equals(MIDDLE))
                            .mapToLong(ResourceAmount::amount).sum();
        }
        helper.assertTrue(fixture.amount(0) == 1 && produced + pending + fixture.amount(2) == OPERATIONS,
                "Grid-driven machine conservation failed at tick " + elapsed);
        if (produced == OPERATIONS) {
            helper.assertTrue(!fixture.holdsBatch(), "A completed natural batch was retained");
            helper.assertTrue(fixture.amount(2) == 0 && fixture.amount(3) == 0,
                    "The machine retained inputs or output after natural completion");
            helper.assertTrue(sawPayment, "The natural batch was never charged for the energy it consumed");
            helper.assertTrue(fixture.sleeping().getAsBoolean(),
                    "An empty upgraded machine still reports itself as busy");
            helper.succeed();
            return;
        }
        helper.assertTrue(elapsed < NATURAL_TICK_BUDGET,
                "Grid-driven machine never finished under natural scheduling: " + produced + "/" + OPERATIONS);
        boolean paid = sawPayment;
        helper.runAfterDelay(1, () -> observe(helper, fixture, produced, elapsed + 1, paid));
    }
}
