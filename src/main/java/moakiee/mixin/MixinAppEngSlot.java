package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 AppEngSlot.getMaxStackSize(ItemStack) 的 64 上限。
 * 原版实现: Math.min(this.getMaxStackSize(), stack.getMaxStackSize())
 * 即使 slot limit > 64，也被 stack.getMaxStackSize() (=64) 封顶。
 * 本 mixin 在 slot limit > 64 时，直接返回 slot limit。
 */
@Mixin(value = AppEngSlot.class, remap = false)
public abstract class MixinAppEngSlot {

    // AE2 自有字段，不受 SRG 映射影响
    @Shadow
    @Final
    private InternalInventory inventory;

    @Shadow
    @Final
    private int invSlot;

    /**
     * 多名称注入：dev 环境用 getMaxStackSize，运行时用 m_5866_（SRG 名）。
     * require = 0 保证在任一环境中不崩溃。
     */
    @Inject(
            method = {
                "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I",
                "m_5866_(Lnet/minecraft/world/item/ItemStack;)I"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_getMaxStackSize(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        // 跳过不可堆叠物品（工具/盔甲）和 GenericStack 流体封装（maxStackSize=1）
        // 否则会破坏流体槽位的点击/显示逻辑
        if (stack.getMaxStackSize() <= 1) {
            return;
        }
        // 直接通过 AE2 字段获取 slot limit，绕过 SRG 映射的 getMaxStackSize() 调用
        int slotLimit = this.inventory.getSlotLimit(this.invSlot);
        if (slotLimit > stack.getMaxStackSize()) {
            cir.setReturnValue(slotLimit);
        }
    }
}