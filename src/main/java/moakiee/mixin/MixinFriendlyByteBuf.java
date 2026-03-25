package moakiee.mixin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 FriendlyByteBuf 对 ItemStack 数量使用 byte 编码的限制。
 * count > 64 时完全接管序列化，将数量改用 int 编码。
 *
 * 参考外部修复方案 AE2OverclockedBiggerStacksFix 实现。
 */
@Mixin(value = FriendlyByteBuf.class, remap = false, priority = 2000)
public class MixinFriendlyByteBuf {

    /**
     * count > 64 时接管 writeItemStack，将数量改用 writeInt 编码。
     */
    @Inject(
            method = {"writeItemStack", "m_130057_"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_writeItemStack(ItemStack stack, boolean limitedTag, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        if (stack.isEmpty() || stack.getCount() <= 64) {
            return; // 正常数量，让原版处理
        }

        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;

        // 1. boolean true（非空）
        buf.writeBoolean(true);
        // 2. item ID（使用原版 Registry ID 编码方式）
        buf.writeId(BuiltInRegistries.ITEM, stack.getItem());
        // 3. 用 writeInt 代替 writeByte 编码数量
        buf.writeInt(stack.getCount());
        // 4. NBT tag
        Item item = stack.getItem();
        CompoundTag compoundtag = null;
        if (item.isDamageable(stack) || item.shouldOverrideMultiplayerNbt()) {
            compoundtag = limitedTag ? stack.getShareTag() : stack.getTag();
        }
        buf.writeNbt(compoundtag);

        cir.setReturnValue(buf);
    }

    /**
     * readItem HEAD: 检测是否为超大堆叠的自定义格式。
     * 如果 count > 64，说明是用 writeInt 写的，走自定义读取路径。
     */
    @Inject(
            method = {"m_130267_"}, // readItem
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_readItem(CallbackInfoReturnable<ItemStack> cir) {
        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;

        // 标记当前读取位置，以便回退
        buf.markReaderIndex();

        if (!buf.readBoolean()) {
            // 空物品
            cir.setReturnValue(ItemStack.EMPTY);
            cir.cancel();
            return;
        }

        Item item = buf.readById(BuiltInRegistries.ITEM);
        int count = buf.readInt(); // 尝试按 int 读取

        if (count <= 64) {
            // count <= 64 说明可能是原版格式（用 writeByte 写的），
            // 回退让原版处理
            buf.resetReaderIndex();
            return;
        }

        // count > 64，这是我们自定义的格式
        ItemStack itemstack = new ItemStack(item, count);
        itemstack.readShareTag(buf.readNbt());

        cir.setReturnValue(itemstack);
        cir.cancel();
    }
}
