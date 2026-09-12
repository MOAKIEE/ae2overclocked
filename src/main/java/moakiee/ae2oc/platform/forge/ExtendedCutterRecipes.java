package moakiee.ae2oc.platform.forge;

import java.util.List;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Loaded only when ExtendedAE is present. Every processing scenario drives the real
 * {@code expatternprovider:cutter/logic} recipe, which consumes one gold block and 100 mB of water for
 * nine printed logic circuits. Real recipes exercise the shared item and fluid ledgers together, so the
 * tests assert the item count, the tank contents and the produced output at every tick.
 */
final class ExtendedCutterRecipes {
    private static final String RECIPE = "expatternprovider:cutter/logic";
    private static final long FLUID_PER_OPERATION = 100;
    private static final long OUTPUT_PER_OPERATION = 9;
    private static final AEItemKey BLOCK = AEItemKey.of(Items.GOLD_BLOCK);
    private static final AEItemKey PRINT = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRINT.asItem());
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);

    private ExtendedCutterRecipes() {}

    private static TileCircuitCutter place(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("expatternprovider", "circuit_cutter")));
        return (TileCircuitCutter) helper.getBlockEntity(pos);
    }

    private static LocalResourceSlot inputSlot(TileCircuitCutter machine) {
        return ManagedItemStorages.slots(machine.getInput()).get(0);
    }

    private static LocalResourceSlot outputSlot(TileCircuitCutter machine) {
        return ManagedItemStorages.slots(machine.getOutput()).get(0);
    }

    private static long tank(TileCircuitCutter machine) {
        return machine.getTank().getAmount(0);
    }

    /** Installs a capacity card so the tank holds every operation; the fluid limit is refreshed on load. */
    private static void upgrade(TileCircuitCutter machine, Item parallel, boolean overclock) {
        if (parallel != null) machine.getUpgrades().setItemDirect(0, new ItemStack(parallel));
        if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
        machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
        // The tank picks up a new capacity only on init/load, so publish the card before the first tick.
        machine.load(machine.saveWithFullMetadata());
    }

    /**
     * Twelve upgrade combinations against the real recipe. Without a parallel or overclock card the machine
     * must still fall through to the upstream tick; with cards the shared processor owns the batch. Both
     * paths must keep gold blocks, water and printed circuits exactly balanced at every tick.
     */
    static void recipeMatrix(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Cutter grid is inactive");
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            Item[] cards = {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            upgrade(machine, cards[tier], overclock);
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);

            long operations = new long[] {24, 4, 8, 130, 600, 1200}[tier];
            inputSlot(machine).write(new ResourceAmount<AEKey>(BLOCK, operations));
            machine.getTank().setStack(0, new GenericStack(WATER, operations * FLUID_PER_OPERATION));
            String label = "tier=" + tier + ", overclock=" + overclock;

            long collected = 0;
            int ticks = 0;
            while (collected < operations * OUTPUT_PER_OPERATION && ticks++ < 40000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                var output = machine.getOutput().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || PRINT.matches(output) && output.getCount() <= output.getMaxStackSize(),
                        "Wrong or oversized cutter output: " + label);
                collected += output.getCount();
                var batch = batch(machine);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    helper.assertTrue(batch != null && batch.recipe().equals(RECIPE),
                            "Custom path did not select the real recipe: " + label);
                }
                long reserved = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), BLOCK);
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), PRINT);
                long materialized = collected + amount(outputSlot(machine)) + pending;
                helper.assertTrue(materialized % OUTPUT_PER_OPERATION == 0,
                        "Cutter output is not a whole number of operations: " + label + ", tick=" + ticks);
                long consumed = materialized / OUTPUT_PER_OPERATION + reserved;
                helper.assertTrue(amount(inputSlot(machine)) + consumed == operations,
                        "Cutter item conservation failed: " + label + ", tick=" + ticks
                                + ", left=" + amount(inputSlot(machine)) + ", consumed=" + consumed);
                helper.assertTrue(tank(machine) + consumed * FLUID_PER_OPERATION == operations * FLUID_PER_OPERATION,
                        "Cutter fluid conservation failed: " + label + ", tick=" + ticks
                                + ", tank=" + tank(machine) + ", consumed=" + consumed);
            }
            helper.assertTrue(collected == operations * OUTPUT_PER_OPERATION, "Cutter recipe did not complete: " + label);
            helper.assertTrue(amount(inputSlot(machine)) == 0 && tank(machine) == 0,
                    "Cutter retained inputs after completion: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Cutter retained a completed batch: " + label);
            recipeMatrix(helper, scenario + 1);
        });
    }

    /**
     * Auto-export while the shared processor owns the batch. Without a capacity card the output slot is
     * limited to one stack, so the machine must hand the rest to the adjacent chest across several ticks
     * instead of destroying the rejected remainder or never exporting at all.
     */
    static void autoExportConservation(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.setBlock(pos.east(), Blocks.CHEST);
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos.east());
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Cutter auto-export grid is inactive");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_64X.get()));
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);

            final long operations = 20;
            final long expected = operations * OUTPUT_PER_OPERATION;
            inputSlot(machine).write(new ResourceAmount<AEKey>(BLOCK, operations));
            machine.getTank().setStack(0, new GenericStack(WATER, operations * FLUID_PER_OPERATION));

            int ticks = 0;
            long seen = 0;
            while (chestCount(chest, PRINT) < expected && ticks++ < 40000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                var batch = batch(machine);
                long reserved = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), BLOCK);
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), PRINT);
                long produced = chestCount(chest, PRINT) + amount(outputSlot(machine)) + pending;
                helper.assertTrue(produced >= seen && produced <= expected,
                        "Cutter output vanished or duplicated at tick " + ticks + ": " + produced);
                seen = produced;
                helper.assertTrue(produced % OUTPUT_PER_OPERATION == 0,
                        "Cutter produced a partial operation at tick " + ticks);
                long consumed = produced / OUTPUT_PER_OPERATION + reserved;
                helper.assertTrue(amount(inputSlot(machine)) + consumed == operations,
                        "Cutter auto-export lost gold blocks at tick " + ticks);
                helper.assertTrue(tank(machine) + consumed * FLUID_PER_OPERATION == operations * FLUID_PER_OPERATION,
                        "Cutter auto-export lost water at tick " + ticks);
            }
            helper.assertTrue(chestCount(chest, PRINT) == expected,
                    "Cutter auto-export never delivered every result: " + chestCount(chest, PRINT));
            helper.assertTrue(amount(outputSlot(machine)) == 0 && amount(inputSlot(machine)) == 0 && tank(machine) == 0,
                    "Cutter retained resources after auto-export");
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Cutter retained a batch after auto-export");
            helper.succeed();
        });
    }

    /**
     * Destruction packaging. The visible projection, the logical overflow, the reserved inputs of an
     * unfinished batch, the products of a finished one and the tank fluid must all leave the machine as
     * bounded containers; nothing may be counted twice.
     */
    static void destructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("expatternprovider", "circuit_cutter")));
        var machine = (TileCircuitCutter) helper.getBlockEntity(pos);
        outputSlot(machine).write(new ResourceAmount<AEKey>(PRINT, 130));
        machine.getTank().setStack(0, new GenericStack(WATER, 4000));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(PRINT, 45)), 100, 100, 0)));
        machine.load(saved);

        var drops = new java.util.ArrayList<ItemStack>();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, PRINT) == 111,
                "Finished batch or logical overflow was not packaged exactly once: " + packedAmount(drops, PRINT));
        helper.assertTrue(packedAmount(drops, WATER) == 0, "Cutter destruction must discard tank fluid");
        helper.assertTrue(packedAmount(drops, BLOCK) == 0, "Finished batch returned a consumed input");
        helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.STORED_RESOURCES.get())).count() == 2,
                "Unexpected stored-resource drop count: " + drops.size());

        saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.of(new ResourceAmount<>(BLOCK, 12)), List.of(new ResourceAmount<>(PRINT, 108)), 100, 25, 4)));
        machine.load(saved);

        drops.clear();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, BLOCK) == 12, "Unfinished batch did not return its reserved inputs");
        helper.assertTrue(packedAmount(drops, PRINT) == 66, "Unfinished batch packaged products it never made");
        helper.assertTrue(packedAmount(drops, WATER) == 0, "Cutter destruction must not package fluid");
        helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.STORED_RESOURCES.get())).count() == 2,
                "Unexpected stored-resource drop count: " + drops.size());
        helper.succeed();
    }

    /**
     * Real destruction of a placed machine. The visible input, the logical output that overflows a legal
     * stack, the products of a finished batch must reach the world exactly once, while tank fluid is discarded when
     * the block is actually destroyed, not merely when the drop hook is invoked directly.
     */
    static void realDestructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("expatternprovider", "circuit_cutter")));
        var machine = (TileCircuitCutter) helper.getBlockEntity(pos);
        inputSlot(machine).write(new ResourceAmount<AEKey>(BLOCK, 3));
        outputSlot(machine).write(new ResourceAmount<AEKey>(PRINT, 130));
        machine.getTank().setStack(0, new GenericStack(WATER, 4000));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(PRINT, 45)), 100, 100, 0)));
        machine.load(saved);

        helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(pos), true, helper.makeMockPlayer()),
                "Cutter destruction failed");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) == null,
                "Destroyed cutter remains in the world");
        helper.runAfterDelay(2, () -> {
            var entities = helper.getEntities(net.minecraft.world.entity.EntityType.ITEM, pos, 4);
            helper.assertTrue(entityAmount(entities, PRINT) == 175,
                    "Real destruction lost or duplicated cutter output: " + entityAmount(entities, PRINT));
            helper.assertTrue(entityAmount(entities, BLOCK) == 3,
                    "Real destruction lost the visible cutter input: " + entityAmount(entities, BLOCK));
            helper.assertTrue(entityAmount(entities, WATER) == 0,
                    "Cutter destruction unexpectedly returned fluid: " + entityAmount(entities, WATER));
            helper.succeed();
        });
    }

    private static ProcessingState<AEKey> batch(TileCircuitCutter machine) {
        var tag = machine.saveWithFullMetadata();
        return tag.contains("ae2ocProcessing") ? ProcessingCodec.read(tag.getCompound("ae2ocProcessing")) : null;
    }

    private static long amountOf(List<ResourceAmount<AEKey>> resources, AEKey key) {
        long total = 0;
        for (var resource : resources) if (resource.key().equals(key)) total += resource.amount();
        return total;
    }

    private static long amount(LocalResourceSlot slot) {
        var value = slot.read();
        return value == null ? 0 : value.amount();
    }

    private static long chestCount(ChestBlockEntity chest, AEItemKey key) {
        long total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            if (key.matches(chest.getItem(slot))) total += chest.getItem(slot).getCount();
        return total;
    }

    /** Amount carried by the mod's bounded stored-resource containers, never plain item stacks. */
    private static long packedAmount(List<ItemStack> drops, AEKey key) {
        long total = 0;
        for (var drop : drops) {
            if (!drop.is(ModItems.STORED_RESOURCES.get()) || !drop.hasTag()) continue;
            if (key.equals(AEKey.fromTagGeneric(drop.getTag().getCompound("resource"))))
                total += Math.max(0, drop.getTag().getLong("amount"));
        }
        return total;
    }

    /** Visible stacks plus bounded stored-resource containers, as they appear in the world after a break. */
    private static long entityAmount(List<net.minecraft.world.entity.item.ItemEntity> entities, AEKey key) {
        long total = 0;
        for (var entity : entities) {
            var stack = entity.getItem();
            if (key instanceof AEItemKey item && item.matches(stack)) total += stack.getCount();
            else if (stack.is(ModItems.STORED_RESOURCES.get()) && stack.hasTag()
                    && key.equals(AEKey.fromTagGeneric(stack.getTag().getCompound("resource"))))
                total += Math.max(0, stack.getTag().getLong("amount"));
        }
        return total;
    }
}
