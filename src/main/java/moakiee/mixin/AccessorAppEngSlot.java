package moakiee.mixin;

import appeng.menu.slot.AppEngSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AppEngSlot.class, remap = false)
public interface AccessorAppEngSlot {
    @Accessor("invSlot") int ae2oc$inventorySlot();
}
