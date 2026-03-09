/*
 * This file includes code adapted from MakeAE2Better by QiuYe.
 * Licensed under the MIT License.
 * Original Source: https://github.com/qiuye2024github/MaekAE2Better
 * Full license text: src/main/resources/LICENSE_MakeAE2Better.txt
 */
package moakiee.mixin;

import appeng.api.parts.IPartItem;
import appeng.parts.automation.ExportBusPart;
import moakiee.ModItems;
import moakiee.support.SuperSpeedNumberUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.parts.PartExExportBus", remap = false)
public abstract class MixinExExportBusSuperSpeed extends ExportBusPart {

    public MixinExExportBusSuperSpeed(IPartItem<?> partItem) {
        super(partItem);
    }

    /**
     * @author .
     * @reason 保留 ExtendedAE 原始基础吞吐，仅在装有超速卡时追加倍率
     */
    @Inject(method = "getOperationsPerTick", at = @At("RETURN"), cancellable = true, remap = false)
    private void ae2oc_boostBySuperSpeedCard(CallbackInfoReturnable<Integer> cir) {
        if (getInstalledUpgrades(ModItems.SUPER_SPEED_CARD.get()) <= 0) {
            return;
        }

        cir.setReturnValue(SuperSpeedNumberUtil.convertLongToIntSaturating(cir.getReturnValue()));
    }
}
