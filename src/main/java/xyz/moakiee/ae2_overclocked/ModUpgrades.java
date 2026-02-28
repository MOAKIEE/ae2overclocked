package xyz.moakiee.ae2_overclocked;

import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEBlocks;
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
            registerForMachine(AEBlocks.INSCRIBER);

            if (ModList.get().isLoaded("expatternprovider")) {
                registerForMachineId("expatternprovider:ex_inscriber");
                registerForMachineId("expatternprovider:circuit_cutter");
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
}
