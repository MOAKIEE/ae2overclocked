package moakiee.mixin;

import appeng.api.upgrades.IUpgradeableObject;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import moakiee.Ae2OcConfig;
import moakiee.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 堆叠卡功能注入：装有堆叠卡时，提升槽位上限。
 * NBT 读写时通过 ae2ocCount 字段保存/恢复超量堆叠。
 */
@Mixin(value = AppEngInternalInventory.class, remap = false)
public class MixinAppEngInternalInventory {

    private static final String AE2OC_COUNT_KEY = "ae2ocCount";

    @Shadow
    @Final
    private NonNullList<ItemStack> stacks;

    @Inject(method = "getSlotLimit", at = @At("HEAD"), cancellable = true)
    private void ae2oc_getSlotLimit(int slot, CallbackInfoReturnable<Integer> cir) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        // 获取宿主（BlockEntity）
        InternalInventoryHost host = inventory.getHost();

        // 只有装了堆叠卡时才允许超过 64
        if (getInstalledCapacityCards(host, 0) > 0) {
            cir.setReturnValue(Ae2OcConfig.getCapacityCardSlotLimit());
        }
        // 不干预时让原版返回 64
    }

    /**
     * 修复 extractItem 中 stack.getMaxStackSize() 封顶 64 的问题。
     */
    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void ae2oc_extractItem(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        if (slot < 0 || slot >= inventory.size()) {
            return; // let original handle bounds error
        }

        var stack = this.stacks.get(slot);
        // 只在超过 64 时介入
        if (stack.isEmpty() || stack.getCount() <= stack.getMaxStackSize()) {
            return;
        }

        // 超过 64 的堆叠：直接用 stack.getCount() 做上限
        int toExtract = Math.min(stack.getCount(), amount);
        if (toExtract <= 0) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (stack.getCount() <= toExtract) {
            if (!simulate) {
                inventory.setItemDirect(slot, ItemStack.EMPTY);
            }
            cir.setReturnValue(simulate ? stack.copy() : stack);
        } else {
            var result = stack.copy();
            if (!simulate) {
                stack.shrink(toExtract);
                // setItemDirect 已包含 notifyContentsChanged
                inventory.setItemDirect(slot, stack);
            }
            result.setCount(toExtract);
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "writeToNBT", at = @At("HEAD"), cancellable = true)
    private void ae2oc_writeToNBT(CompoundTag data, String name, CallbackInfo ci) {
        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        if (inventory.isEmpty()) {
            data.remove(name);
            ci.cancel();
            return;
        }

        boolean hasOversized = false;
        for (int i = 0; i < this.stacks.size(); i++) {
            if (this.stacks.get(i).getCount() > 64) {
                hasOversized = true;
                break;
            }
        }

        // 如果没有超过 64 的堆叠，让原版逻辑处理，保持最大兼容性
        if (!hasOversized) {
            return;
        }

        var items = new ListTag();
        for (int slot = 0; slot < this.stacks.size(); slot++) {
            var stack = this.stacks.get(slot);
            if (stack.isEmpty()) {
                continue;
            }

            CompoundTag itemTag = new CompoundTag();
            itemTag.putInt("Slot", slot);

            int realCount = stack.getCount();
            if (realCount > 64) {
                // 超过 64：Count 设为 1 防止原版截断，真实数量存 ae2ocCount
                ItemStack oneCopy = stack.copy();
                oneCopy.setCount(1);
                oneCopy.save(itemTag);
                itemTag.putInt(AE2OC_COUNT_KEY, realCount);
            } else {
                // ≤ 64：正常保存，不添加 ae2ocCount，与原版完全兼容
                stack.save(itemTag);
            }

            items.add(itemTag);
        }

        data.put(name, items);
        ci.cancel();
    }

    @Inject(method = "readFromNBT", at = @At("HEAD"), cancellable = true)
    private void ae2oc_readFromNBT(CompoundTag data, String name, CallbackInfo ci) {
        // 只有 NBT 中包含 ae2ocCount 标记时才接管；否则让原版处理
        if (!data.contains(name, Tag.TAG_LIST)) {
            return;
        }

        var tagList = data.getList(name, Tag.TAG_COMPOUND);

        // 扫描是否有任何条目含 ae2ocCount
        boolean hasOversized = false;
        for (var rawTag : tagList) {
            var itemTag = (CompoundTag) rawTag;
            if (itemTag.contains(AE2OC_COUNT_KEY, Tag.TAG_INT)) {
                hasOversized = true;
                break;
            }
        }

        // 不含超量标记 → 让原版 readFromNBT 处理，保持兼容
        if (!hasOversized) {
            return;
        }

        AppEngInternalInventory inventory = (AppEngInternalInventory) (Object) this;

        // 清空所有槽位
        for (int i = 0; i < this.stacks.size(); i++) {
            this.stacks.set(i, ItemStack.EMPTY);
        }

        for (var rawTag : tagList) {
            var itemTag = (CompoundTag) rawTag;
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= this.stacks.size()) {
                continue;
            }

            ItemStack stack;
            if (itemTag.contains(AE2OC_COUNT_KEY, Tag.TAG_INT)) {
                // 有 ae2ocCount：用 Count=1 解析物品，再恢复真实数量
                var fixedTag = itemTag.copy();
                fixedTag.putByte("Count", (byte) 1);
                stack = ItemStack.of(fixedTag);
                int restoredCount = itemTag.getInt(AE2OC_COUNT_KEY);
                if (restoredCount <= 0) {
                    restoredCount = 1;
                }
                stack.setCount(restoredCount);
            } else {
                // 正常条目：原版逻辑
                stack = ItemStack.of(itemTag);
            }

            this.stacks.set(slot, stack);
        }

        ci.cancel();
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
