package moakiee.ae2oc.platform.forge;

import java.util.EnumSet;
import java.util.List;
import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;

/**
 * Loaded only when AdvancedAE is present. Both scenarios drive real reaction chamber recipes:
 * {@code advanced_ae:logic_processor_chamber} mixes three item inputs with water and produces items, while
 * {@code advanced_ae:quantum_infusion} consumes a single item with water and produces a fluid. Together
 * they cover the mixed item/fluid ledger, the single-output item path and the tank-slot-0 fluid output.
 */
final class AdvancedReactionRecipes {
    private static final String ITEM_RECIPE = "advanced_ae:logic_processor_chamber";
    private static final String FLUID_RECIPE = "advanced_ae:quantum_infusion";
    private static final AEItemKey PRINT_LOGIC = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRINT.asItem());
    private static final AEItemKey PRINT_SILICON = AEItemKey.of(AEItems.SILICON_PRINT.asItem());
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey LOGIC_PROCESSOR = AEItemKey.of(AEItems.LOGIC_PROCESSOR.asItem());
    private static final AEItemKey INFUSED_DUST = AEItemKey.of(
            ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("advanced_ae:quantum_infused_dust")));
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final AEFluidKey QUANTUM_INFUSION = AEFluidKey.of(
            ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse("advanced_ae:quantum_infusion_source")));
    private static final long ITEMS_PER_OPERATION = 4;
    private static final long ITEM_OUTPUT_PER_OPERATION = 4;
    private static final long WATER_PER_ITEM_OPERATION = 100;
    private static final long WATER_PER_FLUID_OPERATION = 4000;
    private static final long FLUID_OUTPUT_PER_OPERATION = 1000;

    private AdvancedReactionRecipes() {}

    private static ReactionChamberEntity place(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("advanced_ae:reaction_chamber")));
        return (ReactionChamberEntity) helper.getBlockEntity(pos);
    }

    private static LocalResourceSlot inputSlot(ReactionChamberEntity machine, int slot) {
        return ManagedItemStorages.slots(machine.getInput()).get(slot);
    }

    private static long inputAmount(ReactionChamberEntity machine, int slot) {
        var value = inputSlot(machine, slot).read();
        return value == null ? 0 : value.amount();
    }

    private static long outputAmount(ReactionChamberEntity machine) {
        var value = ManagedItemStorages.slots(machine.getOutput()).get(0).read();
        return value == null ? 0 : value.amount();
    }

    private static long tank(ReactionChamberEntity machine, int slot) {
        return machine.getTank().getAmount(slot);
    }

    /** A capacity card only reaches the tank on init/load, so publish it before the first tick. */
    private static void upgrade(ReactionChamberEntity machine, Item parallel, boolean overclock, boolean capacity) {
        if (parallel != null) machine.getUpgrades().setItemDirect(0, new ItemStack(parallel));
        if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
        if (capacity) machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
        machine.load(machine.saveWithFullMetadata());
    }

    private static ProcessingState<AEKey> batch(ReactionChamberEntity machine) {
        var tag = machine.saveWithFullMetadata();
        return tag.contains("ae2ocProcessing") ? ProcessingCodec.read(tag.getCompound("ae2ocProcessing")) : null;
    }

    private static long amountOf(List<ResourceAmount<AEKey>> resources, AEKey key) {
        long total = 0;
        for (var resource : resources) if (resource.key().equals(key)) total += resource.amount();
        return total;
    }

    /**
     * Twelve upgrade combinations against {@code advanced_ae:logic_processor_chamber}: four printed logic
     * circuits, four printed silicon and four redstone dust plus 100 mB of water become four logic
     * processors. Every input and the fluid tank must stay balanced against the number of finished
     * operations at every tick, on both the upstream fallback and the shared processor.
     */
    static void itemRecipeMatrix(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Reaction chamber grid is inactive");
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            Item[] cards = {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            upgrade(machine, cards[tier], overclock, true);
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);

            long operations = new long[] {12, 4, 6, 40, 80, 160}[tier];
            inputSlot(machine, 0).write(new ResourceAmount<AEKey>(PRINT_LOGIC, operations * ITEMS_PER_OPERATION));
            inputSlot(machine, 1).write(new ResourceAmount<AEKey>(PRINT_SILICON, operations * ITEMS_PER_OPERATION));
            inputSlot(machine, 2).write(new ResourceAmount<AEKey>(REDSTONE, operations * ITEMS_PER_OPERATION));
            machine.getTank().setStack(1, new GenericStack(WATER, operations * WATER_PER_ITEM_OPERATION));
            String label = "tier=" + tier + ", overclock=" + overclock;

            long collected = 0;
            int ticks = 0;
            while (collected < operations * ITEM_OUTPUT_PER_OPERATION && ticks++ < 40000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                var output = machine.getOutput().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || LOGIC_PROCESSOR.matches(output) && output.getCount() <= output.getMaxStackSize(),
                        "Wrong or oversized reaction output: " + label);
                collected += output.getCount();
                var batch = batch(machine);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    helper.assertTrue(batch != null && batch.recipe().equals(ITEM_RECIPE),
                            "Custom path did not select the real reaction recipe: " + label);
                }
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), WATER) / WATER_PER_ITEM_OPERATION;
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), LOGIC_PROCESSOR);
                long materialized = collected + outputAmount(machine) + pending;
                helper.assertTrue(materialized % ITEM_OUTPUT_PER_OPERATION == 0,
                        "Reaction output is not a whole number of operations: " + label + ", tick=" + ticks);
                long consumed = materialized / ITEM_OUTPUT_PER_OPERATION + inFlight;
                for (int slot = 0; slot < 3; slot++) {
                    helper.assertTrue(inputAmount(machine, slot) + consumed * ITEMS_PER_OPERATION == operations * ITEMS_PER_OPERATION,
                            "Reaction item conservation failed in slot " + slot + ": " + label + ", tick=" + ticks);
                }
                helper.assertTrue(tank(machine, 1) + consumed * WATER_PER_ITEM_OPERATION == operations * WATER_PER_ITEM_OPERATION,
                        "Reaction fluid conservation failed: " + label + ", tick=" + ticks
                                + ", tank=" + tank(machine, 1) + ", consumed=" + consumed);
            }
            helper.assertTrue(collected == operations * ITEM_OUTPUT_PER_OPERATION, "Reaction recipe did not complete: " + label);
            for (int slot = 0; slot < 3; slot++)
                helper.assertTrue(inputAmount(machine, slot) == 0, "Reaction retained item inputs: " + label);
            helper.assertTrue(tank(machine, 1) == 0 && !machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Reaction retained water or a completed batch: " + label);
            itemRecipeMatrix(helper, scenario + 1);
        });
    }

    /**
     * The only shipped fluid-output recipe: one quantum infused dust plus 4000 mB of water become
     * 1000 mB of quantum infusion in tank slot 0. The dust, the water and the produced fluid must all be
     * accounted for while the product accumulates past the base tank capacity.
     */
    static void fluidRecipeConservation(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Reaction chamber grid is inactive");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_8X.get()));
            machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
            machine.load(machine.saveWithFullMetadata());
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);

            final long operations = 12;
            inputSlot(machine, 0).write(new ResourceAmount<AEKey>(INFUSED_DUST, operations));
            machine.getTank().setStack(1, new GenericStack(WATER, operations * WATER_PER_FLUID_OPERATION));

            long collected = 0;
            int ticks = 0;
            while (collected < operations * FLUID_OUTPUT_PER_OPERATION && ticks++ < 40000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                collected += machine.getTank().extract(0, QUANTUM_INFUSION, Long.MAX_VALUE, Actionable.MODULATE);
                var batch = batch(machine);
                if (ticks == 1) helper.assertTrue(batch != null && batch.recipe().equals(FLUID_RECIPE),
                        "Custom path did not select the real fluid reaction recipe");
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), WATER) / WATER_PER_FLUID_OPERATION;
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), QUANTUM_INFUSION);
                long materialized = collected + tank(machine, 0) + pending;
                helper.assertTrue(materialized % FLUID_OUTPUT_PER_OPERATION == 0,
                        "Fluid reaction produced a partial operation at tick " + ticks + ": " + materialized);
                helper.assertTrue(materialized <= operations * FLUID_OUTPUT_PER_OPERATION,
                        "Fluid reaction duplicated its output at tick " + ticks);
                long consumed = materialized / FLUID_OUTPUT_PER_OPERATION + inFlight;
                helper.assertTrue(inputAmount(machine, 0) + consumed == operations,
                        "Fluid reaction lost infused dust at tick " + ticks);
                helper.assertTrue(tank(machine, 1) + consumed * WATER_PER_FLUID_OPERATION == operations * WATER_PER_FLUID_OPERATION,
                        "Fluid reaction lost water at tick " + ticks);
            }
            helper.assertTrue(collected == operations * FLUID_OUTPUT_PER_OPERATION,
                    "Fluid reaction did not produce every unit: " + collected);
            helper.assertTrue(inputAmount(machine, 0) == 0 && tank(machine, 1) == 0 && tank(machine, 0) == 0,
                    "Fluid reaction retained inputs or product");
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Fluid reaction retained a completed batch");
            helper.succeed();
        });
    }

    /**
     * Auto-export while the shared processor owns the batch. The chamber only exports to the sides the
     * player configured, and the one-stack output slot must hand every result to the adjacent chest
     * instead of stalling because upstream's export call site is bypassed.
     */
    static void autoExportConservation(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Reaction chamber grid is inactive");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_64X.get()));
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);
            machine.updateOutputSides(EnumSet.of(appeng.api.orientation.RelativeSide.TOP));
            Direction top = machine.getOrientation().getSide(appeng.api.orientation.RelativeSide.TOP);
            helper.setBlock(pos.relative(top), Blocks.CHEST);
            var chest = (ChestBlockEntity) helper.getBlockEntity(pos.relative(top));

            final long operations = 20;
            final long expected = operations * ITEM_OUTPUT_PER_OPERATION;
            inputSlot(machine, 0).write(new ResourceAmount<AEKey>(PRINT_LOGIC, operations * ITEMS_PER_OPERATION));
            inputSlot(machine, 1).write(new ResourceAmount<AEKey>(PRINT_SILICON, operations * ITEMS_PER_OPERATION));
            inputSlot(machine, 2).write(new ResourceAmount<AEKey>(REDSTONE, operations * ITEMS_PER_OPERATION));
            machine.getTank().setStack(1, new GenericStack(WATER, operations * WATER_PER_ITEM_OPERATION));

            int ticks = 0;
            long seen = 0;
            while (chestCount(chest, LOGIC_PROCESSOR) < expected && ticks++ < 40000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                var batch = batch(machine);
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), WATER) / WATER_PER_ITEM_OPERATION;
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), LOGIC_PROCESSOR);
                long produced = chestCount(chest, LOGIC_PROCESSOR) + outputAmount(machine) + pending;
                helper.assertTrue(produced >= seen && produced <= expected,
                        "Reaction output vanished or duplicated at tick " + ticks + ": " + produced);
                seen = produced;
                helper.assertTrue(produced % ITEM_OUTPUT_PER_OPERATION == 0,
                        "Reaction chamber produced a partial operation at tick " + ticks);
                long consumed = produced / ITEM_OUTPUT_PER_OPERATION + inFlight;
                for (int slot = 0; slot < 3; slot++)
                    helper.assertTrue(inputAmount(machine, slot) + consumed * ITEMS_PER_OPERATION == operations * ITEMS_PER_OPERATION,
                            "Reaction auto-export lost an item input at tick " + ticks);
                helper.assertTrue(tank(machine, 1) + consumed * WATER_PER_ITEM_OPERATION == operations * WATER_PER_ITEM_OPERATION,
                        "Reaction auto-export lost water at tick " + ticks);
            }
            helper.assertTrue(chestCount(chest, LOGIC_PROCESSOR) == expected,
                    "Reaction auto-export never delivered every result: " + chestCount(chest, LOGIC_PROCESSOR));
            helper.assertTrue(outputAmount(machine) == 0 && tank(machine, 1) == 0
                    && !machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Reaction chamber retained resources after auto-export");
            helper.succeed();
        });
    }

    /**
     * Destruction packaging. Both tank slots and the logical output overflow, the finished batch products
     * and the reserved inputs of an unfinished batch must leave the machine as bounded containers.
     */
    static void destructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("advanced_ae:reaction_chamber")));
        var machine = (ReactionChamberEntity) helper.getBlockEntity(pos);
        ManagedItemStorages.slots(machine.getOutput()).get(0).write(new ResourceAmount<AEKey>(LOGIC_PROCESSOR, 130));
        machine.getTank().setStack(1, new GenericStack(WATER, 4000));
        machine.getTank().setStack(0, new GenericStack(QUANTUM_INFUSION, 1000));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(ITEM_RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(LOGIC_PROCESSOR, 45)), 100, 100, 0)));
        machine.load(saved);

        var drops = new java.util.ArrayList<ItemStack>();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, LOGIC_PROCESSOR) == 111,
                "Reaction overflow or finished batch was not packaged exactly once: " + packedAmount(drops, LOGIC_PROCESSOR));
        helper.assertTrue(packedAmount(drops, WATER) == 4000, "Reaction chamber water input was destroyed");
        helper.assertTrue(packedAmount(drops, QUANTUM_INFUSION) == 1000, "Reaction chamber fluid output was destroyed");
        helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.STORED_RESOURCES.get())).count() == 4,
                "Unexpected stored-resource drop count: " + drops.size());

        saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(ITEM_RECIPE,
                List.of(new ResourceAmount<>(PRINT_LOGIC, 16), new ResourceAmount<>(REDSTONE, 16)),
                List.of(new ResourceAmount<>(LOGIC_PROCESSOR, 16)), 100, 25, 4)));
        machine.load(saved);

        drops.clear();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, PRINT_LOGIC) == 16 && packedAmount(drops, REDSTONE) == 16,
                "Unfinished batch did not return its reserved inputs");
        helper.assertTrue(packedAmount(drops, LOGIC_PROCESSOR) == 66,
                "Unfinished batch packaged products it never made: " + packedAmount(drops, LOGIC_PROCESSOR));
        helper.assertTrue(packedAmount(drops, WATER) == 4000 && packedAmount(drops, QUANTUM_INFUSION) == 1000,
                "Repeated destruction lost a tank slot");
        helper.succeed();
    }

    /**
     * Real destruction of a placed chamber. The visible input, the logical output that overflows a legal
     * stack, the products of a finished batch and both tank slots must reach the world exactly once when the
     * block is actually destroyed, not merely when the drop hook is invoked directly.
     */
    static void realDestructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("advanced_ae:reaction_chamber")));
        var machine = (ReactionChamberEntity) helper.getBlockEntity(pos);
        inputSlot(machine, 0).write(new ResourceAmount<AEKey>(PRINT_LOGIC, 3));
        ManagedItemStorages.slots(machine.getOutput()).get(0).write(new ResourceAmount<AEKey>(LOGIC_PROCESSOR, 130));
        machine.getTank().setStack(1, new GenericStack(WATER, 4000));
        machine.getTank().setStack(0, new GenericStack(QUANTUM_INFUSION, 1000));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(ITEM_RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(LOGIC_PROCESSOR, 45)), 100, 100, 0)));
        machine.load(saved);

        helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(pos), true, helper.makeMockPlayer()),
                "Reaction chamber destruction failed");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) == null,
                "Destroyed reaction chamber remains in the world");
        helper.runAfterDelay(2, () -> {
            var entities = helper.getEntities(net.minecraft.world.entity.EntityType.ITEM, pos, 4);
            helper.assertTrue(entityAmount(entities, LOGIC_PROCESSOR) == 175,
                    "Real destruction lost or duplicated reaction output: " + entityAmount(entities, LOGIC_PROCESSOR));
            helper.assertTrue(entityAmount(entities, PRINT_LOGIC) == 3,
                    "Real destruction lost the visible reaction input: " + entityAmount(entities, PRINT_LOGIC));
            helper.assertTrue(entityAmount(entities, WATER) == 4000, "Real destruction lost the water input");
            helper.assertTrue(entityAmount(entities, QUANTUM_INFUSION) == 1000,
                    "Real destruction lost the fluid product");
            helper.succeed();
        });
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
