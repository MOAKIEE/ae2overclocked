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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos.east());
        if (upgraded) machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
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
        var topChest = (ChestBlockEntity) helper.getBlockEntity(pos.relative(top));
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
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos.east());
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

    /**
     * Real recipes in all four lanes with auto-export enabled. Even lanes press gold into a printed logic
     * circuit, odd lanes print silicon and redstone into a logic processor. Inputs may only shrink, outputs
     * may only grow and never exceed the recipe count, and every produced unit must be reachable in the
     * lane output slot, the owned batch or the chest. Nothing may vanish between the custom processing
     * path, the drain and the lane export.
     */
    static void recipeWithAutoExport(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(new ResourceLocation("expatternprovider:ex_inscriber")));
        helper.setBlock(pos.east(), Blocks.CHEST);
        var machine = (TileExInscriber) helper.getBlockEntity(pos);
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos.east());
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Auto-export recipe grid is inactive");
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);
            machine.getConfigManager().putSetting(Settings.INSCRIBER_SEPARATE_SIDES, YesNo.NO);
            final int operations = 6;
            var press = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRESS.asItem());
            var print = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRINT.asItem());
            var processor = AEItemKey.of(AEItems.LOGIC_PROCESSOR.asItem());
            var silicon = AEItemKey.of(AEItems.SILICON_PRINT.asItem());
            var gold = AEItemKey.of(Items.GOLD_INGOT);
            var redstone = AEItemKey.of(Items.REDSTONE);
            for (int lane = 0; lane < 4; lane++) {
                boolean pressing = lane % 2 != 0;
                var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                slots.get(0).write(new ResourceAmount<AEKey>(pressing ? print : press, pressing ? operations : 1));
                if (pressing) slots.get(1).write(new ResourceAmount<AEKey>(silicon, operations));
                slots.get(2).write(new ResourceAmount<AEKey>(pressing ? redstone : gold, operations));
            }
            long expected = operations * 2L;
            int ticks = 0;
            long seenPrint = 0, seenProcessor = 0;
            long goldLeft = expected, redstoneLeft = expected, siliconLeft = expected;
            long printInputLeft = expected;
            while (ticks++ < 4000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                var saved = machine.saveWithFullMetadata();
                long printProduced = chestCount(chest, print) + slotAmount(machine, 0, 3, print)
                        + slotAmount(machine, 2, 3, print) + pendingOutputs(saved, 0, print) + pendingOutputs(saved, 2, print);
                long processorProduced = chestCount(chest, processor) + slotAmount(machine, 1, 3, processor)
                        + slotAmount(machine, 3, 3, processor) + pendingOutputs(saved, 1, processor) + pendingOutputs(saved, 3, processor);
                helper.assertTrue(printProduced >= seenPrint && printProduced <= expected,
                        "Printed circuit vanished or duplicated at tick " + ticks + ": " + printProduced);
                helper.assertTrue(processorProduced >= seenProcessor && processorProduced <= expected,
                        "Logic processor vanished or duplicated at tick " + ticks + ": " + processorProduced);
                seenPrint = printProduced;
                seenProcessor = processorProduced;
                long printInputNow = slotAmount(machine, 1, 0, print) + slotAmount(machine, 3, 0, print)
                        + reservedInputs(saved, 1, print) + reservedInputs(saved, 3, print);
                helper.assertTrue(printInputNow <= printInputLeft, "Printed circuit input reappeared at tick " + ticks + ": " + printInputNow);
                printInputLeft = printInputNow;
                long goldNow = slotAmount(machine, 0, 2, gold) + slotAmount(machine, 2, 2, gold)
                        + reservedInputs(saved, 0, gold) + reservedInputs(saved, 2, gold);
                helper.assertTrue(goldNow <= goldLeft, "Gold reappeared at tick " + ticks + ": " + goldNow);
                goldLeft = goldNow;
                long redstoneNow = slotAmount(machine, 1, 2, redstone) + slotAmount(machine, 3, 2, redstone)
                        + reservedInputs(saved, 1, redstone) + reservedInputs(saved, 3, redstone);
                helper.assertTrue(redstoneNow <= redstoneLeft, "Redstone reappeared at tick " + ticks + ": " + redstoneNow);
                redstoneLeft = redstoneNow;
                long siliconNow = slotAmount(machine, 1, 1, silicon) + slotAmount(machine, 3, 1, silicon)
                        + reservedInputs(saved, 1, silicon) + reservedInputs(saved, 3, silicon);
                helper.assertTrue(siliconNow <= siliconLeft, "Silicon reappeared at tick " + ticks + ": " + siliconNow);
                siliconLeft = siliconNow;
                if (chestCount(chest, print) == expected && chestCount(chest, processor) == expected) break;
            }
            helper.assertTrue(chestCount(chest, print) == expected && chestCount(chest, processor) == expected,
                    "Auto-export never delivered every result: print=" + chestCount(chest, print)
                            + ", processor=" + chestCount(chest, processor));
            for (int lane = 0; lane < 4; lane++) {
                var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                boolean pressing = lane % 2 != 0;
                helper.assertTrue(amount(slots.get(1)) == 0 && amount(slots.get(2)) == 0 && amount(slots.get(3)) == 0
                        && amount(slots.get(0)) == (pressing ? 0 : 1),
                        "Lane " + lane + " retained inputs or output after auto-export");
                helper.assertTrue(!machine.saveWithFullMetadata().getCompound("ae2ocThread" + lane).contains("ae2ocProcessing"),
                        "Lane " + lane + " retained a batch after auto-export");
            }
            helper.succeed();
        });
    }

    /**
     * Every lane finishes a real recipe while the adjacent inventory is full and its logical output is
     * already nearly a stack. The resulting over-capacity output must survive repeated whole-machine
     * reloads and removal of the parallel card, then drain as legal stacks through the upstream tick path.
     */
    static void blockedExportLifecycle(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(new ResourceLocation("expatternprovider:ex_inscriber")));
        helper.setBlock(pos.east(), Blocks.CHEST);
        var machine = (TileExInscriber) helper.getBlockEntity(pos);
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos.east());
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Blocked-export lifecycle grid is inactive");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_8X.get()));
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);
            machine.getConfigManager().putSetting(Settings.INSCRIBER_SEPARATE_SIDES, YesNo.NO);
            for (int slot = 0; slot < chest.getContainerSize(); slot++)
                chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));

            final int operations = 8;
            final long initialOutput = 63;
            var press = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRESS.asItem());
            var print = AEItemKey.of(AEItems.LOGIC_PROCESSOR_PRINT.asItem());
            var processor = AEItemKey.of(AEItems.LOGIC_PROCESSOR.asItem());
            var silicon = AEItemKey.of(AEItems.SILICON_PRINT.asItem());
            for (int lane = 0; lane < 4; lane++) {
                boolean pressing = lane % 2 != 0;
                var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                slots.get(0).write(new ResourceAmount<AEKey>(pressing ? print : press, pressing ? operations : 1));
                if (pressing) slots.get(1).write(new ResourceAmount<AEKey>(silicon, operations));
                slots.get(2).write(new ResourceAmount<AEKey>(AEItemKey.of(pressing ? Items.REDSTONE : Items.GOLD_INGOT), operations));
                slots.get(3).write(new ResourceAmount<AEKey>(pressing ? processor : print, initialOutput));
            }

            int ticks = 0;
            while (ticks++ < 4000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                boolean complete = true;
                var saved = machine.saveWithFullMetadata();
                for (int lane = 0; lane < 4; lane++) {
                    var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                    var result = lane % 2 == 0 ? print : processor;
                    var held = batch(saved, lane);
                    complete &= amount(slots.get(3)) + pendingOutputs(saved, lane, result) == initialOutput + operations
                            && held != null && held.finished();
                }
                if (complete) break;
            }
            helper.assertTrue(ticks < 4000, "Recipes did not finish against the blocked export target");
            var blocked = machine.saveWithFullMetadata();
            for (int lane = 0; lane < 4; lane++) {
                var result = lane % 2 == 0 ? print : processor;
                long local = amount(ManagedItemStorages.slots(machine.getIndexInventory(lane)).get(3));
                long pending = pendingOutputs(blocked, lane, result);
                helper.assertTrue(local <= 64 && pending > 0 && local + pending == initialOutput + operations,
                        "Blocked lane " + lane + " did not preserve local and pending output");
            }
            machine.load(blocked);
            machine.load(blocked);
            machine.getUpgrades().setItemDirect(0, ItemStack.EMPTY);
            chest.clearContent();
            helper.runAfterDelay(40, () -> {
                helper.assertTrue(machine.getMainNode().isActive(), "Reloaded blocked-export grid did not reconnect");
                for (int tick = 0; tick < 20; tick++) machine.tickingRequest(machine.getMainNode().getNode(), 1);

                long expectedPerType = (initialOutput + operations) * 2;
                helper.assertTrue(chestCount(chest, print) == expectedPerType && chestCount(chest, processor) == expectedPerType,
                        "Reloaded overflow did not drain exactly once: print=" + chestCount(chest, print)
                                + ", processor=" + chestCount(chest, processor));
                for (int lane = 0; lane < 4; lane++) {
                    var slots = ManagedItemStorages.slots(machine.getIndexInventory(lane));
                    boolean pressing = lane % 2 != 0;
                    helper.assertTrue(amount(slots.get(0)) == (pressing ? 0 : 1) && amount(slots.get(1)) == 0
                                    && amount(slots.get(2)) == 0 && amount(slots.get(3)) == 0,
                            "Lane " + lane + " retained resources after reload and card removal");
                    helper.assertTrue(!machine.saveWithFullMetadata().getCompound("ae2ocThread" + lane).contains("ae2ocProcessing"),
                            "Lane " + lane + " retained a completed batch");
                }
                helper.succeed();
            });
        });
    }

    private static long chestCount(ChestBlockEntity chest, AEItemKey key) {
        long total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            if (key.matches(chest.getItem(slot))) total += chest.getItem(slot).getCount();
        return total;
    }

    private static long slotAmount(TileExInscriber machine, int lane, int slot, AEItemKey key) {
        var value = ManagedItemStorages.slots(machine.getIndexInventory(lane)).get(slot).read();
        return value != null && value.key().equals(key) ? value.amount() : 0;
    }

    /** Inputs debited into an unfinished batch; these are gone from the slots but not yet consumed. */
    private static long reservedInputs(CompoundTag saved, int lane, AEItemKey key) {
        var batch = batch(saved, lane);
        if (batch == null || batch.finished()) return 0;
        long total = 0;
        for (var resource : batch.inputs()) if (resource.key().equals(key)) total += resource.amount();
        return total;
    }

    /** Finished but not yet fully drained batch outputs. */
    private static long pendingOutputs(CompoundTag saved, int lane, AEItemKey key) {
        var batch = batch(saved, lane);
        if (batch == null || !batch.finished()) return 0;
        long total = 0;
        for (var resource : batch.outputs()) if (resource.key().equals(key)) total += resource.amount();
        return total;
    }

    private static moakiee.ae2oc.core.execution.ProcessingState<AEKey> batch(CompoundTag saved, int lane) {
        var child = saved.getCompound("ae2ocThread" + lane);
        return child.contains("ae2ocProcessing") ? ProcessingCodec.read(child.getCompound("ae2ocProcessing")) : null;
    }
}
