package moakiee.ae2oc.compat.extendedae;

import appeng.api.inventories.InternalInventory;
import net.minecraft.world.item.ItemStack;

/** Client presentation state for ExtendedAE's global four-lane press animation. */
public interface ExtendedInscriberVisualState {
    boolean ae2oc$isVisualSmash();
    ItemStack ae2oc$visualResult(InternalInventory inventory);
}
