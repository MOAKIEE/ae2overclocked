package moakiee.mixin;

import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fluids.FluidStack;
import net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Changes only widgets owned by the reaction chamber; never mutates the upstream final capacity. */
@Pseudo
@Mixin(targets = "net.pedroksl.ae2addonlib.client.widgets.FluidTankSlot", remap = false)
public abstract class MixinReactionChamberFluidWidget extends AbstractWidget {
    @Shadow @Final private AbstractContainerScreen<?> screen;
    @Shadow private FluidStack content;
    @Unique private long ae2oc_lastCapacity = -1;

    protected MixinReactionChamberFluidWidget(int x, int y, int width, int height, Component title) {
        super(x, y, width, height, title);
    }
    @Unique private long ae2oc_capacity() {
        var profile = UpgradeProfileCache.of(((ReactionChamberScreen) screen).getMenu().getHost());
        return profile.capacity() ? profile.capacityLimit() : 16000;
    }
    @Unique private void ae2oc_tooltip(FluidStack fluid, long capacity) {
        Component amount = Component.literal((fluid == null ? 0 : fluid.getAmount()) + " / " + capacity + " mB");
        setTooltip(Tooltip.create(fluid == null || fluid.isEmpty() ? amount
                : fluid.getDisplayName().copy().append("\n").append(amount)));
        ae2oc_lastCapacity = capacity;
    }
    @Inject(method = "updateTooltip", at = @At("HEAD"), cancellable = true)
    private void ae2oc_tooltip(FluidStack fluid, CallbackInfo ci) {
        if (!(screen instanceof ReactionChamberScreen)) return;
        ae2oc_tooltip(fluid, ae2oc_capacity());
        ci.cancel();
    }
    @Inject(method = {"renderWidget", "m_87963_"}, at = @At("HEAD"))
    private void ae2oc_refresh(GuiGraphics graphics, int x, int y, float partialTick, CallbackInfo ci) {
        if (screen instanceof ReactionChamberScreen && ae2oc_lastCapacity != ae2oc_capacity())
            ae2oc_tooltip(content, ae2oc_capacity());
    }
    @ModifyVariable(method = {"renderWidget", "m_87963_"}, at = @At("STORE"), ordinal = 1)
    private float ae2oc_fillRatio(float original) {
        if (!(screen instanceof ReactionChamberScreen)) return original;
        return Math.max(0, Math.min(1, (float) content.getAmount() / Math.max(1, ae2oc_capacity())));
    }
}
