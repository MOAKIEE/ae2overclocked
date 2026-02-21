package moakiee.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 vanilla FriendlyByteBuf 对 ItemStack 数量使用 byte 编码的限制。
 * <p>
 * 原版 writeItem() 将 count 写为 byte（-128~127），超过 127 的堆叠会溢出。
 * 本 mixin 完全接管大数量物品的序列化，将真实数量存入 tag 的 ae2ocNetCount 字段。
 * <p>
 * 注意：不修改原始 ItemStack，避免影响游戏状态。
 * 
 * 使用 remap = false 因为 official 映射环境下 Mixin 处理器无法找到映射。
 * 方法名同时提供 official (writeItem) 和 SRG (m_130055_) 以兼容两种环境。
 */
@Mixin(value = FriendlyByteBuf.class, remap = false)
public class MixinFriendlyByteBuf {

    @Unique
    private static final Logger AE2OC_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ae2oc_loggedOnce = false;

    @Unique
    private static final String AE2OC_NET_COUNT = "ae2ocNetCount";

    // 静态初始化块，确认 Mixin 类被加载
    static {
        LogUtils.getLogger().info("[AE2OC] MixinFriendlyByteBuf class loaded");
    }

    /**
     * writeItem: 完全接管 count > 127 的物品序列化。
     * Vanilla 1.20.1 格式: boolean(非空) + VarInt(itemId) + byte(count) + NBT(shareTag)
     */
    @Inject(
            method = "writeItem",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_writeItem(ItemStack stack, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        // 只在第一次调用时记录日志，确认 Mixin 已应用
        if (!ae2oc_loggedOnce) {
            AE2OC_LOGGER.info("[AE2OC] MixinFriendlyByteBuf.writeItem is active");
            ae2oc_loggedOnce = true;
        }

        if (stack.isEmpty() || stack.getCount() <= 127) {
            return; // 正常范围，让原版处理
        }

        // count > 127: 完全接管序列化
        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;

        AE2OC_LOGGER.info("[AE2OC] writeItem intercepted: {} x{}", stack.getItem(), stack.getCount());

        // 1. 写入 boolean true（表示物品非空）
        buf.writeBoolean(true);

        // 2. 写入 item ID (VarInt)
        buf.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getItem()));

        // 3. 写 byte count = 1（占位符，真实数量在 tag 中）
        buf.writeByte(1);

        // 4. 构建带有真实数量的 tag（不修改原始 stack）
        CompoundTag shareTag = stack.getShareTag();
        CompoundTag tagToSend;
        if (shareTag != null) {
            tagToSend = shareTag.copy();
        } else {
            tagToSend = new CompoundTag();
        }
        tagToSend.putInt(AE2OC_NET_COUNT, stack.getCount());

        // 5. 写入 tag
        buf.writeNbt(tagToSend);

        cir.setReturnValue(buf);
    }

    /**
     * readItem RETURN: 检查 tag 中的 ae2ocNetCount 并恢复真实数量。
     */
    @Inject(
            method = "readItem",
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
                AE2OC_LOGGER.info("[AE2OC] readItem restored: {} x{}", stack.getItem(), realCount);
            }
        }
    }
}
