package moakiee.ae2oc.compat.advancedae;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;
import net.pedroksl.advanced_ae.recipes.ReactionChamberRecipe;
import net.pedroksl.ae2addonlib.recipes.IngredientStack;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.RecipeBatch;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

public final class ReactionRecipes implements Supplier<RecipeBatch> {
    private final ReactionChamberEntity host;
    private ReactionChamberRecipe cached;
    public ReactionRecipes(ReactionChamberEntity host) { this.host = host; }

    @Override public RecipeBatch get() {
        if (host.getLevel() == null) return null;
        if (cached != null) {
            var batch = snapshot(cached);
            if (batch != null) return batch;
        }
        for (var recipe : host.getLevel().getRecipeManager().getAllRecipesFor(ReactionChamberRecipe.TYPE)) {
            var batch = snapshot(recipe);
            if (batch != null) { cached = recipe; return batch; }
        }
        return null;
    }

    private RecipeBatch snapshot(ReactionChamberRecipe recipe) {
        if (recipe.output == null || recipe.output.amount() <= 0) return null;
        var input = host.getInput();
        ItemStack[] items = new ItemStack[input.size()];
        int[] itemInitial = new int[input.size()];
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = moakiee.ae2oc.compat.ae2.ManagedItemStorages.recipeStack(input, slot);
            itemInitial[slot] = items[slot].getCount();
        }
        var fluidPort = LocalResourceSlot.generic(host.getTank(), 1);
        var fluidBefore = fluidPort.read();
        var fluid = fluidBefore != null && fluidBefore.key() instanceof AEFluidKey key
                ? key.toStack((int) Math.min(Integer.MAX_VALUE, fluidBefore.amount())) : FluidStack.EMPTY;
        int fluidInitial = fluid.getAmount();
        for (var ingredient : recipe.getValidInputs()) {
            if (ingredient instanceof IngredientStack.Item tester) {
                for (var item : items) {
                    tester.consume(item);
                    if (tester.isEmpty()) break;
                }
            } else if (ingredient instanceof IngredientStack.Fluid tester) tester.consume(fluid);
            if (!ingredient.isEmpty()) return null;
        }
        var debits = new ArrayList<RecipeBatch.Debit>();
        for (int slot = 0; slot < items.length; slot++) {
            var port = LocalResourceSlot.item(input, slot);
            var before = port.read();
            if (before != null) debits.add(new RecipeBatch.Debit(port, before,
                    moakiee.ae2oc.compat.ae2.ManagedItemStorages.projectedConsumption(
                            before.amount(), itemInitial[slot], items[slot].getCount())));
        }
        if (fluidBefore != null) debits.add(new RecipeBatch.Debit(fluidPort, fluidBefore, fluidInitial - fluid.getAmount()));
        int speed = switch (host.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD)) {
            case 1 -> 3; case 2 -> 5; case 3 -> 10; case 4 -> 50; default -> 2;
        };
        return new RecipeBatch(recipe.getId().toString(), debits,
                List.of(new ResourceAmount<AEKey>(recipe.output.what(), recipe.output.amount())),
                recipe.getEnergy(), (200 + speed - 1) / speed);
    }
}
