package moakiee.ae2oc.platform.forge;

import java.util.ArrayList;
import java.util.List;
import io.netty.buffer.Unpooled;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;

/** Observes actual synchronizer callbacks through vanilla packet codecs, not live server slots. */
final class MenuPacketProbe implements ContainerSynchronizer {
    final List<ItemStack> slots = new ArrayList<>();
    ItemStack carried = ItemStack.EMPTY;
    int initialPackets;
    int slotPackets;

    @Override public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> items,
            ItemStack cursor, int[] data) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundContainerSetContentPacket(menu.containerId, menu.incrementStateId(), items, cursor).write(buffer);
            var decoded = new ClientboundContainerSetContentPacket(buffer);
            slots.clear();
            slots.addAll(decoded.getItems());
            slots.forEach(MenuPacketProbe::checkLegal);
            carried = decoded.getCarriedItem();
            checkLegal(carried);
            initialPackets++;
        } finally { buffer.release(); }
    }

    @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack item) {
        var decoded = roundTrip(menu.containerId, menu.incrementStateId(), slot, item);
        slots.set(decoded.getSlot(), decoded.getItem());
        slotPackets++;
    }

    @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack item) {
        carried = roundTrip(-1, menu.incrementStateId(), -1, item).getItem();
    }

    @Override public void sendDataChange(AbstractContainerMenu menu, int index, int value) {}

    private static ClientboundContainerSetSlotPacket roundTrip(int container, int state, int slot, ItemStack item) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundContainerSetSlotPacket(container, state, slot, item).write(buffer);
            var decoded = new ClientboundContainerSetSlotPacket(buffer);
            checkLegal(decoded.getItem());
            return decoded;
        } finally { buffer.release(); }
    }

    private static void checkLegal(ItemStack item) {
        if (!item.isEmpty() && (item.getCount() < 1 || item.getCount() > item.getMaxStackSize()))
            throw new IllegalStateException("Menu packet carried an oversized ItemStack");
    }
}
