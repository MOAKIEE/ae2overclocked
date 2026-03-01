package xyz.moakiee.ae2_overclocked.mixin;

import appeng.menu.AEBaseMenu;
import appeng.menu.slot.CraftingMatrixSlot;
import appeng.menu.slot.DisabledSlot;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.InaccessibleSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = AEBaseMenu.class, remap = false)
public abstract class MixinAEBaseMenu {

    @Shadow
    public abstract boolean isPlayerSideSlot(Slot slot);

    @Shadow
    public abstract boolean isClientSide();

    @Shadow
    protected abstract int transferStackToMenu(ItemStack input);

    @Inject(
            method = "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ae2oc_quickMoveStack(Player player, int idx, CallbackInfoReturnable<ItemStack> cir) {
        AEBaseMenu menu = (AEBaseMenu) (Object) this;

        if (this.isClientSide()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (idx < 0 || idx >= menu.slots.size()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        Slot clickSlot = menu.slots.get(idx);
        boolean playerSide = this.isPlayerSideSlot(clickSlot);

        if (clickSlot instanceof DisabledSlot || clickSlot instanceof InaccessibleSlot) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (!clickSlot.hasItem()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        ItemStack moving = clickSlot.getItem();
        if (moving.isEmpty()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        List<Slot> selectedSlots = new ArrayList<>();

        if (playerSide) {
            int moved = this.transferStackToMenu(moving.copy());
            if (moved > 0) {
                moving.shrink(Math.min(moved, moving.getCount()));
                if (moving.isEmpty()) {
                    clickSlot.set(ItemStack.EMPTY);
                } else {
                    clickSlot.setChanged();
                }
                menu.broadcastChanges();
            }

            if (!moving.isEmpty()) {
                for (Slot slot : menu.slots) {
                    if (!this.isPlayerSideSlot(slot)
                            && !(slot instanceof FakeSlot)
                            && !(slot instanceof CraftingMatrixSlot)
                            && slot.mayPlace(moving)) {
                        selectedSlots.add(slot);
                    }
                }
            }
        } else {
            moving = moving.copy();
            for (Slot slot : menu.slots) {
                if (this.isPlayerSideSlot(slot)
                        && !(slot instanceof FakeSlot)
                        && !(slot instanceof CraftingMatrixSlot)
                        && slot.mayPlace(moving)) {
                    selectedSlots.add(slot);
                }
            }
        }

        if (selectedSlots.isEmpty() && playerSide && !moving.isEmpty()) {
            for (Slot slot : menu.slots) {
                if (!(slot instanceof FakeSlot) || this.isPlayerSideSlot(slot)) {
                    continue;
                }

                ItemStack destination = slot.getItem();
                if (ItemStack.isSameItemSameComponents(destination, moving)) {
                    break;
                }

                if (destination.isEmpty()) {
                    slot.set(moving.copy());
                    menu.broadcastChanges();
                    break;
                }
            }
        }

        if (!moving.isEmpty()) {
            for (Slot destination : selectedSlots) {
                if (destination.mayPlace(moving)
                        && destination.hasItem()
                        && ae2oc_merge(menu, clickSlot, moving, destination)) {
                    cir.setReturnValue(ItemStack.EMPTY);
                    return;
                }
            }

            for (Slot destination : selectedSlots) {
                if (!destination.mayPlace(moving)) {
                    continue;
                }

                if (destination.hasItem()) {
                    if (ae2oc_merge(menu, clickSlot, moving, destination)) {
                        cir.setReturnValue(ItemStack.EMPTY);
                        return;
                    }
                    continue;
                }

                int maxSize = destination.getMaxStackSize(moving);
                ItemStack moved = moving.copy();
                if (moved.getCount() > maxSize) {
                    moved.setCount(maxSize);
                }

                moving.setCount(moving.getCount() - moved.getCount());
                destination.set(moved);

                if (moving.getCount() <= 0) {
                    clickSlot.set(ItemStack.EMPTY);
                    destination.setChanged();
                    menu.broadcastChanges();
                    cir.setReturnValue(ItemStack.EMPTY);
                    return;
                }

                menu.broadcastChanges();
            }
        }

        clickSlot.set(!moving.isEmpty() ? moving : ItemStack.EMPTY);
        menu.broadcastChanges();
        cir.setReturnValue(ItemStack.EMPTY);
    }

    private static boolean ae2oc_merge(AEBaseMenu menu, Slot clickSlot, ItemStack moving, Slot destination) {
        ItemStack target = destination.getItem().copy();
        if (!ItemStack.isSameItemSameComponents(target, moving)) {
            return false;
        }

        int maxSize = destination.getMaxStackSize(moving);
        int placeable = maxSize - target.getCount();
        if (placeable <= 0) {
            return false;
        }

        if (moving.getCount() < placeable) {
            placeable = moving.getCount();
        }

        target.setCount(target.getCount() + placeable);
        moving.setCount(moving.getCount() - placeable);
        destination.set(target);

        if (moving.getCount() <= 0) {
            clickSlot.set(ItemStack.EMPTY);
            destination.setChanged();
            menu.broadcastChanges();
            return true;
        }

        menu.broadcastChanges();
        return false;
    }
}
