package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.inventories.InternalInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter", remap = false)
public abstract class MixinTileCircuitCutterNetwork {

    @Unique
    private static final String AE2OC_NET_COUNT = "ae2ocNetCount";

    @Shadow
    public abstract InternalInventory getInternalInventory();

    
    @Inject(
            method = "writeToStream",
            at = @At("HEAD"),
            require = 0
    )
    private void ae2oc_beforeWriteToStream(RegistryFriendlyByteBuf data, CallbackInfo ci) {
        var inv = getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getCount() > 64) {
                ae2oc_putCountTag(stack, stack.getCount());
            }
        }
    }

    
    @Inject(
            method = "writeToStream",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_afterWriteToStream(RegistryFriendlyByteBuf data, CallbackInfo ci) {
        var inv = getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            CompoundTag tag = ae2oc_getCustomTag(stack);
            if (tag != null && tag.contains(AE2OC_NET_COUNT)) {
                ae2oc_removeCountTag(stack);
            }
        }
    }

    
    @Inject(
            method = "readFromStream",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_afterReadFromStream(RegistryFriendlyByteBuf data, CallbackInfoReturnable<Boolean> cir) {
        var inv = getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                CompoundTag tag = ae2oc_getCustomTag(stack);
                if (tag != null && tag.contains(AE2OC_NET_COUNT)) {
                    int realCount = tag.getInt(AE2OC_NET_COUNT);
                    ae2oc_removeCountTag(stack);
                    if (realCount > 0) {
                        stack.setCount(realCount);
                    }
                }
            }
        }
    }

    @Unique
    private static CompoundTag ae2oc_getCustomTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    @Unique
    private static void ae2oc_putCountTag(ItemStack stack, int value) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(AE2OC_NET_COUNT, value));
    }

    @Unique
    private static void ae2oc_removeCountTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }

        CompoundTag copy = data.copyTag();
        copy.remove(AE2OC_NET_COUNT);
        if (copy.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(copy));
        }
    }
}
