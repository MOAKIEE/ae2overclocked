package moakiee.mixin;

import appeng.api.upgrades.IUpgradeableObject;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.google.common.base.Preconditions;
import moakiee.ModItems;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 堆叠卡功能注入：当机器装有堆叠卡时，将所有内部槽位的上限改为 Integer.MAX_VALUE。
 *
 * 安全分析：
 * - NBT 加载：readFromNBT 直接调用 stacks.set()，绕过 maxStack 检查，无截断风险
 * - 拔卡保护：无需额外代码，拔卡后 getSlotLimit() 即恢复 64，insertItem 自动拒绝超限新物品
 * - 溢出喷射：不在此编写，依赖 IItemHandler 的"只出不进"天然安全状态
 */
@Mixin(value = AppEngInternalInventory.class, remap = false)
public class MixinAppEngInternalInventory {

    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        Preconditions.checkArgument(slot >= 0 && slot < inventory.size(), "slot out of range");

        if (stack.isEmpty() || !inventory.isItemValid(slot, stack)) {
            return stack;
        }

        var inSlot = inventory.getStackInSlot(slot);
        int maxSpace = inventory.getSlotLimit(slot);
        int freeSpace = maxSpace - inSlot.getCount();
        if (freeSpace <= 0) {
            return stack;
        }

        if (!inSlot.isEmpty() && !ItemStack.isSameItemSameTags(inSlot, stack)) {
            return stack;
        }

        int insertAmount = Math.min(stack.getCount(), freeSpace);
        if (!simulate) {
            var newItem = inSlot.isEmpty() ? stack.copy() : inSlot.copy();
            newItem.setCount(inSlot.getCount() + insertAmount);
            inventory.setItemDirect(slot, newItem);
        }

        if (freeSpace >= stack.getCount()) {
            return ItemStack.EMPTY;
        } else {
            var remainder = stack.copy();
            remainder.shrink(insertAmount);
            return remainder;
        }
    }

    @Inject(method = "getSlotLimit", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getSlotLimit(int slot, CallbackInfoReturnable<Integer> cir) {
        // 获取宿主（BlockEntity）
        InternalInventoryHost host = ((AppEngInternalInventory) (Object) this).getHost();

        if (getInstalledCapacityCards(host, 0) > 0) {
            // 若装了堆叠卡，返回 int 最大值（约 21 亿）
            cir.setReturnValue(Integer.MAX_VALUE);
        }
    }

    private static int getInstalledCapacityCards(Object target, int depth) {
        if (target == null || depth > 4) {
            return 0;
        }

        if (target instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades().getInstalledUpgrades(ModItems.CAPACITY_CARD.get());
        }

        Integer fromGetUpgrades = tryGetInstalledFromGetUpgrades(target);
        if (fromGetUpgrades != null && fromGetUpgrades > 0) {
            return fromGetUpgrades;
        }

        Object byGetBlockEntity = tryInvokeNoArg(target, "getBlockEntity");
        int fromBlockEntity = getInstalledCapacityCards(byGetBlockEntity, depth + 1);
        if (fromBlockEntity > 0) {
            return fromBlockEntity;
        }

        Object byGetHost = tryInvokeNoArg(target, "getHost");
        int fromHost = getInstalledCapacityCards(byGetHost, depth + 1);
        if (fromHost > 0) {
            return fromHost;
        }

        Object byHostField = tryGetField(target, "host");
        return getInstalledCapacityCards(byHostField, depth + 1);
    }

    private static Integer tryGetInstalledFromGetUpgrades(Object target) {
        try {
            Method getUpgrades = target.getClass().getMethod("getUpgrades");
            Object upgrades = getUpgrades.invoke(target);
            if (upgrades == null) {
                return null;
            }
            Method getInstalled = upgrades.getClass().getMethod("getInstalledUpgrades", net.minecraft.world.level.ItemLike.class);
            Object result = getInstalled.invoke(upgrades, ModItems.CAPACITY_CARD.get());
            if (result instanceof Integer i) {
                return i;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryGetField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
