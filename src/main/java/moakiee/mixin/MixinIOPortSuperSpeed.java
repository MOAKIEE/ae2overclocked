/*
 * Includes work adapted from MakeAE2Better by QiuYe, MIT.
 * See src/main/resources/LICENSE_MakeAE2Better.txt.
 */
package moakiee.mixin;

import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.storage.IOPortBlockEntity;
import moakiee.ModItems;
import moakiee.support.SuperSpeedNumberUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = IOPortBlockEntity.class, remap = false)
public abstract class MixinIOPortSuperSpeed implements IUpgradeableObject {
    /** Scale the completed upstream speed calculation once, before the cell loop. */
    @ModifyVariable(method = "tickingRequest(Lappeng/api/networking/IGridNode;I)Lappeng/api/networking/ticking/TickRateModulation;",
            at = @At(value = "INVOKE", target = "Lappeng/api/networking/IManagedGridNode;getGrid()Lappeng/api/networking/IGrid;"),
            ordinal = 0, require = 1, remap = false)
    private long ae2oc_transferBudget(long upstreamAmount) {
        if (getUpgrades().getInstalledUpgrades(ModItems.SUPER_SPEED_CARD.get()) == 0) return upstreamAmount;
        return Math.min(SuperSpeedNumberUtil.boostLongSaturating(upstreamAmount), 1_048_576L);
    }
}
