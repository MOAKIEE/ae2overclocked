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
            org.apache.logging.log4j.LogManager.getLogger("ae2_overclocked/clientSmoke")
                    .info("Client smoke passed: client setup and fluid widget transformation");
            Minecraft.getInstance().stop();
        });
    }
}
