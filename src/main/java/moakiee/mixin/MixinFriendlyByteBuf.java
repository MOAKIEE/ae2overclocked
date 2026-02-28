package moakiee.mixin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 FriendlyByteBuf 对 ItemStack 数量使用 byte 编码的限制。
 * count > 64 时完全接管序列化，将真实数量存入 tag 的 ae2ocNetCount 字段。
 */
@Mixin(value = FriendlyByteBuf.class, remap = false)
public class MixinFriendlyByteBuf {

    @Unique
    private static final String AE2OC_NET_COUNT = "ae2ocNetCount";

    /**
     * count > 64 时接管 writeItem，将真实数量编码到 NBT 中。
     */
    @Inject(
            method = {"writeItem", "m_130055_"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_writeItem(ItemStack stack, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        if (stack.isEmpty() || stack.getCount() <= 64) {
            return;
        }

        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;

        // 1. boolean true（非空）
        buf.writeBoolean(true);
        // 2. item ID
        buf.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getItem()));
        // 3. 占位 count=1，真实数量在 tag 中
        buf.writeByte(1);
        // 4. 构建 tag
        CompoundTag shareTag = stack.getShareTag();
        CompoundTag tagToSend;
        if (shareTag != null) {
            tagToSend = shareTag.copy();
        } else {
            tagToSend = new CompoundTag();
        }
        tagToSend.putInt(AE2OC_NET_COUNT, stack.getCount());
        buf.writeNbt(tagToSend);

        cir.setReturnValue(buf);
    }

    /**
     * readItem RETURN: 从 tag 中恢复 ae2ocNetCount。
     */
    @Inject(
            method = {"readItem", "m_130267_"},
            at = @At("RETURN"),
            require = 0
    )
    private void ae2oc_readItem(CallbackInfoReturnable<ItemStack> cir) {
        ItemStack stack = cir.getReturnValue();
        if (stack == null || stack.isEmpty()) {
            return;
        }

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(AE2OC_NET_COUNT, 3 /* TAG_INT */)) {
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
