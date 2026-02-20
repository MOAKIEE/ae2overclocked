package moakiee.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.menu.slot.AppEngSlot;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(value = AEBaseScreen.class, remap = false)
public class MixinAEBaseScreen {

    // 缩放因子：0.7f = 70% 大小
    private static final float SCALE_FACTOR = 0.7f;

    @Inject(method = "renderAppEngSlot", at = @At("HEAD"), cancellable = true)
    private void ae2oc_renderCompactCount(GuiGraphics guiGraphics, AppEngSlot slot, CallbackInfo ci) {
        var stack = slot.getItem();
        if (stack.isEmpty() || slot.isHideAmount() || stack.getCount() < 1000) {
            return;
        }

        if ((slot.renderIconWithItem() || stack.isEmpty()) && slot.isSlotEnabled() && slot.getIcon() != null) {
            slot.getIcon().getBlitter()
                    .dest(slot.x, slot.y)
                    .opacity(slot.getOpacityOfIcon())
                    .blit(guiGraphics);
        }

        if (!slot.isValid()) {
            guiGraphics.fill(slot.x, slot.y, 16 + slot.x, 16 + slot.y, 0x66ff6666);
        }

        Font font = Minecraft.getInstance().font;
        // 渲染物品图标
        guiGraphics.renderItem(stack, slot.x, slot.y);

        // 使用更小的字体渲染数量
        String text = formatCompact(stack.getCount());
        renderSmallSizeLabel(guiGraphics, font, slot.x, slot.y, text);

        ci.cancel();
    }

    /**
     * 使用更小的字体渲染数量标签（参考 AE2 StackSizeRenderer）
     */
    private static void renderSmallSizeLabel(GuiGraphics guiGraphics, Font font, int x, int y, String text) {
        var pose = guiGraphics.pose();
        pose.pushPose();
        // 文字渲染在物品上方 200 层
        pose.translate(0, 0, 200);
        pose.scale(SCALE_FACTOR, SCALE_FACTOR, 1.0f);

        float inverseScale = 1.0f / SCALE_FACTOR;
        // 计算缩放后的位置（右下角对齐）
        int scaledX = (int) ((x + 16 - font.width(text) * SCALE_FACTOR - 1) * inverseScale);
        int scaledY = (int) ((y + 16 - 7 * SCALE_FACTOR - 1) * inverseScale);

        // 渲染带阴影的白色文字
        RenderSystem.disableBlend();
        var buffer = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        font.drawInBatch(text, scaledX, scaledY, 0xFFFFFF, true,
                pose.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, 15728880);
        buffer.endBatch();
        RenderSystem.enableBlend();

        pose.popPose();
    }

    private static String formatCompact(int value) {
        if (value >= 1_000_000_000) {
            return oneDecimal(value / 1_000_000_000.0) + "G";
        }
        if (value >= 1_000_000) {
            return oneDecimal(value / 1_000_000.0) + "M";
        }
        if (value >= 1_000) {
            return oneDecimal(value / 1_000.0) + "K";
        }
        return Integer.toString(value);
    }

    private static String oneDecimal(double number) {
        String text = String.format(Locale.ROOT, "%.1f", number);
        if (text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }
}
