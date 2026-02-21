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
 * 修改：将阈值改为 64，处理所有超过原版堆叠上限的情况，避免闪烁问题。
 * 
 * 使用 SRG 方法名：writeItem = m_130055_, readItem = m_130267_
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
     * writeItem: 完全接管 count > 64 的物品序列化。
     * SRG 名: m_130055_
     */
    @Inject(
            method = {"writeItem", "m_130055_"},
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

        if (stack.isEmpty() || stack.getCount() <= 64) {
            return; // 正常范围，让原版处理
        }

        // count > 64: 完全接管序列化，避免任何潜在的截断问题
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
     * SRG 名: m_130267_
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
                AE2OC_LOGGER.info("[AE2OC] readItem restored: {} x{}", stack.getItem(), realCount);
            }
        }
    }
}
