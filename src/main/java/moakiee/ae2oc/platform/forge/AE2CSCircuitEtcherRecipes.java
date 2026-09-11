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
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity;

/**
 * Loaded only when AE2 Crystal Science is present. Drives the real {@code ae2cs:circuit_etcher/logic_processor}
 * recipe, which etches 4 gold blocks, 4 redstone blocks and 4 silicon blocks into 36 logic processors for
 * 14400 AE. Inputs and outputs use a 4:36 ratio, so the removed inputs, the pending batch, the local output
 * slot and everything already extracted must stay in that ratio on both paths.
 */
final class AE2CSCircuitEtcherRecipes {
    private static final String RECIPE = "ae2cs:circuit_etcher/logic_processor";
    private static final int IN_PER_OP = 4;
    private static final int OUT_PER_OP = 36;
    private static final AEItemKey GOLD_BLOCK = AEItemKey.of(Blocks.GOLD_BLOCK);
    private static final AEItemKey REDSTONE_BLOCK = AEItemKey.of(Blocks.REDSTONE_BLOCK);
    private static final AEItemKey SILICON_BLOCK = item("ae2cs:silicon_block");
    private static final AEItemKey OUTPUT = item("ae2:logic_processor");
    /** Input slots 0..2 in the same order as the recipe's declared ingredients. */
    private static final AEItemKey[] INPUTS = {GOLD_BLOCK, REDSTONE_BLOCK, SILICON_BLOCK};

    private AE2CSCircuitEtcherRecipes() {}

    private static AEItemKey item(String id) {
        Item value = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
        if (value == null) throw new IllegalStateException("Missing item " + id);
        return AEItemKey.of(value);
    }

    private static CircuitEtcherBlockEntity place(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:circuit_etcher")));
        return (CircuitEtcherBlockEntity) helper.getBlockEntity(pos);
    }

    private static LocalResourceSlot inputSlot(CircuitEtcherBlockEntity machine, int slot) {
        return ManagedItemStorages.slots(machine.getInputInv()).get(slot);
    }

    private static LocalResourceSlot outputSlot(CircuitEtcherBlockEntity machine) {
        return ManagedItemStorages.slots(machine.getOutputInv()).get(0);
    }

    private static ProcessingState<AEKey> batch(CircuitEtcherBlockEntity machine) {
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
     * Twelve upgrade combinations. Every operation removes exactly four units of each input and publishes
     * thirty-six products, so for each input slot the units that left the slot must always correspond to the
     * products already made, on both the upstream fallback and the shared processor.
     */
    static void recipeMatrix(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Circuit etcher grid is inactive");
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            Item[] cards = {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            if (cards[tier] != null) machine.getUpgrades().setItemDirect(0, new ItemStack(cards[tier]));
            if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));

            long operations = new long[] {1, 2, 4, 12, 32, 77}[tier];
            long[] initial = new long[INPUTS.length];
            for (int slot = 0; slot < INPUTS.length; slot++) {
                initial[slot] = operations * IN_PER_OP;
                inputSlot(machine, slot).write(new ResourceAmount<AEKey>(INPUTS[slot], initial[slot]));
            }
            long expected = operations * OUT_PER_OP;
            String label = "tier=" + tier + ", overclock=" + overclock;

            long collected = 0;
            int ticks = 0;
            while (collected < expected && ticks++ < 60000) {
                machine.serverTick();
                var output = machine.getOutputInv().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || OUTPUT.matches(output) && output.getCount() <= output.getMaxStackSize(),
                        "Wrong or oversized etcher output: " + label);
                collected += output.getCount();
                var batch = batch(machine);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    helper.assertTrue(batch != null && batch.recipe().equals(RECIPE),
                            "Custom path did not select the real etcher recipe: " + label);
                }
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), OUTPUT);
                long produced = collected + amount(outputSlot(machine)) + pending;
                for (int slot = 0; slot < INPUTS.length; slot++) {
                    long reserved = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), INPUTS[slot]);
                    long consumed = initial[slot] - amount(inputSlot(machine, slot)) - reserved;
                    helper.assertTrue(consumed * OUT_PER_OP == produced * IN_PER_OP,
                            "Etcher conservation failed for slot " + slot + ": " + label + ", tick=" + ticks
                                    + ", consumed=" + consumed + ", produced=" + produced);
                }
            }
            helper.assertTrue(collected == expected, "Etcher recipe did not complete: " + label);
            for (int slot = 0; slot < INPUTS.length; slot++) {
                helper.assertTrue(amount(inputSlot(machine, slot)) == 0,
                        "Etcher retained input in slot " + slot + ": " + label);
            }
            helper.assertTrue(amount(outputSlot(machine)) == 0, "Etcher retained output: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Etcher retained a completed batch: " + label);
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
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:circuit_etcher")));
        var machine = (CircuitEtcherBlockEntity) helper.getBlockEntity(pos);
        outputSlot(machine).write(new ResourceAmount<AEKey>(OUTPUT, 130));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(OUTPUT, 72)), 100, 100, 0)));
        machine.load(saved);

        var drops = new java.util.ArrayList<ItemStack>();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, OUTPUT) == 138,
                "Etcher overflow or finished batch was not packaged exactly once: " + packedAmount(drops, OUTPUT));
        helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.STORED_RESOURCES.get())).count() == 2,
                "Unexpected stored-resource drop count: " + drops.size());

        saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.of(new ResourceAmount<>(GOLD_BLOCK, 8), new ResourceAmount<>(REDSTONE_BLOCK, 8),
                        new ResourceAmount<>(SILICON_BLOCK, 8)),
                List.of(new ResourceAmount<>(OUTPUT, 72)), 100, 25, 4)));
        machine.load(saved);

        drops.clear();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, GOLD_BLOCK) == 8 && packedAmount(drops, REDSTONE_BLOCK) == 8
                        && packedAmount(drops, SILICON_BLOCK) == 8,
                "Unfinished etcher batch did not return all reserved inputs");
        helper.assertTrue(packedAmount(drops, OUTPUT) == 66,
                "Unfinished etcher batch packaged products it never made");
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
