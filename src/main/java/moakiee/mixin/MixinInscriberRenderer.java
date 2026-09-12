package moakiee.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.client.render.tesr.InscriberTESR;
import com.mojang.blaze3d.vertex.PoseStack;
import moakiee.ae2oc.compat.ae2.InscriberVisualState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Supplies the completed result to the animation without touching AE2's settlement state. */
@Mixin(value = InscriberTESR.class, remap = false)
public abstract class MixinInscriberRenderer {
    @Redirect(method = "render(Lappeng/blockentity/misc/InscriberBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/inventories/InternalInventory;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;",
                    ordinal = 3))
    private ItemStack ae2oc_renderCompletedResult(InternalInventory inventory, int slot,
            InscriberBlockEntity blockEntity, float partialTicks, PoseStack poses,
            MultiBufferSource buffers, int combinedLight, int combinedOverlay) {
        var visual = ((InscriberVisualState) blockEntity).ae2oc$visualResult();
        return blockEntity.isSmash() && !visual.isEmpty() ? visual : inventory.getStackInSlot(slot);
    }
}
