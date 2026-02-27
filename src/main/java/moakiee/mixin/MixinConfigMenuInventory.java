package moakiee.mixin;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.util.ConfigMenuInventory;
import moakiee.support.OverstackingRegistry;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 ConfigMenuInventory 对超堆叠物品的处理。
 * 
 * 核心问题：ConfigMenuInventory.getStackInSlot() 在物品数量 > 64 时返回 wrapped item，
 * 导致 AppEngSlot 的 containsWrapperItem() 为 true，进而：
 * - mayPlace() 返回 false（放不进去）
 * - mayPickup() 返回 false（拿不出来）  
 * - remove() 返回 EMPTY（取不出来）
 * 
 * 解决方案：注入 getStackInSlot，在超堆叠模式下返回真实 ItemStack（count = 实际数量），
 * 不再使用 wrapped item。同时注入 getSlotLimit 返回正确的容量上限。
 */
@Mixin(value = ConfigMenuInventory.class, remap = false)
public abstract class MixinConfigMenuInventory {

    @Shadow
    @Final
    private GenericStackInv inv;

    /**
     * 注入 getStackInSlot：在超堆叠模式下返回真实 ItemStack。
     * 
     * 原版逻辑：数量 > maxStackSize 时返回 wrapped item（WrappedGenericStack）
     * 修改后：在超堆叠注册的 inventory 中，返回真实 ItemStack，数量直接设为实际数量。
     * 
     * 这样 containsWrapperItem() 返回 false，所有操作正常工作。
     * MixinAEBaseScreen 负责将大数字格式化显示（如 "1K"、"2.5M"）。
     */
    @Inject(method = "getStackInSlot", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getStackInSlot(int slotIndex, CallbackInfoReturnable<ItemStack> cir) {
        if (!ae2oc_isOverstacking()) {
            return;
        }

        GenericStack stack = inv.getStack(slotIndex);
        if (stack == null) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        // 只处理物品类型，流体等使用原版逻辑
        if (stack.what() instanceof AEItemKey itemKey) {
            // 返回真实 ItemStack，数量设为实际数量（截断为 int 范围）
            int count = (int) Math.min(stack.amount(), Integer.MAX_VALUE);
            cir.setReturnValue(itemKey.toStack(count));
        }
    }

    /**
     * 注入 getSlotLimit：在超堆叠模式下返回正确的容量上限。
     * 原版只返回 items 的 capacity，超堆叠时需要返回更大的值。
     */
    @Inject(method = "getSlotLimit", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getSlotLimit(int slot, CallbackInfoReturnable<Integer> cir) {
        if (!ae2oc_isOverstacking()) {
            return;
        }

        long capacity = inv.getCapacity(AEKeyType.items());
        cir.setReturnValue((int) Math.min(capacity, Integer.MAX_VALUE));
    }

    @Unique
    private boolean ae2oc_isOverstacking() {
        return OverstackingRegistry.shouldAllowOverstacking(inv);
    }
}
