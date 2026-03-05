package moakiee;

import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import appeng.core.localization.GuiText;
import com.glodblock.github.extendedae.common.EPPItemAndBlock;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * 将所有升级卡注册到对应机器的升级槽。
 * 可选 mod 使用 ModList 动态判断是否注册。
 */
public class ModUpgrades {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 每台机器每种卡只允许插入 1 张
    private static final int MAX_CARDS = 1;

    public static void register(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            String itemIoBusGroup = GuiText.IOBuses.getTranslationKey();

            // ── AE2 I/O Port & I/O Bus（超速卡）─────────────────────
            Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), AEBlocks.IO_PORT, 1);
            Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), AEParts.IMPORT_BUS, 1, itemIoBusGroup);
            Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), AEParts.EXPORT_BUS, 1, itemIoBusGroup);

            // ── AE2 压印器（必选）────────────────────────────────────
            registerForMachine(AEBlocks.INSCRIBER);

            // ── ExtendedAE（可选）─────────────────────
            if (ModList.get().isLoaded("expatternprovider")) {
                Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), EPPItemAndBlock.EX_IO_PORT, 1);
                Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), EPPItemAndBlock.TAG_EXPORT_BUS, 1);
                Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), EPPItemAndBlock.EX_IMPORT_BUS, 1, "group.ex_io_bus_part");
                Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), EPPItemAndBlock.EX_EXPORT_BUS, 1, "group.ex_io_bus_part");

                registerForMachineId("expatternprovider:ex_inscriber");
                registerForMachineId("expatternprovider:circuit_cutter");
            }

            // ── AdvancedAE（可选）─────────────────────
            if (ModList.get().isLoaded("advanced_ae")) {
                registerForMachineId("advanced_ae:reaction_chamber");
            }

            // ── AE2 Crystal Science（可选）─────────────────────
            if (ModList.get().isLoaded("ae2cs")) {
                registerForMachineId("ae2cs:circuit_etcher");
                registerForMachineId("ae2cs:crystal_pulverizer");
                registerForMachineId("ae2cs:crystal_aggregator");
                registerForMachineId("ae2cs:entropy_variation_reaction_chamber");
            }
        });
    }

    private static void registerForMachineId(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            LOGGER.warn("[AE2OC] Invalid machine id: {}", id);
            return;
        }

        Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            LOGGER.info("[AE2OC] Skip machine not found: {}", id);
            return;
        }

        registerForMachine(block);
    }

    private static void registerForMachine(net.minecraft.world.level.ItemLike machine) {
        // 并行卡系列（各等级限 1 张）
        Upgrades.add(ModItems.PARALLEL_CARD.get(),       machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_8X.get(),    machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_64X.get(),   machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_1024X.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_MAX.get(),   machine, MAX_CARDS);

        // 堆叠卡（限 1 张）
        Upgrades.add(ModItems.CAPACITY_CARD.get(),   machine, MAX_CARDS);
        // 超级能源卡（限 1 张）
        Upgrades.add(ModItems.SUPER_ENERGY_CARD.get(),     machine, MAX_CARDS);
        // 超频卡（限 1 张）
        Upgrades.add(ModItems.OVERCLOCK_CARD.get(),  machine, MAX_CARDS);
    }
}
