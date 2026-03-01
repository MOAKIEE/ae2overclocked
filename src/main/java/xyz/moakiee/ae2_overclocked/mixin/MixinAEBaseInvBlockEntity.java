package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.blockentity.AEBaseInvBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AEBaseInvBlockEntity.class, remap = false)
public abstract class MixinAEBaseInvBlockEntity {

    private static final String AE2OC_COUNT_KEY = "ae2ocCount";

    @Shadow
    public abstract InternalInventory getInternalInventory();

    @Redirect(
            method = "saveAdditional",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;saveOptional(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/Tag;"
            ),
            require = 0
    )
    private Tag ae2oc_saveOptionalWithOverstack(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty() || stack.getCount() <= 64) {
            return stack.saveOptional(registries);
        }

        // Serialize a safe vanilla stack first, then append the real count.
        ItemStack oneCopy = stack.copy();
        oneCopy.setCount(1);
        Tag rawTag = oneCopy.saveOptional(registries);
        if (rawTag instanceof CompoundTag itemTag) {
            itemTag.putInt(AE2OC_COUNT_KEY, stack.getCount());
        }
        return rawTag;
    }

    @Inject(method = "loadTag", at = @At("TAIL"), require = 0)
    private void ae2oc_loadTagTail(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        if (!data.contains("inv")) {
            return;
        }

        InternalInventory inv = this.getInternalInventory();
        if (inv == InternalInventory.empty()) {
            return;
        }

        CompoundTag invTag = data.getCompound("inv");
        for (int i = 0; i < inv.size(); i++) {
            CompoundTag itemTag = invTag.getCompound("item" + i);
            if (!itemTag.contains(AE2OC_COUNT_KEY)) {
                continue;
            }

            int restoredCount = itemTag.getInt(AE2OC_COUNT_KEY);
            if (restoredCount <= 0) {
                continue;
            }

            // Do not call setItemDirect here to avoid extra wake-up side effects.
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                stack.setCount(restoredCount);
            }
        }
    }
}
