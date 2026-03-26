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
 * count > 64 时完全接管序列化：先写一个哨兵字节 0，再用 int 编码数量。
 *
 * 协议格式：
 *   原版：  boolean(true) + VarInt(itemId) + byte(count 1~64) + NBT
 *   自定义：boolean(true) + VarInt(itemId) + byte(0)【哨兵】+ int(count) + NBT
 *
 * 哨兵字节 0 在原版中不会出现（非空物品 count >= 1），因此可安全区分两种格式。
 */
@Mixin(value = FriendlyByteBuf.class, remap = false, priority = 2000)
public class MixinFriendlyByteBuf {

    /**
     * count > 64 时接管 writeItemStack：写哨兵字节 0 + writeInt 编码数量。
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
        // 3. 哨兵字节 0，表示后续 count 用 int 编码
        buf.writeByte(0);
        // 4. 用 writeInt 编码数量
        buf.writeInt(stack.getCount());
        // 5. NBT tag
        Item item = stack.getItem();
        CompoundTag compoundtag = null;
        if (item.isDamageable(stack) || item.shouldOverrideMultiplayerNbt()) {
            compoundtag = limitedTag ? stack.getShareTag() : stack.getTag();
        }
        buf.writeNbt(compoundtag);

        cir.setReturnValue(buf);
    }

    /**
     * readItem HEAD: 通过哨兵字节 0 检测是否为超大堆叠的自定义格式。
     * 原版 count 字节范围 1~64，不会为 0，因此 0 可安全用作标记。
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
        int countByte = buf.readByte(); // 读取 1 字节，与原版 writeByte 对齐

        if (countByte != 0) {
            // 不是哨兵字节，说明是原版格式（count 1~64），回退让原版处理
            buf.resetReaderIndex();
            return;
        }

        // 哨兵字节 0 → 自定义格式，接下来用 readInt 读取真实数量
        int count = buf.readInt();
        ItemStack itemstack = new ItemStack(item, count);
        itemstack.readShareTag(buf.readNbt());

        cir.setReturnValue(itemstack);
        cir.cancel();
    }
}
