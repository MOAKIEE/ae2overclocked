package moakiee.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.network.FriendlyByteBuf", remap = false)
public class MixinFriendlyByteBufObf {

    @Inject(method = "m_130055_", at = @At("HEAD"), cancellable = true)
    private void ae2oc_writeItemObf(ItemStack stack, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;

        if (stack.isEmpty()) {
            self.writeBoolean(false);
            cir.setReturnValue(self);
            return;
        }

        self.writeBoolean(true);
        Item item = stack.getItem();
        self.writeVarInt(Item.getId(item));
        self.writeVarInt(stack.getCount());

        CompoundTag tag = null;
        if (item.isDamageable(stack) || item.shouldOverrideMultiplayerNbt()) {
            tag = stack.getShareTag();
        }

        self.writeNbt(tag);
        cir.setReturnValue(self);
    }

    @Inject(method = "m_130267_", at = @At("HEAD"), cancellable = true)
    private void ae2oc_readItemObf(CallbackInfoReturnable<ItemStack> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;

        if (!self.readBoolean()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        int itemId = self.readVarInt();
        int count = self.readVarInt();

        ItemStack stack = new ItemStack(Item.byId(itemId), count);
        stack.readShareTag(self.readNbt());
        cir.setReturnValue(stack);
    }
}
