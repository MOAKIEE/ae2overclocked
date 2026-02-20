package moakiee.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.menu.slot.AppEngSlot;
import appeng.util.ReadableNumberConverter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(value = AEBaseScreen.class, remap = false)
public class MixinAEBaseScreen {

    @Inject(method = "renderAppEngSlot", at = @At("TAIL"))
    private void ae2oc_renderCompactCount(GuiGraphics guiGraphics, AppEngSlot slot, CallbackInfo ci) {
        var stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        int count = stack.getCount();
        if (count < 1000) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        String text = ReadableNumberConverter.format(count, 4).toLowerCase(Locale.ROOT);
        int textWidth = font.width(text);

        int x = slot.x + 17 - textWidth;
        int y = slot.y + 9;

        guiGraphics.fill(x - 1, y - 1, x + textWidth + 1, y + 8, 0xAA000000);
        guiGraphics.drawString(font, text, x, y, 0xFFFFFF, false);
    }
}
