package moakiee.ae2oc.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.grid.AENetworkPowerBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.ae2oc.api.ResourceAmount;

/** Recipe semantics are shared by the two upstream inscriber implementations. */
public final class InscriberAdapter extends MachineProcessorAdapter {
    public InscriberAdapter(AENetworkPowerBlockEntity host, IUpgradeableObject upgrades,
                            InternalInventory inventory, Supplier<InscriberRecipe> recipes) {
        this(host, upgrades, inventory, recipes, 1);
    }

    public InscriberAdapter(AENetworkPowerBlockEntity host, IUpgradeableObject upgrades,
                            InternalInventory inventory, Supplier<InscriberRecipe> recipes, int shares) {
        super(host, upgrades, inventory, () -> snapshot(upgrades, inventory, recipes.get()), shares, 3);
    }

    private static RecipeBatch snapshot(IUpgradeableObject upgrades, InternalInventory inventory, InscriberRecipe recipe) {
        if (recipe == null || recipe.getResultItem().isEmpty()) return null;
        var inputs = new ArrayList<RecipeBatch.Debit>();
        for (int slot = 0; slot < 3; slot++) {
            var port = ManagedItemStorages.slots(inventory).get(slot);
            var before = port.read();
            if (before != null) inputs.add(new RecipeBatch.Debit(port, before,
                    slot == 2 || recipe.getProcessType() == InscriberProcessType.PRESS ? 1 : 0));
        }
        int speed = switch (upgrades.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD)) {
            case 1 -> 3; case 2 -> 5; case 3 -> 10; case 4 -> 50; default -> 2;
        };
        // AE2 charges ten AE per step and finishes only after exceeding 200 steps.
        int normalTicks = 200 / speed + 1;
        var output = recipe.getResultItem();
        return new RecipeBatch(recipe.getId().toString(), inputs,
                List.of(new ResourceAmount<AEKey>(AEItemKey.of(output), output.getCount())),
                normalTicks * speed * 10.0, normalTicks);
    }
}
