package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 InscriberThread (ExInscriber的工作线程) 网络同步超量堆叠物品的问题。
 */
@Mixin(targets = "com.glodblock.github.extendedae.common.me.InscriberThread", remap = false)
public abstract class MixinInscriberThreadNetwork {

    @Unique
    private static final Logger AE2OC_LOGGER = LogUtils.getLogger();

    @Unique
    private static final String AE2OC_NET_COUNT = "ae2ocNetCount";

    @Shadow
    private InternalInventory inv;

    /**
     * 在 writeToStream 方法执行前，为超量物品添加数量标记。
     */
    @Inject(
            method = "writeToStream",
            at = @At("HEAD"),
            require = 0
    )
    private void ae2oc_beforeWriteToStream(FriendlyByteBuf data, CallbackInfo ci) {
        AE2OC_LOGGER.info("[AE2OC] InscriberThread.writeToStream HEAD called");
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getCount() > 127) {
                CompoundTag tag = stack.getOrCreateTag();
                tag.putInt(AE2OC_NET_COUNT, stack.getCount());
                AE2OC_LOGGER.info("[AE2OC] InscriberThread.writeToStream slot={} count={}", i, stack.getCount());
            }
        }
    }

    /**
     * 在 writeToStream 方法执行后，清理临时标记。
     */
    @Inject(
            method = "writeToStream",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_afterWriteToStream(FriendlyByteBuf data, CallbackInfo ci) {
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
     * 在 readFromStream 方法执行后，恢复超量物品的真实数量。
     */
    @Inject(
            method = "readFromStream",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_afterReadFromStream(FriendlyByteBuf data, CallbackInfo ci) {
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
                        AE2OC_LOGGER.info("[AE2OC] InscriberThread.readFromStream slot={} count={}", i, realCount);
                    }
                }
            }
        }
    }
}
