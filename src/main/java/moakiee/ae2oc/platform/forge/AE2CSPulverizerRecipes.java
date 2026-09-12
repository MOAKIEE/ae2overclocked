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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity;

/**
 * Loaded only when AE2 Crystal Science is present. Drives the real {@code ae2cs:pulverizer/gunpowder}
 * recipe, which grinds one flint into one gunpowder for 8000 AE, across every parallel tier.
 */
final class AE2CSPulverizerRecipes {
    private static final String RECIPE = "ae2cs:pulverizer/gunpowder";
    private static final AEItemKey FLINT = AEItemKey.of(Items.FLINT);
    private static final AEItemKey GUNPOWDER = AEItemKey.of(Items.GUNPOWDER);

    private AE2CSPulverizerRecipes() {}

    private static CrystalPulverizerBlockEntity place(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_pulverizer")));
        return (CrystalPulverizerBlockEntity) helper.getBlockEntity(pos);
    }

    private static LocalResourceSlot inputSlot(CrystalPulverizerBlockEntity machine) {
        return ManagedItemStorages.slots(machine.getInputInv()).get(0);
    }

    private static LocalResourceSlot outputSlot(CrystalPulverizerBlockEntity machine) {
        return ManagedItemStorages.slots(machine.getOutputInv()).get(0);
    }

