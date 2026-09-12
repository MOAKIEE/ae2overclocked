package moakiee.mixin;

import moakiee.ae2oc.compat.ae2cs.AE2CSStateSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity", remap = false)
public abstract class MixinAE2CSCrystalAggregatorStateSync implements AE2CSStateSync {
    @Shadow public abstract void checkActive(boolean active);
    @Shadow private int recipeProgress;
    @Shadow private int activeRecipeEnergyCost;

    @Override public void ae2oc$syncState(boolean powered, int progress, int progressMax) {
        checkActive(powered);
        recipeProgress = progress;
        activeRecipeEnergyCost = progressMax;
    }

    @Override public int ae2oc$progress() { return recipeProgress; }
    @Override public int ae2oc$progressMax() { return activeRecipeEnergyCost; }
}
