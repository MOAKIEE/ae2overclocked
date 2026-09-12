package moakiee.ae2oc;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.pedroksl.ae2addonlib.client.widgets.FluidTankSlot;

/** Development-only source set: force transformation of lazily loaded client widgets. */
@Mod.EventBusSubscriber(modid = "ae2_overclocked", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSmokeTest {
    @SubscribeEvent
    public static void load(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("advanced_ae")) {
                var widget = new FluidTankSlot(null, 0, 0, 0, 16, 58, 16);
                widget.setFluidStack(net.minecraftforge.fluids.FluidStack.EMPTY);
            }
            RenderCheck.ready = true;
        });
    }

    @Mod.EventBusSubscriber(modid = "ae2_overclocked", value = Dist.CLIENT)
    public static final class RenderCheck {
        private static boolean ready;
        @SubscribeEvent
        public static void render(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
            var minecraft = Minecraft.getInstance();
            if (!ready || minecraft.getOverlay() != null) return;
            ready = false;
            var gui = event.getGuiGraphics();
            gui.fill(8, 8, 240, 96, 0xffdddddd);
            var samples = java.util.List.of(
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST),
                    net.minecraft.world.item.alchemy.PotionUtils.setPotion(
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION),
                            net.minecraft.world.item.alchemy.Potions.HEALING));
            for (int i = 0; i < samples.size(); i++) {
                var sample = samples.get(i);
                var pack = moakiee.item.StoredResourcesItem.pack(appeng.api.stacks.AEItemKey.of(sample), 1000000);
                var display = moakiee.item.StoredResourcesItem.displayStack(pack);
                if (display.getCount() != 1 || !net.minecraft.world.item.ItemStack.isSameItemSameTags(display, sample)) {
                    throw new IllegalStateException("Stored resource icon lost item identity or NBT");
                }
                if (!minecraft.getItemRenderer().getModel(pack, null, null, 0).isCustomRenderer()) {
                    throw new IllegalStateException("Stored resource model did not enable the content renderer");
                }
                gui.renderItem(sample, 24 + i * 64, 24);
                gui.renderItem(pack, 24 + i * 64, 56);
            }
            gui.flush();
            try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                var path = java.nio.file.Path.of("../build/reports/stored-resource-icons.png");
                java.nio.file.Files.createDirectories(path.getParent());
                screenshot.writeToFile(path);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not save icon verification", e);
            }
            org.apache.logging.log4j.LogManager.getLogger("ae2_overclocked/clientSmoke")
                    .info("Client smoke passed: client setup, fluid widget and stored resource icons");
            minecraft.stop();
        }
    }
}
