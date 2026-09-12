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
            RenderCheck.ready = true;
        });
    }

    @Mod.EventBusSubscriber(modid = "ae2_overclocked", value = Dist.CLIENT)
    public static final class RenderCheck {
        private static boolean ready;
        @SubscribeEvent
        public static void render(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
            var minecraft = Minecraft.getInstance();
            if (Boolean.getBoolean("ae2oc.clientInteractionSmoke")) return;
            if (!ready || minecraft.getOverlay() != null) return;
            ready = false;
            var gui = event.getGuiGraphics();
            gui.fill(8, 8, 264, 120, 0xffdddddd);
            // Standalone widget smoke only: this does not exercise a reaction chamber screen or its capacity card.
            if (ModList.get().isLoaded("advanced_ae")) {
                // Upstream maxLevel and tooltip use buckets; FluidStack uses mB.
                var widget = new FluidTankSlot(null, 0, 208, 24, 16, 58, 16);
                for (int amount : new int[]{16000, 8000}) {
                    widget.setFluidStack(new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, amount));
                    if (widget.getTooltip() == null) throw new IllegalStateException("Fluid widget has no tooltip");
                    var text = new StringBuilder();
                    for (var line : widget.getTooltip().toCharSequence(minecraft))
                        line.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
                    var numbers = java.util.regex.Pattern.compile("\\d+").matcher(text).results()
                            .map(java.util.regex.MatchResult::group).toList();
                    if (!numbers.equals(java.util.List.of(Integer.toString(amount / 1000), "16")))
                        throw new IllegalStateException("Fluid widget tooltip lost amount: " + text);
                    widget.render(gui, 0, 0, 0);
                    widget.setX(232);
                }
            }
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

            // Local presentation smoke; real menu networking is covered separately and still needs a connected client.
            var largeDiamond = appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.DIAMOND);
            var wrappedLarge = appeng.api.stacks.GenericStack.wrapInItemStack(largeDiamond, 1000000);
            var unwrapped = appeng.api.stacks.GenericStack.unwrapItemStack(wrappedLarge);
            if (unwrapped == null || unwrapped.amount() != 1000000 || !unwrapped.what().equals(largeDiamond)) {
                throw new IllegalStateException("GenericStack client projection corrupted large stack");
            }
            gui.renderItem(wrappedLarge, 24, 88);

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
