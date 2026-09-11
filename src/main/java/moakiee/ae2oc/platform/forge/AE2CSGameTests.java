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
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void pulverizerReloadAndCardRemovalPreserveBatch(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "crystal_pulverizer", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void pulverizerRealBreakReturnsReservedInputs(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "crystal_pulverizer", true);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aggregatorReloadAndCardRemovalPreserveBatch(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "crystal_aggregator", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aggregatorRealBreakReturnsReservedInputs(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "crystal_aggregator", true);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void circuitEtcherReloadAndCardRemovalPreserveBatch(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "circuit_etcher", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void circuitEtcherRealBreakReturnsReservedInputs(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "circuit_etcher", true);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void entropyChamberReloadAndCardRemovalPreserveBatch(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "entropy_variation_reaction_chamber", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void entropyChamberRealBreakReturnsReservedInputs(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.lifecycle(helper, "entropy_variation_reaction_chamber", true);
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aggregatorNaturallyResumesAfterEnergyArrives(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.run(helper, "crystal_aggregator", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aggregatorNaturallyRunsAfterNodeDestruction(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.run(helper, "crystal_aggregator", true);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void circuitEtcherNaturallyResumesAfterEnergyArrives(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.run(helper, "circuit_etcher", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void circuitEtcherNaturallyRunsAfterNodeDestruction(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.run(helper, "circuit_etcher", true);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void entropyChamberNaturallyResumesAfterEnergyArrives(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.run(helper, "entropy_variation_reaction_chamber", false);
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void entropyChamberNaturallyRunsAfterNodeDestruction(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSNaturalScheduling.run(helper, "entropy_variation_reaction_chamber", true);
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void processorNaturallyResumesAfterEnergyArrives(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSEnergySemantics.naturalScheduling(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void processorNaturallyRunsAfterNodeDestruction(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSEnergySemantics.naturalScheduling(helper, true);
    }

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

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void aggregatorRealRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSAggregatorRecipes.recipeMatrix(helper, 0);
    }

    @GameTest(template = "empty")
    public static void aggregatorDestructionReturnsOwnedResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSAggregatorRecipes.destructionDrops(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void circuitEtcherRealRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSCircuitEtcherRecipes.recipeMatrix(helper, 0);
    }

    @GameTest(template = "empty")
    public static void circuitEtcherDestructionReturnsOwnedResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSCircuitEtcherRecipes.destructionDrops(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void entropyChamberRealRecipeMatrix(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSEntropyRecipes.recipeMatrix(helper, 0);
    }

    @GameTest(template = "empty")
    public static void entropyChamberDestructionReturnsOwnedResources(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSEntropyRecipes.destructionDrops(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void processorKeepsProcessingWhileNodeIsInactive(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSEnergySemantics.keepsProcessingWhileNodeIsInactive(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void processorStallsWithoutEnergyThenResumes(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2cs")) { helper.succeed(); return; }
        AE2CSEnergySemantics.stallsWithoutEnergyThenResumes(helper);
    }
}
