package xyz.moakiee.ae2_overclocked.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.pedroksl.ae2addonlib.client.widgets.FluidTankSlot", remap = false)
public abstract class MixinFluidTankSlotRender {

    @Shadow
    private TextureAtlasSprite fluidTexture;

    @Shadow
    private FluidStack content;

    @Shadow
    @Final
    private int maxLevel;

    @Shadow
    private boolean disableRender;

    @Unique
    private static final int AE2OC_MB_PER_BUCKET = 1000;

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2oc_renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.content == null || this.content.isEmpty() || this.fluidTexture == null || this.disableRender) {
            return;
        }

        AbstractWidget widget = (AbstractWidget) (Object) this;
        int width = widget.getWidth();
        int height = widget.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        double ratio = ae2oc_computeFillRatio(this.content.getAmount(), this.maxLevel);
        if (ratio <= 0.0D) {
            ci.cancel();
            return;
        }

        int fillPixels = Mth.clamp((int) Math.ceil(ratio * height), 0, height);

        int tint = IClientFluidTypeExtensions.of(this.content.getFluid()).getTintColor();
        float alpha = ((tint >> 24) & 0xFF) / 255.0F;
        float red = ((tint >> 16) & 0xFF) / 255.0F;
        float green = ((tint >> 8) & 0xFF) / 255.0F;
        float blue = (tint & 0xFF) / 255.0F;

        guiGraphics.setColor(red, green, blue, alpha);

        int drawY = widget.getY() + height;
        int remaining = fillPixels;
        while (remaining > 0) {
            int drawHeight = Math.min(width, remaining);
            guiGraphics.blit(widget.getX(), drawY - drawHeight, 0, width, drawHeight, this.fluidTexture);
            drawY -= drawHeight;
            remaining -= drawHeight;
        }

        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        ci.cancel();
    }

    @Unique
    private static double ae2oc_computeFillRatio(int amountMb, int maxBuckets) {
        if (amountMb <= 0 || maxBuckets <= 0) {
            return 0.0D;
        }

        double amountBuckets = amountMb / (double) AE2OC_MB_PER_BUCKET;
        if (maxBuckets >= Integer.MAX_VALUE / 2) {
            double numerator = Math.log1p(amountBuckets);
            double denominator = Math.log1p((double) Integer.MAX_VALUE);
            return Mth.clamp(numerator / denominator, 0.0D, 1.0D);
        }

        return Mth.clamp(amountBuckets / maxBuckets, 0.0D, 1.0D);
    }
}
