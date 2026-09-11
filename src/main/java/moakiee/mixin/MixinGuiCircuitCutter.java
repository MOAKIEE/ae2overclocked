package moakiee.mixin;

import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import com.glodblock.github.extendedae.container.ContainerCircuitCutter;
import moakiee.ae2oc.compat.ae2.UpgradeProfileCache;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiCircuitCutter", remap = false)
public abstract class MixinGuiCircuitCutter extends UpgradeableScreen<ContainerCircuitCutter> {
    protected MixinGuiCircuitCutter(ContainerCircuitCutter menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
    }

    @ModifyConstant(method = {"renderTooltip", "m_280072_"}, constant = @Constant(intValue = 16000))
    private int ae2oc_displayCapacity(int original) {
        var profile = UpgradeProfileCache.of(getMenu().getHost());
        return profile.capacity() ? (int) Math.min(Integer.MAX_VALUE, profile.capacityLimit()) : original;
    }
}
