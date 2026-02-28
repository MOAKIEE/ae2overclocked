package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 AE2 机器超量堆叠 NBT 持久化问题。
 * saveAdditional TAIL: 重写超过 64 的槽位数据，加入 ae2ocCount 标记。
 * loadTag TAIL: 从 ae2ocCount 标记恢复真实数量。
 */
@Mixin(targets = "appeng.blockentity.AEBaseInvBlockEntity", remap = false)
public abstract class MixinAEBaseInvBlockEntity {

    private static final String AE2OC_COUNT_KEY = "ae2ocCount";

    @Shadow
    public abstract InternalInventory getInternalInventory();

    /**
     * saveAdditional TAIL: 重写超过 64 的槽位数据。
     */
    @Inject(
            method = {
                    "saveAdditional(Lnet/minecraft/nbt/CompoundTag;)V",
                    "m_183515_(Lnet/minecraft/nbt/CompoundTag;)V"
            },
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_saveAdditionalTail(CompoundTag data, CallbackInfo ci) {
        var inv = this.getInternalInventory();
        if (inv == InternalInventory.empty()) {
            return;
        }

        // 检查是否有超过 64 的堆叠
        boolean hasOversized = false;
        for (int x = 0; x < inv.size(); x++) {
            if (inv.getStackInSlot(x).getCount() > 64) {
                hasOversized = true;
                break;
            }
        }

        if (!hasOversized) {
            return;
        }

        // 有超量堆叠：重新写入 inventory 数据，覆盖原版写入的数据
        if (!data.contains("inv")) {
            return;
        }

        var opt = data.getCompound("inv");
        for (int x = 0; x < inv.size(); x++) {
            final ItemStack is = inv.getStackInSlot(x);
            if (!is.isEmpty() && is.getCount() > 64) {
                // 重新写入这个槽位
                final CompoundTag item = new CompoundTag();
                ItemStack oneCopy = is.copy();
                oneCopy.setCount(1);
                oneCopy.save(item);
                item.putInt(AE2OC_COUNT_KEY, is.getCount());
                opt.put("item" + x, item);
            }
        }
    }

    // 旧版网络同步遗留标签名
    private static final String AE2OC_NET_COUNT_LEGACY = "ae2ocNetCount";

    /**
     * loadTag TAIL: 从 ae2ocCount 标记恢复超量堆叠，并清理遗留标签。
     */
    @Inject(
            method = {
                    "loadTag(Lnet/minecraft/nbt/CompoundTag;)V",
                    "m_155245_(Lnet/minecraft/nbt/CompoundTag;)V"
            },
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_loadTagTail(CompoundTag data, CallbackInfo ci) {
        if (!data.contains("inv")) {
            return;
        }

        var opt = data.getCompound("inv");
        var inv = this.getInternalInventory();
        if (inv == InternalInventory.empty()) {
            return;
        }

        // 检查每个槽位是否有 ae2ocCount 标记
        for (int x = 0; x < inv.size(); x++) {
            var item = opt.getCompound("item" + x);
            if (item.contains(AE2OC_COUNT_KEY)) {
                int restoredCount = item.getInt(AE2OC_COUNT_KEY);
                if (restoredCount > 0) {
                    // 直接修改 count，不调用 setItemDirect 避免触发 wakeDevice
                    ItemStack stack = inv.getStackInSlot(x);
                    if (!stack.isEmpty()) {
                        stack.setCount(restoredCount);
                    }
                }
            }
            // 清理旧版遗留标签
            ItemStack stack = inv.getStackInSlot(x);
            if (!stack.isEmpty()) {
                CompoundTag tag = stack.getTag();
                if (tag != null && tag.contains(AE2OC_NET_COUNT_LEGACY)) {
                    tag.remove(AE2OC_NET_COUNT_LEGACY);
                    if (tag.isEmpty()) {
                        stack.setTag(null);
                    }
                }
            }
        }
    }
}
