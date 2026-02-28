package xyz.moakiee.ae2_overclocked;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import xyz.moakiee.ae2_overclocked.support.MachineBreakProtection;

@Mod(Ae2Overclocked.MODID)
public class Ae2Overclocked {
    public static final String MODID = "ae2_overclocked";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Ae2Overclocked(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Ae2OcConfig.SPEC);

        ModItems.ITEMS.register(modEventBus);
        ModItems.TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("AE2 Overclocked initialized.");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        ModUpgrades.register(event);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }

        if (event.getPlayer().isShiftKeyDown()) {
            return;
        }

        BlockEntity blockEntity = event.getLevel().getBlockEntity(event.getPos());
        if (!MachineBreakProtection.isProtectedMachine(blockEntity)) {
            return;
        }

        int totalItems = MachineBreakProtection.getInternalItemTotalCount(blockEntity);
        int threshold = Ae2OcConfig.getBreakProtectionItemThreshold();
        if (totalItems <= threshold) {
            return;
        }

        event.setCanceled(true);

        if (event.getPlayer() instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.ae2_overclocked.break_block_too_many_items", threshold),
                    true
            );
        }
    }
}
