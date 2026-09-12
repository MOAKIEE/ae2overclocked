package moakiee.ae2oc.compat.ae2;

import net.minecraft.world.item.ItemStack;

/** Client-only presentation state kept separate from AE2's settlement-driving smash fields. */
public interface InscriberVisualState {
    ItemStack ae2oc$visualResult();
}
