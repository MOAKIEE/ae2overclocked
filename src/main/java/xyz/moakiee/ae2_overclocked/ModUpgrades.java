package xyz.moakiee.ae2_overclocked;

import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import appeng.core.localization.GuiText;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

public final class ModUpgrades {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CARDS = 1;

    private ModUpgrades() {
    }

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
            if (ModList.get().isLoaded("extendedae") || ModList.get().isLoaded("expatternprovider")) {
                String eaeModId = ModList.get().isLoaded("extendedae") ? "extendedae" : "expatternprovider";
                registerForMachineId(eaeModId + ":ex_inscriber");
                registerForMachineId(eaeModId + ":circuit_cutter");
                registerForMachineId(eaeModId + ":crystal_assembler");

                // 超速卡：EAE I/O Port & EAE buses
                registerSuperSpeedForMachineId(eaeModId + ":ex_io_port");
                registerSuperSpeedForMachineId(eaeModId + ":tag_export_bus");
                registerSuperSpeedForMachineId(eaeModId + ":ex_import_bus", "group.ex_io_bus_part");
                registerSuperSpeedForMachineId(eaeModId + ":ex_export_bus", "group.ex_io_bus_part");
            }

            if (ModList.get().isLoaded("advanced_ae")) {
                registerForMachineId("advanced_ae:reaction_chamber");
            }

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

        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(Blocks.AIR);
        if (block == Blocks.AIR) {
            LOGGER.info("[AE2OC] Skip machine not found: {}", id);
            return;
        }

        registerForMachine(block);
    }

    private static void registerForMachine(ItemLike machine) {
        Upgrades.add(ModItems.PARALLEL_CARD.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_8X.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_64X.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_1024X.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.PARALLEL_CARD_MAX.get(), machine, MAX_CARDS);

        Upgrades.add(ModItems.CAPACITY_CARD.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.SUPER_ENERGY_CARD.get(), machine, MAX_CARDS);
        Upgrades.add(ModItems.OVERCLOCK_CARD.get(), machine, MAX_CARDS);
    }

    private static void registerSuperSpeedForMachineId(String id) {
        registerSuperSpeedForMachineId(id, null);
    }

    private static void registerSuperSpeedForMachineId(String id, String tooltipGroup) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            LOGGER.warn("[AE2OC] Invalid machine id: {}", id);
            return;
        }

        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(Blocks.AIR);
        if (block == Blocks.AIR) {
            LOGGER.info("[AE2OC] Skip super speed machine not found: {}", id);
            return;
        }

        if (tooltipGroup != null) {
            Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), block, 1, tooltipGroup);
        } else {
            Upgrades.add(ModItems.SUPER_SPEED_CARD.get(), block, 1);
        }
    }
}
