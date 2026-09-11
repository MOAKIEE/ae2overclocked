package moakiee.ae2oc.platform.forge;

import java.util.List;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEBlocks;
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
import net.minecraftforge.registries.ForgeRegistries;
import io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity;

/**
 * Loaded only when AE2 Crystal Science is present. Drives the real {@code ae2cs:aggregator/logic_processor}
 * recipe, which fuses 32 printed logic circuits, 32 redstone dust and 32 printed silicon into 32 logic
 * processors for 51200 AE. One unit of every input maps to exactly one output unit, so all three input
 * slots, the pending batch and the collected output must add up to the planned operation count.
 */
final class AE2CSAggregatorRecipes {
    private static final String RECIPE = "ae2cs:aggregator/logic_processor";
    private static final AEItemKey PRINTED_LOGIC = item("ae2:printed_logic_processor");
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey PRINTED_SILICON = item("ae2:printed_silicon");
    private static final AEItemKey OUTPUT = item("ae2:logic_processor");
    /** Input slots 0..2 in the same order as the recipe's declared ingredients. */
    private static final AEItemKey[] INPUTS = {PRINTED_LOGIC, REDSTONE, PRINTED_SILICON};

    private AE2CSAggregatorRecipes() {}

    private static AEItemKey item(String id) {
        Item value = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
        if (value == null) throw new IllegalStateException("Missing item " + id);
        return AEItemKey.of(value);
    }

    private static CrystalAggregatorBlockEntity place(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_aggregator")));
        return (CrystalAggregatorBlockEntity) helper.getBlockEntity(pos);
    }

    private static LocalResourceSlot inputSlot(CrystalAggregatorBlockEntity machine, int slot) {
        return ManagedItemStorages.slots(machine.getInputInv()).get(slot);
    }

    private static LocalResourceSlot outputSlot(CrystalAggregatorBlockEntity machine) {
        return ManagedItemStorages.slots(machine.getOutputInv()).get(0);
    }

    private static ProcessingState<AEKey> batch(CrystalAggregatorBlockEntity machine) {
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

    /**
     * Twelve upgrade combinations. Each operation consumes 32 of every input and produces 32 processors,
     * so the pending batch, the local output slot and everything already extracted must constantly account
     * for the inputs that left the slots, on both the upstream fallback and the shared processor.
     */
    static void recipeMatrix(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Aggregator grid is inactive");
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            Item[] cards = {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            if (cards[tier] != null) machine.getUpgrades().setItemDirect(0, new ItemStack(cards[tier]));
            if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));

            // Multiples of the recipe's ingredient count so the upstream path can consume them completely.
            long operations = new long[] {32, 64, 128, 384, 1024, 2464}[tier];
            for (int slot = 0; slot < INPUTS.length; slot++) {
                inputSlot(machine, slot).write(new ResourceAmount<AEKey>(INPUTS[slot], operations));
            }
            String label = "tier=" + tier + ", overclock=" + overclock;

            long collected = 0;
            int ticks = 0;
            while (collected < operations && ticks++ < 60000) {
                machine.serverTick();
                var output = machine.getOutputInv().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || OUTPUT.matches(output) && output.getCount() <= output.getMaxStackSize(),
                        "Wrong or oversized aggregator output: " + label);
                collected += output.getCount();
                var batch = batch(machine);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    helper.assertTrue(batch != null && batch.recipe().equals(RECIPE),
                            "Custom path did not select the real aggregator recipe: " + label);
                }
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), PRINTED_LOGIC);
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), OUTPUT);
                long consumed = collected + amount(outputSlot(machine)) + pending + inFlight;
                for (int slot = 0; slot < INPUTS.length; slot++) {
                    helper.assertTrue(amount(inputSlot(machine, slot)) + consumed == operations,
                            "Aggregator conservation failed for slot " + slot + ": " + label + ", tick=" + ticks
                                    + ", left=" + amount(inputSlot(machine, slot)) + ", consumed=" + consumed);
                }
            }
            helper.assertTrue(collected == operations, "Aggregator recipe did not complete: " + label);
            for (int slot = 0; slot < INPUTS.length; slot++) {
                helper.assertTrue(amount(inputSlot(machine, slot)) == 0,
                        "Aggregator retained input in slot " + slot + ": " + label);
            }
            helper.assertTrue(amount(outputSlot(machine)) == 0, "Aggregator retained output: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Aggregator retained a completed batch: " + label);
            recipeMatrix(helper, scenario + 1);
        });
    }

    /**
     * Destruction packaging: the visible projection, the logical overflow, the reserved inputs of an
     * unfinished batch and the products of a finished one must leave the machine as bounded containers
     * and must never be counted twice.
     */
    static void destructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_aggregator")));
        var machine = (CrystalAggregatorBlockEntity) helper.getBlockEntity(pos);
        outputSlot(machine).write(new ResourceAmount<AEKey>(OUTPUT, 130));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(OUTPUT, 45)), 100, 100, 0)));
        machine.load(saved);

        var drops = new java.util.ArrayList<ItemStack>();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, OUTPUT) == 111,
                "Aggregator overflow or finished batch was not packaged exactly once: " + packedAmount(drops, OUTPUT));
        helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.STORED_RESOURCES.get())).count() == 2,
                "Unexpected stored-resource drop count: " + drops.size());

        saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.of(new ResourceAmount<>(PRINTED_LOGIC, 12)), List.of(new ResourceAmount<>(OUTPUT, 12)), 100, 25, 4)));
        machine.load(saved);

        drops.clear();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, PRINTED_LOGIC) == 12,
                "Unfinished aggregator batch did not return its reserved input");
        helper.assertTrue(packedAmount(drops, OUTPUT) == 66,
                "Unfinished aggregator batch packaged products it never made");
        helper.succeed();
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
}
