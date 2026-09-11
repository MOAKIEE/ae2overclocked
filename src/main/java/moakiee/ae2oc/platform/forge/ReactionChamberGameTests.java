package moakiee.ae2oc.platform.forge;

import moakiee.Ae2Overclocked;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** AdvancedAE reaction chamber scenarios. Skipped when the optional mod is absent. */
@GameTestHolder(Ae2Overclocked.MODID)
@PrefixGameTestTemplate(false)
public final class ReactionChamberGameTests {
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void reactionChamberItemRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("advanced_ae")) { helper.succeed(); return; }
        AdvancedReactionRecipes.itemRecipeMatrix(helper, 0);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void reactionChamberFluidRecipeConservesTank(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("advanced_ae")) { helper.succeed(); return; }
        AdvancedReactionRecipes.fluidRecipeConservation(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void reactionChamberAutoExportConservesOverflow(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("advanced_ae")) { helper.succeed(); return; }
        AdvancedReactionRecipes.autoExportConservation(helper);
    }

    @GameTest(template = "empty")
    public static void reactionChamberDestructionReturnsTankAndBatchResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("advanced_ae")) { helper.succeed(); return; }
        AdvancedReactionRecipes.destructionDrops(helper);
    }
}
