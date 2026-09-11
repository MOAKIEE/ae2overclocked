package moakiee.ae2oc.platform.forge;

import moakiee.Ae2Overclocked;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** AE2 Crystal Science scenarios. Skipped when the optional mod is absent. */
@GameTestHolder(Ae2Overclocked.MODID)
@PrefixGameTestTemplate(false)
public final class AE2CSGameTests {
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void pulverizerRealRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSPulverizerRecipes.recipeMatrix(helper, 0);
    }

    @GameTest(template = "empty")
    public static void pulverizerDestructionReturnsOwnedResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSPulverizerRecipes.destructionDrops(helper);
    }
}
