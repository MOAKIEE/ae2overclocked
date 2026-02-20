package moakiee.mixin;

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
    private boolean isPlayerSideSlot(Slot slot) {
        return false;
    }

    @Shadow
    public abstract boolean isClientSide();

    @Shadow
    protected abstract ItemStack transferStackToMenu(ItemStack input);

    @Inject(
            method = {
                    "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;",
                    "m_7648_(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;"
            },
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

        final Slot clickSlot = menu.slots.get(idx);
        boolean playerSide = isPlayerSideSlot(clickSlot);

        if (clickSlot instanceof DisabledSlot || clickSlot instanceof InaccessibleSlot) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (clickSlot.hasItem()) {
            ItemStack tis = clickSlot.getItem();
            if (tis.isEmpty()) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }

            final List<Slot> selectedSlots = new ArrayList<>();

            if (playerSide) {
                tis = this.transferStackToMenu(tis);
                if (!tis.isEmpty()) {
                    for (Slot cs : menu.slots) {
                        if (!isPlayerSideSlot(cs)
                                && !(cs instanceof FakeSlot)
                                && !(cs instanceof CraftingMatrixSlot)
                                && cs.mayPlace(tis)) {
                            selectedSlots.add(cs);
                        }
                    }
                }
            } else {
                tis = tis.copy();
                for (Slot cs : menu.slots) {
                    if (isPlayerSideSlot(cs)
                            && !(cs instanceof FakeSlot)
                            && !(cs instanceof CraftingMatrixSlot)
                            && cs.mayPlace(tis)) {
                        selectedSlots.add(cs);
                    }
                }
            }

            if (selectedSlots.isEmpty() && playerSide && !tis.isEmpty()) {
                for (Slot cs : menu.slots) {
                    if (cs instanceof FakeSlot && !isPlayerSideSlot(cs)) {
                        var destination = cs.getItem();
                        if (ItemStack.isSameItemSameTags(destination, tis)) {
                            break;
                        } else if (destination.isEmpty()) {
                            cs.set(tis.copy());
                            menu.broadcastChanges();
                            break;
                        }
                    }
                }
            }

            if (!tis.isEmpty()) {
                for (Slot d : selectedSlots) {
                    if (d.mayPlace(tis) && d.hasItem() && ae2oc_merge(menu, clickSlot, tis, d)) {
                        cir.setReturnValue(ItemStack.EMPTY);
                        return;
                    }
                }

                for (Slot d : selectedSlots) {
                    if (!d.mayPlace(tis)) {
                        continue;
                    }

                    if (d.hasItem()) {
                        if (ae2oc_merge(menu, clickSlot, tis, d)) {
                            cir.setReturnValue(ItemStack.EMPTY);
                            return;
                        }
                    } else {
                        int maxSize = d.getMaxStackSize(tis);

                        final ItemStack tmp = tis.copy();
                        if (tmp.getCount() > maxSize) {
                            tmp.setCount(maxSize);
                        }

                        tis.setCount(tis.getCount() - tmp.getCount());
                        d.set(tmp);

                        if (tis.getCount() <= 0) {
                            clickSlot.set(ItemStack.EMPTY);
                            d.setChanged();
                            menu.broadcastChanges();
                            cir.setReturnValue(ItemStack.EMPTY);
                            return;
                        } else {
                            menu.broadcastChanges();
                        }
                    }
                }
            }

            clickSlot.set(!tis.isEmpty() ? tis : ItemStack.EMPTY);
        }

        menu.broadcastChanges();
        cir.setReturnValue(ItemStack.EMPTY);
    }

    private static boolean ae2oc_merge(AEBaseMenu menu, Slot clickSlot, ItemStack tis, Slot destination) {
        final ItemStack target = destination.getItem().copy();

        if (!ItemStack.isSameItemSameTags(target, tis)) {
            return false;
        }

        int maxSize = destination.getMaxStackSize(tis);
        int placeable = maxSize - target.getCount();
        if (placeable <= 0) {
            return false;
        }

        if (tis.getCount() < placeable) {
            placeable = tis.getCount();
        }

        target.setCount(target.getCount() + placeable);
        tis.setCount(tis.getCount() - placeable);

        destination.set(target);

        if (tis.getCount() <= 0) {
            clickSlot.set(ItemStack.EMPTY);
            destination.setChanged();
            menu.broadcastChanges();
            menu.broadcastChanges();
            return true;
        }

        menu.broadcastChanges();
        return false;
    }
}
