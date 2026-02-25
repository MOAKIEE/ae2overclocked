package moakiee;

import com.mojang.logging.LogUtils;
import moakiee.support.MachineBreakProtection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Ae2Overclocked.MODID)
public class Ae2Overclocked {

    public static final String MODID = "ae2_overclocked";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Ae2Overclocked(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 注册通用配置文件：ae2_overclocked-common.toml
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Ae2OcConfig.SPEC);

        // 注册所有物品
        ModItems.ITEMS.register(modEventBus);
        // 注册创意标签页
        ModItems.TABS.register(modEventBus);

        // 监听通用初始化事件，用于注册 Upgrades 绑定
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

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
