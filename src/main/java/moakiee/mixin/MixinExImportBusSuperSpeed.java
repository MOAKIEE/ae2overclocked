package moakiee.mixin;

import appeng.api.parts.IPartItem;
import appeng.parts.automation.ImportBusPart;
import moakiee.ModItems;
import moakiee.support.SuperSpeedNumberUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.parts.PartExImportBus", remap = false)
public abstract class MixinExImportBusSuperSpeed extends ImportBusPart implements moakiee.ae2oc.compat.ae2.OwnsTransferScaling {

    public MixinExImportBusSuperSpeed(IPartItem<?> partItem) {
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