    private static ProcessingState<AEKey> batch(CrystalPulverizerBlockEntity machine) {
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
     * Twelve upgrade combinations. One flint becomes exactly one gunpowder, so the flint count, the
     * pending batch, the local output slot and everything already collected must always add up to the
     * planned operation count, on both the upstream fallback and the shared processor.
     */
    static void recipeMatrix(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Pulverizer grid is inactive");
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            Item[] cards = {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            if (cards[tier] != null) machine.getUpgrades().setItemDirect(0, new ItemStack(cards[tier]));
            if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));

            long operations = new long[] {24, 4, 8, 40, 120, 300}[tier];
            inputSlot(machine).write(new ResourceAmount<AEKey>(FLINT, operations));
            String label = "tier=" + tier + ", overclock=" + overclock;

            long collected = 0;
            int ticks = 0;
            while (collected < operations && ticks++ < 40000) {
                machine.serverTick();
                var output = machine.getOutputInv().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || GUNPOWDER.matches(output) && output.getCount() <= output.getMaxStackSize(),
                        "Wrong or oversized pulverizer output: " + label);
                collected += output.getCount();
                var batch = batch(machine);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    helper.assertTrue(batch != null && batch.recipe().equals(RECIPE),
                            "Custom path did not select the real pulverizer recipe: " + label);
                }
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), FLINT);
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), GUNPOWDER);
                long consumed = collected + amount(outputSlot(machine)) + pending + inFlight;
                helper.assertTrue(amount(inputSlot(machine)) + consumed == operations,
                        "Pulverizer conservation failed: " + label + ", tick=" + ticks
                                + ", left=" + amount(inputSlot(machine)) + ", consumed=" + consumed);
            }
            helper.assertTrue(collected == operations, "Pulverizer recipe did not complete: " + label);
            helper.assertTrue(amount(inputSlot(machine)) == 0 && amount(outputSlot(machine)) == 0,
                    "Pulverizer retained inputs or output: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Pulverizer retained a completed batch: " + label);
            recipeMatrix(helper, scenario + 1);
        });
    }

    static void certusQuartzRecipe(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        var machine = place(helper, pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Pulverizer grid is inactive");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_64X.get()));
            machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getUpgrades().setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));

            var certusCrystal = AEItemKey.of(appeng.core.definitions.AEItems.CERTUS_QUARTZ_CRYSTAL);
            var certusDust = AEItemKey.of(appeng.core.definitions.AEItems.CERTUS_QUARTZ_DUST);
            long operations = 32;
            inputSlot(machine).write(new ResourceAmount<>(certusCrystal, operations));
            String label = "ae2cs:pulverizer/certus_quartz_dust";

            long collected = 0;
            int ticks = 0;
            while (collected < operations && ticks++ < 40000) {
                machine.serverTick();
                var output = machine.getOutputInv().extractItem(0, 64, false);
                helper.assertTrue(output.isEmpty() || certusDust.matches(output) && output.getCount() <= output.getMaxStackSize(),
                        "Wrong or oversized pulverizer output: " + label);
                collected += output.getCount();
                var batch = batch(machine);
                if (ticks == 1) {
                    helper.assertTrue(batch != null && batch.recipe().equals(label),
                            "Custom path did not select recipe: " + label);
                }
                long inFlight = batch == null || batch.finished() ? 0 : amountOf(batch.inputs(), certusCrystal);
                long pending = batch == null || !batch.finished() ? 0 : amountOf(batch.outputs(), certusDust);
                long consumed = collected + amount(outputSlot(machine)) + pending + inFlight;
                helper.assertTrue(amount(inputSlot(machine)) + consumed == operations,
                        "Pulverizer conservation failed: " + label + ", tick=" + ticks);
            }
            helper.assertTrue(collected == operations, "Pulverizer recipe did not complete: " + label);
            helper.assertTrue(amount(inputSlot(machine)) == 0 && amount(outputSlot(machine)) == 0,
                    "Pulverizer retained inputs or output: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"),
                    "Pulverizer retained a completed batch: " + label);
            helper.succeed();
        });
    }

    /**
     * Destruction packaging: the visible projection, the logical overflow, the reserved inputs of an
     * unfinished batch and the products of a finished one must leave the machine as bounded containers
     * and must never be counted twice.
     */
    static void destructionDrops(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_pulverizer")));
        var machine = (CrystalPulverizerBlockEntity) helper.getBlockEntity(pos);
        outputSlot(machine).write(new ResourceAmount<AEKey>(GUNPOWDER, 130));

        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.<ResourceAmount<AEKey>>of(), List.of(new ResourceAmount<>(GUNPOWDER, 45)), 100, 100, 0)));
        machine.load(saved);

        var drops = new java.util.ArrayList<ItemStack>();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, GUNPOWDER) == 111,
                "Pulverizer overflow or finished batch was not packaged exactly once: " + packedAmount(drops, GUNPOWDER));
        helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.STORED_RESOURCES.get())).count() == 2,
                "Unexpected stored-resource drop count: " + drops.size());

        saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.of(new ResourceAmount<>(FLINT, 12)), List.of(new ResourceAmount<>(GUNPOWDER, 12)), 100, 25, 4)));
        machine.load(saved);

        drops.clear();
        machine.addAdditionalDrops(helper.getLevel(), helper.absolutePos(pos), drops);
        helper.assertTrue(packedAmount(drops, FLINT) == 12, "Unfinished batch did not return its reserved input");
        helper.assertTrue(packedAmount(drops, GUNPOWDER) == 66, "Unfinished batch packaged products it never made");
        helper.succeed();
    }

    static void allOutputSlots(GameTestHelper helper) {
        var partial = placeWithFinishedOutput(helper, new BlockPos(1, 1, 1), 5);
        partial.getOutputInv().setItemDirect(0, new ItemStack(Items.DIRT, 64));
        partial.getOutputInv().setItemDirect(1, new ItemStack(Items.GUNPOWDER, 60));
        partial.getOutputInv().setItemDirect(3, new ItemStack(Items.DIRT, 64));
        partial.serverTick();
        helper.assertTrue(outputAmount(partial, GUNPOWDER) == 65,
                "Pulverizer did not spread output across its remaining legal slots");
        helper.assertTrue(batch(partial) == null, "Pulverizer retained output accepted across multiple slots");

        var blocked = placeWithFinishedOutput(helper, new BlockPos(4, 1, 1), 5);
        for (int slot = 0; slot < blocked.getOutputInv().size(); slot++) {
            blocked.getOutputInv().setItemDirect(slot, new ItemStack(Items.DIRT, 64));
        }
        blocked.serverTick();
        var pending = batch(blocked);
        helper.assertTrue(pending != null && pending.finished() && amountOf(pending.outputs(), GUNPOWDER) == 5,
                "Full pulverizer output discarded or altered pending results");
        helper.succeed();
    }

    static void legacyComponentInventory(GameTestHelper helper) {
        var machine = placeWithLegacyInput(helper, new BlockPos(1, 1, 1), true);
        var restored = inputSlot(machine).read();
        helper.assertTrue(restored != null && restored.key().equals(FLINT) && restored.amount() == 10_000,
                "Pulverizer did not load the legacy inv_input/inv_work component aliases exactly");

        var wrongField = placeWithLegacyInput(helper, new BlockPos(4, 1, 1), false);
        helper.assertTrue(inputSlot(wrongField).read() == null,
                "Negative control unexpectedly accepted the obsolete root inv field");
        helper.succeed();
    }

    private static CrystalPulverizerBlockEntity placeWithLegacyInput(GameTestHelper helper, BlockPos pos,
            boolean componentFields) {
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_pulverizer")));
        var machine = (CrystalPulverizerBlockEntity) helper.getBlockEntity(pos);
        var saved = machine.saveWithFullMetadata();
        var inventory = new ListTag();
        var item = new ItemStack(Items.FLINT, 64).save(new CompoundTag());
        item.putInt("Slot", 0);
        item.putInt("ae2ocCount", 10_000);
        inventory.add(item);
        if (componentFields) {
            saved.put("inv_input", inventory.copy());
            saved.put("inv_work", inventory.copy());
        } else {
            saved.put("inv", inventory);
        }
        machine.load(saved);
        return machine;
    }

    private static CrystalPulverizerBlockEntity placeWithFinishedOutput(GameTestHelper helper, BlockPos pos, long amount) {
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:crystal_pulverizer")));
        var machine = (CrystalPulverizerBlockEntity) helper.getBlockEntity(pos);
        var saved = machine.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>(RECIPE,
                List.of(), List.of(new ResourceAmount<>(GUNPOWDER, amount)), 0, 0, 0)));
        machine.load(saved);
        return machine;
    }

    private static long outputAmount(CrystalPulverizerBlockEntity machine, AEKey key) {
        long total = 0;
        for (var slot : ManagedItemStorages.slots(machine.getOutputInv())) {
            var value = slot.read();
            if (value != null && value.key().equals(key)) total += value.amount();
        }
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
}
