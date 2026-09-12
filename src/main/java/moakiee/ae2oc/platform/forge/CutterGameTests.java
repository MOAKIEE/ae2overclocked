package moakiee.ae2oc.platform.forge;

import moakiee.Ae2Overclocked;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** ExtendedAE circuit cutter scenarios. Skipped when the optional mod is absent. */
@GameTestHolder(Ae2Overclocked.MODID)
@PrefixGameTestTemplate(false)
public final class CutterGameTests {
    @GameTest(template = "empty")
    public static void cutterRestoredBatchUpdatesProgress(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedCutterRecipes.progressSync(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void cutterRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedCutterRecipes.recipeMatrix(helper, 0);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void cutterAdditionalRecipesConserveResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedCutterRecipes.additionalRecipeCoverage(helper, 0);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void cutterAutoExportConservesOverflow(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedCutterRecipes.autoExportConservation(helper);
    }

    @GameTest(template = "empty")
    public static void cutterDestructionReturnsTankAndBatchResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedCutterRecipes.destructionDrops(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cutterRealDestructionReturnsVisibleAndOverflowResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("expatternprovider")) { helper.succeed(); return; }
        ExtendedCutterRecipes.realDestructionDrops(helper);
    }
}
