package moakiee.ae2oc.compat.ae2cs;

import java.util.ArrayList;
import java.util.List;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.core.definitions.AEItems;
import io.github.lounode.ae2cs.common.init.AECSItems;
import io.github.lounode.ae2cs.common.recipe.SizedIngredient;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.RecipeBatch;
import net.minecraft.world.item.ItemStack;

public final class ItemRecipes {
    private ItemRecipes() {}
    public static RecipeBatch snapshot(String id, InternalInventory inventory, IUpgradeableObject host,
                                      int[] slots, List<SizedIngredient> required, ItemStack output, int energy) {
        if (slots == null || output.isEmpty()) return null;
        var debits = new ArrayList<RecipeBatch.Debit>();
        long[] counts = new long[inventory.size()];
        for (int i = 0; i < slots.length; i++) counts[slots[i]] += required.get(i).count();
        for (int slot = 0; slot < counts.length; slot++) if (counts[slot] > 0) {
            var port = LocalResourceSlot.item(inventory, slot);
            var before = port.read();
            if (before == null || before.amount() < counts[slot]) return null;
            debits.add(new RecipeBatch.Debit(port, before, counts[slot]));
        }
        int speed = 1 << Math.min(4, host.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD));
        int ticks = host.getUpgrades().isInstalled(AECSItems.OVERLOAD_CARD.get()) ? 4
                : Math.max(1, (int) Math.ceil(energy / (200.0 * speed)));
        return new RecipeBatch(id, debits, List.of(new ResourceAmount<AEKey>(AEItemKey.of(output), output.getCount())), energy, ticks);
    }
}
