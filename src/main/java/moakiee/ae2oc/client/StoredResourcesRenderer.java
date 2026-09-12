package moakiee.ae2oc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import moakiee.item.StoredResourcesItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** Render the contained item, including its NBT-dependent model, tint and custom renderer. */
public final class StoredResourcesRenderer extends BlockEntityWithoutLevelRenderer {
    private StoredResourcesRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
            MultiBufferSource buffers, int light, int overlay) {
        var minecraft = Minecraft.getInstance();
        poses.pushPose();
        // ItemRenderer has already shifted the outer builtin/entity model to its corner.
        poses.translate(0.5, 0.5, 0.5);
        minecraft.getItemRenderer().renderStatic(StoredResourcesItem.displayStack(stack), context,
                light, overlay, poses, buffers, minecraft.level, 0);
        poses.popPose();
    }

    public static final class Extension implements IClientItemExtensions {
        private StoredResourcesRenderer renderer;

        @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if (renderer == null) renderer = new StoredResourcesRenderer();
            return renderer;
        }
    }
}
