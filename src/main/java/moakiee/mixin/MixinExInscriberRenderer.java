package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import com.mojang.blaze3d.vertex.PoseStack;
import moakiee.ae2oc.compat.extendedae.ExtendedInscriberVisualState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.render.tesr.ExInscriberTESR", remap = false)
public abstract class MixinExInscriberRenderer {
    @Redirect(method = "render(Lcom/glodblock/github/extendedae/common/tileentities/TileExInscriber;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/inventories/InternalInventory;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;",
                    ordinal = 3))
    private ItemStack ae2oc_renderCompletedResult(InternalInventory inventory, int slot,
            TileExInscriber blockEntity, float partialTicks, PoseStack poses,
            MultiBufferSource buffers, int combinedLight, int combinedOverlay) {
        var state = (ExtendedInscriberVisualState) blockEntity;
        var visual = state.ae2oc$visualResult(inventory);
        return state.ae2oc$isVisualSmash() && !visual.isEmpty() ? visual : inventory.getStackInSlot(slot);
    }
}
