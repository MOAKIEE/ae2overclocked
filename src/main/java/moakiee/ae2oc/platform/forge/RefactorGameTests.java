package moakiee.ae2oc.platform.forge;

import java.util.List;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import moakiee.Ae2Overclocked;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(Ae2Overclocked.MODID)
@PrefixGameTestTemplate(false)
public final class RefactorGameTests {
    @GameTest(template = "empty")
    public static void managedFluidRemovalPreservesResources(GameTestHelper helper) {
        var tank = new appeng.helpers.externalstorage.GenericStackInv(null,
                appeng.helpers.externalstorage.GenericStackInv.Mode.STORAGE, 1);
        moakiee.support.OverstackingRegistry.register(tank);
        var key = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        tank.setCapacity(key.getType(), Long.MAX_VALUE);
        tank.setStack(0, new appeng.api.stacks.GenericStack(key, Long.MAX_VALUE));
        tank.setCapacity(key.getType(), 16000);
        helper.assertTrue(tank.getAmount(0) == Long.MAX_VALUE, "Capacity reduction destroyed fluid");
        helper.assertTrue(tank.insert(0, key, 1, appeng.api.config.Actionable.MODULATE) == 0, "Frozen tank accepted fluid");
        helper.assertTrue(tank.extract(0, key, Long.MAX_VALUE - 16000, appeng.api.config.Actionable.MODULATE) == Long.MAX_VALUE - 16000,
                "Frozen tank lost resources on extraction");
        helper.assertTrue(tank.getAmount(0) == 16000, "Wrong remaining fluid");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void processingNbtRoundTrip(GameTestHelper helper) {
        var state = new ProcessingState<AEKey>("ae2:inscriber/test",
                List.of(new ResourceAmount<>(AEItemKey.of(Items.IRON_INGOT), 4096)),
                List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 8192)), 1000, 125, 4);
        helper.assertTrue(state.equals(ProcessingCodec.read(ProcessingCodec.write(state))), "Batch NBT changed owned resources or payments");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void machineSaveReload(GameTestHelper helper) {
        String[] ids = {"ae2:inscriber", "expatternprovider:ex_inscriber", "expatternprovider:circuit_cutter",
                "advanced_ae:reaction_chamber", "ae2cs:circuit_etcher", "ae2cs:crystal_pulverizer",
                "ae2cs:crystal_aggregator", "ae2cs:entropy_variation_reaction_chamber"};
        for (String id : ids) {
            var key = new ResourceLocation(id);
            if (!ForgeRegistries.BLOCKS.containsKey(key)) continue;
            var block = ForgeRegistries.BLOCKS.getValue(key);
            helper.setBlock(new BlockPos(1, 1, 1), block);
            var machine = helper.getBlockEntity(new BlockPos(1, 1, 1));
            helper.assertTrue(machine != null, "Missing machine entity: " + id);
            var saved = machine.saveWithFullMetadata();
            machine.load(saved);
            helper.assertTrue(machine.getType() == helper.getBlockEntity(new BlockPos(1, 1, 1)).getType(), "Machine type changed: " + id);
        }
        helper.succeed();
    }
}
