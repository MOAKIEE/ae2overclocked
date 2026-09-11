package moakiee.mixin;

import appeng.menu.AEBaseMenu;
import appeng.menu.slot.AppEngSlot;
import moakiee.ae2oc.client.LogicalMenuSlot;
import moakiee.ae2oc.client.LogicalMenuInteraction;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes only explicitly managed slots; other menus and slots retain upstream behavior. */
@Mixin(value = AEBaseMenu.class, remap = false)
public abstract class MixinLogicalMenu {
    @ModifyVariable(method = "addSlot(Lnet/minecraft/world/inventory/Slot;Lappeng/menu/SlotSemantic;)Lnet/minecraft/world/inventory/Slot;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Slot ae2oc_projectSlot(Slot slot) {
        if (slot instanceof AppEngSlot aeSlot && !(slot instanceof LogicalMenuSlot)
                && ManagedItemStorages.isManaged(aeSlot.getInventory()))
            return new LogicalMenuSlot(aeSlot, ((AccessorAppEngSlot) aeSlot).ae2oc$inventorySlot());
        return slot;
    }
    @Inject(method = {"clicked", "m_150399_"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void ae2oc_click(int index, int button, ClickType type, Player player, CallbackInfo ci) {
        var menu = (AEBaseMenu) (Object) this;
        if (LogicalMenuInteraction.click(menu, index, button, type, player)) { ci.cancel(); return; }
        if (type != ClickType.QUICK_MOVE || index < 0 || index >= menu.slots.size()) return;
        var source = menu.getSlot(index);
        if (source.container != player.getInventory() || !source.mayPickup(player)) return;
        var stack = source.getItem().copy();
        if (stack.isEmpty()) return;
        int before = stack.getCount();
        for (var destination : menu.slots) if (destination instanceof LogicalMenuSlot logical && logical.mayPlace(stack)) {
            if (menu.isClientSide()) { ci.cancel(); return; }
            logical.safeInsert(stack, stack.getCount());
            if (stack.isEmpty()) break;
        }
        if (before != stack.getCount()) {
            source.set(stack);
            menu.broadcastChanges();
            ci.cancel();
        }
    }
}
