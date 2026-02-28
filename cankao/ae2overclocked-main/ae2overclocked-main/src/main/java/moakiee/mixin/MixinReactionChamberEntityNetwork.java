package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 ReactionChamberEntity 网络同步超量堆叠物品的问题。
 */
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberEntityNetwork {

    @Unique
    private static final String AE2OC_NET_COUNT = "ae2ocNetCount";

    @Shadow
    public abstract InternalInventory getInternalInventory();

    /**
     * writeToStream HEAD: 为超量物品添加数量标记。
     */
    @Inject(
            method = "writeToStream",
            at = @At("HEAD"),
            require = 0
    )
    private void ae2oc_beforeWriteToStream(FriendlyByteBuf data, CallbackInfo ci) {
        var inv = getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getCount() > 64) {
                stack.getOrCreateTag().putInt(AE2OC_NET_COUNT, stack.getCount());
            }
        }
    }

    /**
     * writeToStream TAIL: 清理临时标记。
     */
    @Inject(
            method = "writeToStream",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_afterWriteToStream(FriendlyByteBuf data, CallbackInfo ci) {
        var inv = getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains(AE2OC_NET_COUNT)) {
                tag.remove(AE2OC_NET_COUNT);
                if (tag.isEmpty()) {
                    stack.setTag(null);
                }
            }
        }
    }

    /**
     * readFromStream TAIL: 恢复超量物品的真实数量。
     */
    @Inject(
            method = "readFromStream",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_afterReadFromStream(FriendlyByteBuf data, CallbackInfoReturnable<Boolean> cir) {
        var inv = getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                CompoundTag tag = stack.getTag();
                if (tag != null && tag.contains(AE2OC_NET_COUNT)) {
                    int realCount = tag.getInt(AE2OC_NET_COUNT);
                    tag.remove(AE2OC_NET_COUNT);
                    if (tag.isEmpty()) {
                        stack.setTag(null);
                    }
                    if (realCount > 0) {
                        stack.setCount(realCount);
                    }
                }
            }
        }
    }
}
