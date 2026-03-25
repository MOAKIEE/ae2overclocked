package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.core.localization.Tooltips;
import appeng.helpers.externalstorage.GenericStackInv;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.support.FluidDisplayHelper;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the crystal assembler's fluid tooltip rendering so that the capacity
 * is displayed in human-readable units (B / kB / MB) via {@link FluidDisplayHelper}
 * instead of raw mB numbers that can be unreasonably large when a Capacity Card
 * is installed.
 */
@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiCrystalAssembler", remap = false)
public class MixinGuiCrystalAssemblerTooltip {

    @Unique
    private static final int AE2OC_DEFAULT_TANK_MB = 16_000;

    /**
     * Intercept renderTooltip to replace the fluid amount/capacity line with
     * a formatted version using {@link FluidDisplayHelper#formatMb(long)}.
     * The original code writes raw mB values like "2147483647 mB / 2147483647 mB"
     * which is unreadable; we convert both amount and capacity to readable units.
     */
    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2oc_renderTooltipFormatted(GuiGraphics guiGraphics, int x, int y, CallbackInfo ci) {
        try {
            Object menu = ReflectionCache.invokeNoArg(this, "getMenu");
            if (menu == null) {
                return;
            }

            // Check if hovering over a tank slot
            Object carried = ReflectionCache.invokeNoArg(menu, "getCarried");
            if (carried instanceof net.minecraft.world.item.ItemStack carriedStack && !carriedStack.isEmpty()) {
                return;
            }

            Object hoveredSlotObj = ReflectionCache.getFieldValue(this, "hoveredSlot");
            if (!(hoveredSlotObj instanceof Slot hoveredSlot)) {
                return;
            }

            // Call menu.isTank(slot) to determine if this is a fluid slot
            Method isTankMethod = ReflectionCache.getMethod(menu.getClass(), "isTank", Slot.class);
            if (isTankMethod == null || !(boolean) isTankMethod.invoke(menu, hoveredSlot)) {
                return;
            }

            if (!hoveredSlot.isActive() || !hoveredSlot.hasItem()) {
                return;
            }

            // Build item tooltip
            Method getTooltipMethod = ReflectionCache.getDeclaredMethodHierarchy(this.getClass(), "getTooltipFromContainerItem",
                    net.minecraft.world.item.ItemStack.class);
            if (getTooltipMethod == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Component> baseTooltip = (List<Component>) getTooltipMethod.invoke(this, hoveredSlot.getItem());
            List<Component> itemTooltip = new ArrayList<>(baseTooltip);

            // Extract the fluid amount from the wrapped GenericStack
            GenericStack unwrapped = GenericStack.fromItemStack(hoveredSlot.getItem());
            long amountMb = unwrapped != null ? unwrapped.amount() : 0;

            // Read the actual tank capacity from the host tile entity
            long capacityMb = ae2oc_getTankCapacityMb(menu);

            // Format both amount and capacity to human-readable units
            String amountStr = FluidDisplayHelper.formatMb(amountMb);
            String capacityStr = FluidDisplayHelper.formatMb(capacityMb);
            itemTooltip.add(Component.literal(amountStr + " / " + capacityStr)
                    .withStyle(Tooltips.NORMAL_TOOLTIP_TEXT));

            // Draw the tooltip via the parent helper
            Method drawTooltipMethod = ReflectionCache.getDeclaredMethodHierarchy(this.getClass(), "drawTooltip",
                    GuiGraphics.class, int.class, int.class, List.class);
            if (drawTooltipMethod != null) {
                drawTooltipMethod.invoke(this, guiGraphics, x, y, itemTooltip);
            }

            ci.cancel();
        } catch (Exception ignored) {
            // Fall through to original renderTooltip on any error
        }
    }

    @Unique
    private long ae2oc_getTankCapacityMb(Object menu) {
        try {
            Object host = ReflectionCache.invokeNoArg(menu, "getHost");
            if (host == null) {
                return AE2OC_DEFAULT_TANK_MB;
            }

            Object tankObj = ReflectionCache.invokeNoArg(host, "getTank");
            if (tankObj instanceof GenericStackInv tankInv) {
                long capacity = tankInv.getCapacity(AEKeyType.fluids());
                if (capacity > 0) {
                    return capacity;
                }
            }
        } catch (Exception ignored) {
        }
        return AE2OC_DEFAULT_TANK_MB;
    }

}
