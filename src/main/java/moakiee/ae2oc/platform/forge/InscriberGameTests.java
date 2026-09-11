package moakiee.ae2oc.platform.forge;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.core.definitions.AEBlocks;
import moakiee.Ae2Overclocked;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Ae2Overclocked.MODID)
@PrefixGameTestTemplate(false)
public final class InscriberGameTests {
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void extendedInscriberRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedInscriberRecipes.run(helper, 0);
    }

    @GameTest(template = "empty")
    public static void extendedInscriberStillExports(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedInscriberRecipes.exportConservation(helper, true);
    }

    @GameTest(template = "empty")
    public static void extendedInscriberExportsWithoutLosingOverflow(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedInscriberRecipes.exportConservation(helper, false);
    }

    @GameTest(template = "empty")
    public static void extendedInscriberLanesExportIndependently(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedInscriberRecipes.laneExportIsolation(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void inscriberRecipeSurvivesReloadAndCardRemoval(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, AEBlocks.INSCRIBER.block());
        var machine = (InscriberBlockEntity) helper.getBlockEntity(pos);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Lifecycle test grid did not become active");
            machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD_8X.get()));
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);
            var slots = ManagedItemStorages.slots(machine.getInternalInventory());
            var print = AEItemKey.of(appeng.core.definitions.AEItems.LOGIC_PROCESSOR_PRINT.asItem());
            slots.get(0).write(new ResourceAmount<AEKey>(AEItemKey.of(appeng.core.definitions.AEItems.LOGIC_PROCESSOR_PRESS.asItem()), 1));
            slots.get(2).write(new ResourceAmount<AEKey>(AEItemKey.of(Items.GOLD_INGOT), 8));
            slots.get(3).write(new ResourceAmount<AEKey>(print, 63));
            machine.tickingRequest(machine.getMainNode().getNode(), 1);
            var reserved = machine.saveWithFullMetadata();
            var batch = moakiee.ae2oc.compat.ae2.ProcessingCodec.read(reserved.getCompound("ae2ocProcessing"));
            helper.assertTrue(batch.inputs().get(0).amount() == 8 && batch.energyPaid() > 0 && !batch.finished(),
                    "Real recipe did not reserve and pay for eight operations");
            machine.load(reserved);
            machine.load(reserved);
            helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(machine) == 72,
                    "Repeated recipe reload changed held resources");
            machine.getUpgrades().setItemDirect(0, ItemStack.EMPTY);
            for (int tick = 0; tick < 120; tick++) machine.tickingRequest(machine.getMainNode().getNode(), 1);
            var blocked = machine.saveWithFullMetadata();
            var pending = moakiee.ae2oc.compat.ae2.ProcessingCodec.read(blocked.getCompound("ae2ocProcessing"));
            helper.assertTrue(pending.finished() && pending.outputs().get(0).amount() == 7 && amount(slots.get(3)) == 64,
                    "Full output did not preserve the seven pending results after card removal");
            helper.assertTrue(pending.energyPaid() == batch.energyRequired(), "Reload changed the recipe payment");
            var extracted = machine.getInternalInventory().extractItem(3, 64, false);
            helper.assertTrue(print.matches(extracted) && extracted.getCount() == 64, "Could not extract legal completed output");
            // Persist after extraction: replaying an older world snapshot is not a supported transaction.
            var resume = machine.saveWithFullMetadata();
            machine.load(resume);
            machine.load(resume);
            machine.tickingRequest(machine.getMainNode().getNode(), 1);
            var rest = machine.getInternalInventory().extractItem(3, 64, false);
            helper.assertTrue(print.matches(rest) && rest.getCount() == 7, "Pending results were lost or replayed");
            for (int tick = 0; tick < 5; tick++) machine.tickingRequest(machine.getMainNode().getNode(), 1);
            helper.assertTrue(amount(slots.get(0)) == 1 && amount(slots.get(2)) == 0 && amount(slots.get(3)) == 0
                    && !machine.saveWithFullMetadata().contains("ae2ocProcessing"), "Completed recipe retained resources or replayed output");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void inscriberInscriptionRecipeMatrix(GameTestHelper helper) {
        recipeScenario(helper, false, 0);
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void inscriberPressRecipeMatrix(GameTestHelper helper) {
        recipeScenario(helper, true, 0);
    }

    private static void recipeScenario(GameTestHelper helper, boolean press, int scenario) {
        if (scenario == 12) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(pos, AEBlocks.INSCRIBER.block());
        var machine = (InscriberBlockEntity) helper.getBlockEntity(pos);
        // Allow real grid creation and power propagation before manually driving machine ticks.
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(machine.getMainNode().isActive(), "Recipe test grid did not become active");
            var cards = new net.minecraft.world.item.Item[] {null, ModItems.PARALLEL_CARD.get(),
                    ModItems.PARALLEL_CARD_8X.get(), ModItems.PARALLEL_CARD_64X.get(),
                    ModItems.PARALLEL_CARD_1024X.get(), ModItems.PARALLEL_CARD_MAX.get()};
            int tier = scenario / 2;
            boolean overclock = scenario % 2 != 0;
            if (cards[tier] != null) machine.getUpgrades().setItemDirect(0, new ItemStack(cards[tier]));
            if (overclock) machine.getUpgrades().setItemDirect(1, new ItemStack(ModItems.OVERCLOCK_CARD.get()));
            machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);
            var slots = ManagedItemStorages.slots(machine.getInternalInventory());
            var middle = AEItemKey.of(press ? Items.REDSTONE : Items.GOLD_INGOT);
            var result = AEItemKey.of(press ? appeng.core.definitions.AEItems.LOGIC_PROCESSOR.asItem()
                    : appeng.core.definitions.AEItems.LOGIC_PROCESSOR_PRINT.asItem());
            int operations = new int[] {70, 5, 17, 130, 2050, 8193}[tier];
            slots.get(0).write(new ResourceAmount<AEKey>(AEItemKey.of(press
                    ? appeng.core.definitions.AEItems.LOGIC_PROCESSOR_PRINT.asItem()
                    : appeng.core.definitions.AEItems.LOGIC_PROCESSOR_PRESS.asItem()), press ? operations : 1));
            if (press) slots.get(1).write(new ResourceAmount<AEKey>(AEItemKey.of(appeng.core.definitions.AEItems.SILICON_PRINT.asItem()), operations));
            slots.get(2).write(new ResourceAmount<AEKey>(middle, operations));
            var recipe = machine.getTask();
            String label = "press=" + press + ", tier=" + tier + ", overclock=" + overclock;
            helper.assertTrue(recipe != null && recipe.getId().toString().equals(press
                    ? "ae2:inscriber/logic_processor" : "ae2:inscriber/logic_processor_print"), "Missing real recipe: " + label);
            long collected = 0;
            int ticks = 0;
            while (collected < operations && ticks++ < 20000) {
                machine.tickingRequest(machine.getMainNode().getNode(), 1);
                if (ticks == 1 && (tier > 0 || overclock)) {
                    var profile = moakiee.ae2oc.compat.ae2.UpgradeProfileCache.of(machine);
                    long expected = Math.min(operations, Math.min(profile.parallelLimit(), moakiee.Ae2OcConfig.getMaxRecipeOperationsPerMachineTick()));
                    helper.assertTrue(operations - amount(slots.get(2)) == expected, "Wrong initial parallel batch: " + label);
                }
                var output = machine.getInternalInventory().extractItem(3, 64, false);
                helper.assertTrue(output.isEmpty() || (result.matches(output) && output.getCount() <= output.getMaxStackSize()), "Wrong or oversized recipe output: " + label);
                collected += output.getCount();
                var saved = machine.saveWithFullMetadata();
                long pending = 0;
                if (saved.contains("ae2ocProcessing")) {
                    var batch = moakiee.ae2oc.compat.ae2.ProcessingCodec.read(saved.getCompound("ae2ocProcessing"));
                    pending = batch.finished() ? batch.outputs().stream().mapToLong(ResourceAmount::amount).sum()
                            : batch.inputs().stream().filter(input -> input.key().equals(middle)).mapToLong(ResourceAmount::amount).sum();
                }
                helper.assertTrue(collected + amount(slots.get(3)) + pending + amount(slots.get(2)) == operations,
                        "Recipe conservation failed at tick " + ticks + ": " + label);
            }
            helper.assertTrue(collected == operations, "Recipe did not complete: " + label);
            helper.assertTrue(amount(slots.get(0)) == (press ? 0 : 1) && amount(slots.get(1)) == 0,
                    "Recipe consumed a mold or retained press ingredients: " + label);
            helper.assertTrue(!machine.saveWithFullMetadata().contains("ae2ocProcessing"), "Completed recipe retained a batch: " + label);
            recipeScenario(helper, press, scenario + 1);
        });
    }

    private static long amount(moakiee.ae2oc.compat.ae2.LocalResourceSlot slot) {
        var resource = slot.read();
        return resource == null ? 0 : resource.amount();
    }

    @GameTest(template = "empty")
    public static void upgradedInscriberStillExports(GameTestHelper helper) {
        exportConservation(helper, true);
    }

    @GameTest(template = "empty")
    public static void downgradedInscriberExportsWithoutLosingOverflow(GameTestHelper helper) {
        exportConservation(helper, false);
    }

    private static void exportConservation(GameTestHelper helper, boolean upgraded) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AEBlocks.INSCRIBER.block());
        helper.setBlock(pos.east(), Blocks.CHEST);
        var machine = (InscriberBlockEntity) helper.getBlockEntity(pos);
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos.east());
        if (upgraded) machine.getUpgrades().setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
        var output = ManagedItemStorages.slots(machine.getInternalInventory()).get(3);
        output.write(new ResourceAmount<AEKey>(AEItemKey.of(Items.GOLD_INGOT), 130));
        for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 63));
        machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.NO);
        machine.tickingRequest(null, 1);
        helper.assertTrue(output.read().amount() == 130 && chest.getItem(0).getCount() == 63, "Disabled auto-export moved items");
        machine.getConfigManager().putSetting(Settings.AUTO_EXPORT, YesNo.YES);
        machine.tickingRequest(null, 1);
        helper.assertTrue(chest.getItem(0).getCount() == 64, "Auto-export did not deliver to adjacent inventory");
        helper.assertTrue(output.read().amount() == 129, "Partial auto-export lost overflow items");
        machine.tickingRequest(null, 1);
        helper.assertTrue(output.read().amount() == 129, "Full destination lost overflow items");
        helper.setBlock(pos.east(), Blocks.AIR);
        helper.setBlock(pos.above(), Blocks.CHEST);
        var top = (ChestBlockEntity) helper.getBlockEntity(pos.above());
        machine.getConfigManager().putSetting(Settings.INSCRIBER_SEPARATE_SIDES, YesNo.YES);
        machine.tickingRequest(null, 1);
        helper.assertTrue(top.isEmpty() && output.read().amount() == 129, "Separate sides exported through the top");
        machine.getConfigManager().putSetting(Settings.INSCRIBER_SEPARATE_SIDES, YesNo.NO);
        machine.tickingRequest(null, 1);
        helper.assertTrue(top.getItem(0).getCount() == 64 && output.read().amount() == 65, "Combined sides did not export a legal stack");
        helper.succeed();
    }
}
