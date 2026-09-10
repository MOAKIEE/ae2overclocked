package moakiee.ae2oc.compat.ae2cs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import appeng.recipes.AERecipeTypes;
import io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity;
import io.github.lounode.ae2cs.common.init.AECSItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.RecipeBatch;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

public final class EntropyRecipes implements Supplier<RecipeBatch> {
    private final EntropyVariationReactionChamberBlockEntity host;
    public EntropyRecipes(EntropyVariationReactionChamberBlockEntity host) { this.host = host; }
    public RecipeBatch get() {
        if (host.getLevel() == null) return null;
        var port = LocalResourceSlot.generic(host.getInputInv(), 0);
        var before = port.read();
        if (before == null) return null;
        var blockState = before.key() instanceof AEItemKey item && item.getItem() instanceof BlockItem block
                ? block.getBlock().defaultBlockState() : Blocks.AIR.defaultBlockState();
        var fluidState = before.key() instanceof AEFluidKey fluid
                ? fluid.getFluid().defaultFluidState() : Fluids.EMPTY.defaultFluidState();
        for (var recipe : host.getLevel().getRecipeManager().getAllRecipesFor(AERecipeTypes.ENTROPY)) {
            if (!recipe.matches(host.getEntropyMode(), blockState, fluidState)) continue;
            long amount = 0;
            if (recipe.getInputBlock() != null && recipe.getInputBlock().asItem() != Items.AIR) {
                if (!before.key().equals(AEItemKey.of(recipe.getInputBlock().asItem()))) continue;
                amount = 1;
            }
            if (recipe.getInputFluid() != null && recipe.getInputFluid() != Fluids.EMPTY) {
                if (!before.key().equals(AEFluidKey.of(recipe.getInputFluid()))) continue;
                amount = 1000;
            }
            if (amount == 0 || before.amount() < amount) continue;
            var outputs = new ArrayList<ResourceAmount<AEKey>>();
            if (recipe.getOutputBlock() != null && recipe.getOutputBlock().asItem() != Items.AIR)
                outputs.add(new ResourceAmount<>(AEItemKey.of(recipe.getOutputBlock().asItem()), 1));
            if (recipe.getOutputFluid() != null && recipe.getOutputFluid() != Fluids.EMPTY)
                outputs.add(new ResourceAmount<>(AEFluidKey.of(recipe.getOutputFluid()), 1000));
            for (var drop : recipe.getDrops()) if (!drop.isEmpty()) outputs.add(new ResourceAmount<>(AEItemKey.of(drop), drop.getCount()));
            if (outputs.isEmpty()) continue;
            int speed = 1 << Math.min(4, host.getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD));
            int ticks = host.getUpgrades().isInstalled(AECSItems.OVERLOAD_CARD.get()) ? 4 : Math.max(1, (8 + speed - 1) / speed);
            return new RecipeBatch(recipe.getId().toString(), List.of(new RecipeBatch.Debit(port, before, amount)), outputs, 1600, ticks);
        }
        return null;
    }
}
