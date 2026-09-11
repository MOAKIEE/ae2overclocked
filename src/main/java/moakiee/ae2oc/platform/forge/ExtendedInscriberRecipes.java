package moakiee.ae2oc.platform.forge;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/** Loaded only when ExtendedAE is present. All four lanes run real upstream recipes. */
final class ExtendedInscriberRecipes {
    static void run(GameTestHelper helper, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(new ResourceLocation("expatternprovider:ex_inscriber")));
        var machine = (TileExInscriber) helper.getBlockEntity(pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Extended inscriber grid is inactive");
            var cards = new net.minecraft.world.item.Item[] {null, ModItems.PARALLEL_CARD.get(), ModItems.PARALLEL_CARD_8X.get(),
                    ModItems.PARALLEL_CARD_64X.get(), ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            if (cards[tier] != null) machine.getUpgrades().setItemDirect(0, new ItemStack(cards[tier]));
            if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);
            int operations = new int[] {70, 5, 17, 130, 2050, 4097}[tier];
            for (int lane = 0; lane < 4; lane++) {
                boolean press = lane % 2 != 0;
                var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                slots.get(0).write(new ResourceAmount<AEKey>(AEItemKey.of(press ? AEItems.LOGIC_PROCESSOR_PRINT.asItem() : AEItems.LOGIC_PROCESSOR_PRESS.asItem()), press ? operations : 1));
                if (press) slots.get(1).write(new ResourceAmount<AEKey>(AEItemKey.of(AEItems.SILICON_PRINT.asItem()), operations));
                slots.get(2).write(new ResourceAmount<AEKey>(AEItemKey.of(press ? Items.REDSTONE : Items.GOLD_INGOT), operations));
                helper.assertTrue(machine.getTask(lane) != null, "Missing real recipe in lane " + lane);
            }
            long[] collected = new long[4];
            int ticks = 0;
            while (java.util.Arrays.stream(collected).sum() < operations * 4L && ticks++ < 20000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                var saved = machine.saveWithFullMetadata();
                for (int lane = 0; lane < 4; lane++) {
                    var inventory = machine.getIndexInventory(lane);
                    var slots = ManagedItemStorages.slots(inventory);
                    boolean press = lane % 2 != 0;
                    var middle = AEItemKey.of(press ? Items.REDSTONE : Items.GOLD_INGOT);
                    var result = AEItemKey.of(press ? AEItems.LOGIC_PROCESSOR.asItem() : AEItems.LOGIC_PROCESSOR_PRINT.asItem());
                    String label = "scenario=" + scenario + ", lane=" + lane + ", tick=" + ticks;
                    if (ticks == 1 && (tier > 0 || overclock)) {
                        long expected = Math.min(operations, Math.min(moakiee.ae2oc.compat.ae2.UpgradeProfileCache.of(machine).parallelLimit(),
                                moakiee.Ae2OcConfig.getMaxRecipeOperationsPerMachineTick() / 4));
                        helper.assertTrue(operations - amount(slots.get(2)) == expected, "Wrong shared recipe budget: " + label);
                    }
                    var output = inventory.extractItem(3, 64, false);
                    helper.assertTrue(output.isEmpty() || result.matches(output) && output.getCount() <= 64, "Invalid output: " + label);
                    collected[lane] += output.getCount();
                    var child = saved.getCompound("ae2ocThread" + lane);
                    long pending = 0;
                    if (child.contains("ae2ocProcessing")) {
                        var batch = ProcessingCodec.read(child.getCompound("ae2ocProcessing"));
                        pending = batch.finished() ? batch.outputs().stream().mapToLong(ResourceAmount::amount).sum()
                                : batch.inputs().stream().filter(input -> input.key().equals(middle)).mapToLong(ResourceAmount::amount).sum();
                    }
                    helper.assertTrue(collected[lane] + pending + amount(slots.get(2)) + amount(slots.get(3)) == operations,
                            "Extended recipe conservation failed: " + label);
                }
                // Reload every lane together after the first paid processing step.
                if (ticks == 1 && (tier > 0 || overclock)) {
                    var current = machine.saveWithFullMetadata();
                    machine.load(current);
                    machine.load(current);
                }
            }
            for (int lane = 0; lane < 4; lane++) {
                var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                helper.assertTrue(collected[lane] == operations && amount(slots.get(0)) == (lane % 2 == 0 ? 1 : 0)
                        && amount(slots.get(1)) == 0 && amount(slots.get(2)) == 0, "Incomplete lane " + lane + " in scenario " + scenario);
                helper.assertTrue(!machine.saveWithFullMetadata().getCompound("ae2ocThread" + lane).contains("ae2ocProcessing"), "Completed lane retained a batch");
            }
            run(helper, scenario + 1);
        });
    }

    private static long amount(moakiee.ae2oc.compat.ae2.LocalResourceSlot slot) {
        var value = slot.read();
        return value == null ? 0 : value.amount();
    }

    /**
     * Lane 0 auto-export conservation. The unupgraded lane goes through the upstream tick and its patched
     * {@code pushOutResult}; the parallel-card lane goes through the custom tick path. Both must deliver a
     * whole legal stack and preserve the over-capacity remainder instead of reinserting it.
     */
    static void exportConservation(GameTestHelper helper, boolean upgraded) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(new ResourceLocation("expatternprovider:ex_inscriber")));
        helper.setBlock(pos.east(), Blocks.CHEST);
        var machine = (TileExInscriber) helper.getBlockEntity(pos);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(pos.east());
        if (upgraded) machine.getUpgrades().setItemDirect(0, new net.minecraft.world.item.ItemStack(ModItems.PARALLEL_CARD.get()));
        var output = ManagedItemStorages.slots(machine.getIndexInventory(0)).get(3);
        output.write(new ResourceAmount<AEKey>(AEItemKey.of(Items.GOLD_INGOT), 130));
        for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 63));
        machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);
        machine.tickingRequest(null, 1);
        helper.assertTrue(amount(output) == 130 && chest.getItem(0).getCount() == 63, "Disabled auto-export moved items");
        machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);
        machine.tickingRequest(null, 1);
        helper.assertTrue(chest.getItem(0).getCount() == 64, "Auto-export did not deliver to adjacent inventory");
        helper.assertTrue(amount(output) == 129, "Partial auto-export lost overflow items");
        machine.tickingRequest(null, 1);
        helper.assertTrue(amount(output) == 129, "Full destination lost overflow items");
        helper.setBlock(pos.east(), Blocks.AIR);
        var top = machine.getTop();
        helper.setBlock(pos.relative(top), Blocks.CHEST);
        var topChest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(pos.relative(top));
        machine.getConfigManager().putSetting(Settings.INSCRIBER_SEPARATE_SIDES, YesNo.YES);
        machine.tickingRequest(null, 1);
        helper.assertTrue(topChest.isEmpty() && amount(output) == 129, "Separate sides exported through the top");
        machine.getConfigManager().putSetting(Settings.INSCRIBER_SEPARATE_SIDES, YesNo.NO);
        machine.tickingRequest(null, 1);
        helper.assertTrue(topChest.getItem(0).getCount() == 64 && amount(output) == 65, "Combined sides did not export a legal stack");
        helper.succeed();
    }

    /** Each lane owns its own output slot and exports only that lane's resources. */
    static void laneExportIsolation(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(new ResourceLocation("expatternprovider:ex_inscriber")));
        helper.setBlock(pos.east(), Blocks.CHEST);
        var machine = (TileExInscriber) helper.getBlockEntity(pos);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(pos.east());
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var diamond = AEItemKey.of(Items.DIAMOND);
        var lane0 = ManagedItemStorages.slots(machine.getIndexInventory(0)).get(3);
        var lane1 = ManagedItemStorages.slots(machine.getIndexInventory(1)).get(3);
        lane0.write(new ResourceAmount<AEKey>(gold, 64));
        lane1.write(new ResourceAmount<AEKey>(diamond, 64));
        machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);
        machine.tickingRequest(null, 1);
        helper.assertTrue(amount(lane0) == 0 && amount(lane1) == 0, "A lane did not export its own output");
        long goldInChest = 0, diamondInChest = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            var stack = chest.getItem(slot);
            if (gold.matches(stack)) goldInChest += stack.getCount();
            if (diamond.matches(stack)) diamondInChest += stack.getCount();
        }
        helper.assertTrue(goldInChest == 64 && diamondInChest == 64,
                "Lane outputs were mixed or lost: gold=" + goldInChest + ", diamond=" + diamondInChest);
        helper.succeed();
    }
}
