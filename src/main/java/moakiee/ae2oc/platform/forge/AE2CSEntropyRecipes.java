package moakiee.ae2oc.platform.forge;

import java.util.List;
import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEBlocks;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity;

/**
 * Loaded only when AE2 Crystal Science is present. The entropy variation reaction chamber wraps AE2's own
 * {@code ae2:entropy} recipes; this drives the real {@code ae2:entropy/heat/cobblestone_stone} recipe in
 * HEAT mode, turning every cobblestone into one stone. Its generic input slot owns a single key while the
 * batch reserves exact amounts, so the remaining input, the pending batch, the local output slots and
 * everything already extracted must always add up to the planned operation count.
 */
final class AE2CSEntropyRecipes {
    private static final String RECIPE = "ae2:entropy/heat/cobblestone_stone";
    private static final AEItemKey COBBLESTONE = AEItemKey.of(Blocks.COBBLESTONE);
    private static final AEItemKey STONE = AEItemKey.of(Blocks.STONE);

    private AE2CSEntropyRecipes() {}

    private static EntropyVariationReactionChamberBlockEntity place(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(
                ResourceLocation.tryParse("ae2cs:entropy_variation_reaction_chamber")));
        return (EntropyVariationReactionChamberBlockEntity) helper.getBlockEntity(pos);
    }

    private static ProcessingState<AEKey> batch(EntropyVariationReactionChamberBlockEntity machine) {
        var tag = machine.saveWithFullMetadata();
        return tag.contains("ae2ocProcessing") ? ProcessingCodec.read(tag.getCompound("ae2ocProcessing")) : null;
    }

    private static long amountOf(List<ResourceAmount<AEKey>> resources, AEKey key) {
        long total = 0;
        for (var resource : resources) if (resource.key().equals(key)) total += resource.amount();
        return total;
    }

    private static long inputAmount(EntropyVariationReactionChamberBlockEntity machine) {
        var value = LocalResourceSlot.generic(machine.getInputInv(), 0).read();
        return value == null ? 0 : value.amount();
    }

    private static long outputAmount(EntropyVariationReactionChamberBlockEntity machine) {
        long total = 0;
        var inv = machine.getOutputInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            var value = LocalResourceSlot.generic(inv, slot).read();
            if (value != null) total += value.amount();
        }
        return total;
    }

    /** Empties every local output slot; only the recipe's product may ever appear there. */
    private static long drain(GameTestHelper helper, EntropyVariationReactionChamberBlockEntity machine, String label) {
        long total = 0;
        var inv = machine.getOutputInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            var value = LocalResourceSlot.generic(inv, slot).read();
            if (value == null) continue;
            helper.assertTrue(value.key().equals(STONE) && value.amount() > 0, "Wrong entropy output: " + label);
            long extracted = inv.extract(slot, value.key(), value.amount(), Actionable.MODULATE);
            helper.assertTrue(extracted == value.amount(), "Partial entropy extraction: " + label);
            total += extracted;
        }
        return total;
    }

    /**
     * Twelve upgrade combinations. One cobblestone becomes exactly one stone, so the leftover input, the
     * pending batch, the local output slots and everything already extracted must always account for the
     * whole planned operation count, on both the upstream fallback and the shared processor.
     */
    static void recipeMatrix(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Entropy chamber grid is inactive");
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            Item[] cards = {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            if (cards[tier] != null) machine.getUpgrades().setItemDirect(0, new ItemStack(cards[tier]));
            if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));

            long operations = new long[] {8, 16, 32, 96, 256, 640}[tier];
            LocalResourceSlot.generic(machine.getInputInv(), 0)
                    .write(new ResourceAmount<AEKey>(COBBLESTONE, operations));
            String label = "tier=" + tier + ", overclock=" + overclock;

            long collected = 0;
            int ticks = 0;
            while (collected < operations && ticks++ < 60000) {
                machine.serverTick();
                collected += drain(helper, machine, label);
                var batch = batch(machine);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    helper.assertTrue(batch != null && batch.recipe().equals(RECIPE),
                            "Custom path did not select the real entropy recipe: " + label);
                }
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), COBBLESTONE);
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), STONE);
                long accounted = inputAmount(machine) + collected + outputAmount(machine) + pending + inFlight;
                helper.assertTrue(accounted == operations,
                        "Entropy conservation failed: " + label + ", tick=" + ticks + ", accounted=" + accounted);
            }
            helper.assertTrue(collected == operations, "Entropy recipe did not complete: " + label);
            helper.assertTrue(inputAmount(machine) == 0, "Entropy chamber retained input: " + label);
            helper.assertTrue(outputAmount(machine) == 0, "Entropy chamber retained output: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Entropy chamber retained a completed batch: " + label);
            recipeMatrix(helper, scenario + 1);
        });
    }

    /**
     * Destruction packaging: the reserved input of an unfinished batch and the products of a finished one
     * must leave the machine as bounded containers and must never be counted twice.
     */
    static void destructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(
                ResourceLocation.tryParse("ae2cs:entropy_variation_reaction_chamber")));
        var machine = (EntropyVariationReactionChamberBlockEntity) helper.getBlockEntity(pos);

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.of(new ResourceAmount<>(COBBLESTONE, 12)), List.of(new ResourceAmount<>(STONE, 12)), 100, 25, 4)));
        machine.load(saved);

        var drops = new java.util.ArrayList<ItemStack>();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, COBBLESTONE) == 12,
                "Unfinished entropy batch did not return its reserved input");
        helper.assertTrue(packedAmount(drops, STONE) == 0,
                "Unfinished entropy batch packaged products it never made");

        saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(STONE, 45)), 100, 100, 0)));
        machine.load(saved);

        drops.clear();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, STONE) == 45,
                "Finished entropy batch was not packaged exactly once: " + packedAmount(drops, STONE));
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
