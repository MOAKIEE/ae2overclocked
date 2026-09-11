package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.CombinedInternalInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CombinedInternalInventory.class, remap = false)
public interface AccessorCombinedInventory {
    @Accessor("inventories") InternalInventory[] ae2oc$children();
}
