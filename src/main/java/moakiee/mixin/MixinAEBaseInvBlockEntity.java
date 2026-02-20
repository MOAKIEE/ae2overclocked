package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 AE2 机器 (继承自 AEBaseInvBlockEntity) 的超量堆叠 NBT 持久化问题。
 *
 * 原版问题：
 * - saveAdditional: is.save(item) → ItemStack.save() 将 Count 存为 byte，超过 127 会溢出
 * - loadTag: ItemStack.of(item) → 只能读取 byte 范围的 Count
 *
 * 解决方案：
 * - saveAdditional TAIL: 检测超量堆叠，重新写入正确的 inventory 数据
 * - loadTag TAIL: 检测 ae2ocCount 标记，恢复真实数量
 */
@Mixin(targets = "appeng.blockentity.AEBaseInvBlockEntity", remap = false)
public abstract class MixinAEBaseInvBlockEntity {

    private static final String AE2OC_COUNT_KEY = "ae2ocCount";
    private static final org.slf4j.Logger AE2OC_LOGGER = LogUtils.getLogger();

    @Shadow
    public abstract InternalInventory getInternalInventory();

    /**
     * 在原版 saveAdditional 完成后，检查并修复超量堆叠的保存。
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
            return; // 没有超量堆叠，原版保存已经正确
        }

        // 有超量堆叠：重新写入 inventory 数据，覆盖原版写入的数据
        if (!data.contains("inv")) {
            return; // 原版没有写入 inv，说明 inventory 为空
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
                AE2OC_LOGGER.info("[AE2OC] saveAdditional slot={} count={}", x, is.getCount());
            }
        }
    }

    /**
     * 在原版 loadTag 完成后，检查并恢复超量堆叠。
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
                    // 获取原版加载的物品（此时 count 可能是错的）
                    ItemStack stack = inv.getStackInSlot(x);
                    if (!stack.isEmpty()) {
                        stack.setCount(restoredCount);
                        inv.setItemDirect(x, stack);
                        AE2OC_LOGGER.info("[AE2OC] loadTag slot={} count={}", x, restoredCount);
                    }
                }
            }
        }
    }
}
