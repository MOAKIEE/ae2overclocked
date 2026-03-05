/*
 * This file includes code adapted from MakeAE2Better by QiuYe.
 * Licensed under the MIT License.
 * Original Source: https://github.com/qiuye2024github/MaekAE2Better
 * Full license text: src/main/resources/LICENSE_MakeAE2Better.txt
 */
package moakiee.mixin;

import appeng.api.parts.IPartItem;
import appeng.parts.automation.ExportBusPart;
import moakiee.support.SuperSpeedNumberUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.parts.PartExExportBus", remap = false)
public abstract class MixinExExportBusSuperSpeed extends ExportBusPart {

    public MixinExExportBusSuperSpeed(IPartItem<?> partItem) {
        super(partItem);
    }

    /**
     * @author .
     * @reason 移植 MakeAE2Better 的超速卡吞吐逻辑
     */
    @Overwrite(remap = false)
    public int getOperationsPerTick() {
        int result = super.getOperationsPerTick();
        return SuperSpeedNumberUtil.convertLongToIntSaturating(result);
    }
}
