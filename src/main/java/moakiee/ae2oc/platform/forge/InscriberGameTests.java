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
