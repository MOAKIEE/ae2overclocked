package moakiee.ae2oc.client;

import appeng.menu.AEBaseMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Authoritative operations never move the presentation wrapper into a player inventory. */
public final class LogicalMenuInteraction {
    private LogicalMenuInteraction() {}
    public static boolean click(AEBaseMenu menu, int index, int button, ClickType type, Player player) {
        if (index < 0 || index >= menu.slots.size() || !(menu.getSlot(index) instanceof LogicalMenuSlot slot)) return false;
        if (menu.isClientSide() || !slot.isSlotEnabled()) return true;
        var current = slot.backingItem();
        var cursor = menu.getCarried();
        switch (type) {
            case PICKUP -> {
                if (button != 0 && button != 1) return true;
                if (cursor.isEmpty() && slot.mayPickup(player) && !current.isEmpty()) {
                    int count = button == 1 ? (int) Math.min(current.getMaxStackSize(), slot.amount() / 2 + slot.amount() % 2) : current.getMaxStackSize();
                    menu.setCarried(slot.remove(count));
                } else if (!cursor.isEmpty() && !current.isEmpty()
                        && !ItemStack.isSameItemSameTags(cursor, current)) {
                    var exchanged = slot.exchange(cursor, player);
                    if (exchanged != null) menu.setCarried(exchanged);
                } else if (!cursor.isEmpty() && slot.mayPlace(cursor)) {
                    slot.safeInsert(cursor, button == 1 ? 1 : cursor.getCount());
                    menu.setCarried(cursor);
                } else if (!cursor.isEmpty() && ItemStack.isSameItemSameTags(cursor, current) && slot.mayPickup(player)) {
                    cursor.grow(slot.remove(Math.max(0, cursor.getMaxStackSize() - cursor.getCount())).getCount());
                    menu.setCarried(cursor);
                }
            }
            case QUICK_MOVE -> {
                if (slot.mayPickup(player) && !current.isEmpty()) {
                    var extracted = slot.remove(current.getMaxStackSize());
                    player.getInventory().add(extracted);
                    slot.restoreExtracted(extracted);
                }
            }
            case THROW -> {
                if ((button == 0 || button == 1) && slot.mayPickup(player) && !current.isEmpty())
                    player.drop(slot.remove(button == 0 ? 1 : current.getMaxStackSize()), true);
            }
            case CLONE -> {
                if (player.getAbilities().instabuild && !current.isEmpty()) menu.setCarried(current.copyWithCount(current.getMaxStackSize()));
            }
            case PICKUP_ALL -> {
                if (!cursor.isEmpty() && ItemStack.isSameItemSameTags(cursor, current) && slot.mayPickup(player)) {
                    cursor.grow(slot.remove(Math.max(0, cursor.getMaxStackSize() - cursor.getCount())).getCount());
                    menu.setCarried(cursor);
                }
            }
            case SWAP, QUICK_CRAFT -> { /* Presentation slots do not support direct hotbar/offhand swaps or dragging. */ }
        }
        menu.broadcastChanges();
        return true;
    }
}
