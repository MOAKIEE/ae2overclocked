package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import com.google.common.primitives.Ints;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.moakiee.ae2_overclocked.support.OverstackingRegistry;

/**
 * Fixes ConfigMenuInventory.getStackInSlot() for overstacking inventories.
 * <p>
 * In vanilla AE2 1.21.1, when a GenericStackInv slot holds more items than
 * {@code itemKey.getMaxStackSize()} (typically 64), the method returns a
 * "wrapped" GenericStack ItemStack instead of a real ItemStack. This wrapped
 * item has a different item type, so Minecraft's slot interaction logic
 * considers it incompatible with the player's held item, preventing further
 * stacking via GUI clicks.
 * <p>
 * This mixin makes getStackInSlot() return a real ItemStack (clamped to int
 * range) for overstacking inventories, so GUI interactions work seamlessly
 * beyond the 64-item limit.
 */
@Mixin(targets = "appeng.util.ConfigMenuInventory", remap = false)
public class MixinConfigMenuInventory {

    @Shadow
    @Final
    private GenericStackInv inv;

    @Inject(method = "getStackInSlot", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getStackInSlot(int slotIndex, CallbackInfoReturnable<ItemStack> cir) {
        if (!OverstackingRegistry.shouldAllowOverstacking(inv)) {
            return;
        }

        GenericStack stack = inv.getStack(slotIndex);
        if (stack == null) {
            return;
        }

        // For item keys, always return a real ItemStack (capped to int range)
        // so that vanilla slot interaction logic can match item types correctly.
        if (stack.what() instanceof AEItemKey itemKey) {
            int amount = Ints.saturatedCast(stack.amount());
            if (amount <= 0) amount = 1;
            cir.setReturnValue(itemKey.toStack(amount));
        }
        // For non-item keys (fluids etc.), fall through to original logic.
    }
}
