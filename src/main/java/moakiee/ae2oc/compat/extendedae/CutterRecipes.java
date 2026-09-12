package moakiee.ae2oc.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter;
import com.glodblock.github.extendedae.recipe.CircuitCutterRecipe;
import com.glodblock.github.extendedae.recipe.util.IngredientStack;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.RecipeBatch;
import net.minecraftforge.fluids.FluidStack;

public final class CutterRecipes implements Supplier<RecipeBatch> {
    private final TileCircuitCutter host;
    private CircuitCutterRecipe cached;
    public CutterRecipes(TileCircuitCutter host) { this.host = host; }

    @Override public RecipeBatch get() {
        if (host.getLevel() == null) return null;
        var manager = host.getLevel().getRecipeManager();
        if (cached != null) {
            // A datapack reload replaces the whole recipe registry. A snapshot may only be reused while the
            // cached object is still the one the current manager holds; otherwise a removed recipe would keep
            // starting batches, and a same-id replacement would keep its stale cost and outputs.
            Object registered = manager.byKey(cached.getId()).orElse(null);
            if (registered == cached) {
                var batch = snapshot(cached);
                if (batch != null) return batch;
            }
            cached = null;
        }
        for (var recipe : manager.getAllRecipesFor(CircuitCutterRecipe.TYPE)) {
            var batch = snapshot(recipe);
            if (batch != null) { cached = recipe; return batch; }
        }
        return null;
    }

    private RecipeBatch snapshot(CircuitCutterRecipe recipe) {
        var itemPort = LocalResourceSlot.item(host.getInput(), 0);
        var fluidPort = LocalResourceSlot.generic(host.getTank(), 0);
        var itemBefore = itemPort.read();
        var fluidBefore = fluidPort.read();
        if (itemBefore == null || recipe.output.isEmpty()) return null;
        var item = ((AEItemKey) itemBefore.key()).toStack((int) Math.min(Integer.MAX_VALUE, itemBefore.amount()));
        int itemInitial = item.getCount();
        var fluid = fluidBefore != null && fluidBefore.key() instanceof AEFluidKey key
                ? key.toStack((int) Math.min(Integer.MAX_VALUE, fluidBefore.amount())) : FluidStack.EMPTY;
        int fluidInitial = fluid.getAmount();
        for (var ingredient : recipe.getSample()) {
            if (ingredient instanceof IngredientStack.Item input) input.consume(item);
            else if (ingredient instanceof IngredientStack.Fluid input) input.consume(fluid);
            if (!ingredient.isEmpty()) return null;
        }
        var debits = new ArrayList<RecipeBatch.Debit>();
        debits.add(new RecipeBatch.Debit(itemPort, itemBefore,
                moakiee.ae2oc.compat.ae2.ManagedItemStorages.projectedConsumption(
                        itemBefore.amount(), itemInitial, item.getCount())));
        if (fluidBefore != null) debits.add(new RecipeBatch.Debit(fluidPort, fluidBefore, fluidInitial - fluid.getAmount()));
        int speed = switch (host.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD)) {
            case 1 -> 3; case 2 -> 5; case 3 -> 10; case 4 -> 50; default -> 2;
        };
        int ticks = 200 / speed + 1;
        return new RecipeBatch(recipe.getId().toString(), debits,
                List.of(new ResourceAmount<AEKey>(AEItemKey.of(recipe.output), recipe.output.getCount())), ticks * speed * 10.0, ticks);
    }
}
