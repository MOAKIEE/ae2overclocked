/*
 * This file includes code adapted from MakeAE2Better by QiuYe.
 * Licensed under the MIT License.
 * Original Source: https://github.com/qiuye2024github/MaekAE2Better
 * Full license text: src/main/resources/LICENSE_MakeAE2Better.txt
 */
package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.upgrades.IUpgradeableObject;
import appeng.parts.automation.IOBusPart;
import xyz.moakiee.ae2_overclocked.ModItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IOBusPart.class)
public abstract class MixinIOBusPartSuperSpeed implements IUpgradeableObject {

    @Inject(method = "getOperationsPerTick", at = @At("RETURN"), cancellable = true, remap = false)
    private void ae2oc_boostBySuperSpeedCard(CallbackInfoReturnable<Integer> cir) {
        int superSpeedUpgrades = getInstalledUpgrades(ModItems.SUPER_SPEED_CARD.get());
        if (superSpeedUpgrades <= 0) {
            return;
        }

        int result = xyz.moakiee.ae2_overclocked.support.SuperSpeedNumberUtil.convertLongToIntSaturating(cir.getReturnValue());
        cir.setReturnValue(result);
    }
}
