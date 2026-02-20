package moakiee.mixin;

import com.mojang.logging.LogUtils;
import moakiee.ModItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen", remap = false)
public class MixinReactionChamberScreenCtor {

    private static final int AE2OC_DEFAULT_BUCKETS = 16;
    private static final int AE2OC_MAX_BUCKETS = Integer.MAX_VALUE / 1000;
    private static final org.slf4j.Logger AE2OC_LOGGER = LogUtils.getLogger();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_fixFluidDisplayCap(
            net.pedroksl.advanced_ae.gui.ReactionChamberMenu menu,
            net.minecraft.world.entity.player.Inventory playerInventory,
            net.minecraft.network.chat.Component title,
            appeng.client.gui.style.ScreenStyle style,
            CallbackInfo ci) {
        try {
            int buckets = AE2OC_DEFAULT_BUCKETS;

            Object host = menu.getClass().getMethod("getHost").invoke(menu);
            if (host instanceof net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity chamber) {
                int cards = chamber.getUpgrades().getInstalledUpgrades(ModItems.CAPACITY_CARD.get());
                buckets = cards > 0 ? AE2OC_MAX_BUCKETS : AE2OC_DEFAULT_BUCKETS;
                AE2OC_LOGGER.info("[AE2OC] ReactionChamber display cap set to {}b (cards={})", buckets, cards);
            }

            var inField = menu.getClass().getDeclaredField("INPUT_FLUID_SIZE");
            var outField = menu.getClass().getDeclaredField("OUTPUT_FLUID_SIZE");
            inField.setAccessible(true);
            outField.setAccessible(true);
            inField.setInt(menu, buckets);
            outField.setInt(menu, buckets);
        } catch (Throwable ignored) {
            AE2OC_LOGGER.warn("[AE2OC] ReactionChamber display cap override failed", ignored);
        }
    }
}
