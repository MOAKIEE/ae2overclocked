package moakiee;

import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEBlocks;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * 将所有升级卡注册到对应机器的升级槽。
 * 在 FMLCommonSetupEvent 中调用，确保所有 mod 已完成各自注册。
 * 可选 mod（ExtendedAE / AdvancedAE）使用 ModList 动态判断是否注册，
 * 这样 Tooltip 中显示的支持机器列表会根据已安装的 mod 自动变化。
 */
public class ModUpgrades {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 每台机器每种卡只允许插入 1 张
    private static final int MAX_CARDS = 1;

    public static void register(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {

            // ── AE2 压印器（必选）────────────────────────────────────
            registerForMachine(AEBlocks.INSCRIBER);
            registerForMachine(AEBlocks.PATTERN_PROVIDER);

            // ── ExtendedAE（可选）─────────────────────
            if (ModList.get().isLoaded("expatternprovider")) {
                registerForMachineId("expatternprovider:ex_inscriber");
                registerForMachineId("expatternprovider:circuit_cutter");
                registerForMachineId("expatternprovider:ex_pattern_provider");
            }

            // ── AdvancedAE（可选）─────────────────────
            if (ModList.get().isLoaded("advanced_ae") || ModList.get().isLoaded("advancedae")) {
                registerForMachineId("advanced_ae:reaction_chamber");
                registerForMachineId("advancedae:reaction_chamber");
                registerForMachineId("advanced_ae:adv_pattern_provider");
                registerForMachineId("advancedae:adv_pattern_provider");
                registerForMachineId("advanced_ae:small_adv_pattern_provider");
                registerForMachineId("advancedae:small_adv_pattern_provider");
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
            LOGGER.info("[AE2OC] Skip upgrade registration, machine not found: {}", id);
            return;
        }

        registerForMachine(block);
        LOGGER.info("[AE2OC] Registered upgrades for machine: {}", id);
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
        Upgrades.add(ModItems.ENERGY_CARD.get(),     machine, MAX_CARDS);
        // 超速卡（限 1 张）
        Upgrades.add(ModItems.OVERCLOCK_CARD.get(),  machine, MAX_CARDS);
    }
}
