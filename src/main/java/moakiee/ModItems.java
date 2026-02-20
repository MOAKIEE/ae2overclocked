package moakiee;

import moakiee.item.CapacityCard;
import moakiee.item.EnergyCard;
import moakiee.item.OverclockCard;
import moakiee.item.ParallelCard;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Ae2Overclocked.MODID);

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Ae2Overclocked.MODID);

    // ── 堆叠卡（单一等级）──────────────────────────────────────────
    public static final RegistryObject<CapacityCard> CAPACITY_CARD =
            ITEMS.register("capacity_card", CapacityCard::new);

    // ── 超级能源卡（单一等级）─────────────────────────────────────
    public static final RegistryObject<EnergyCard> ENERGY_CARD =
            ITEMS.register("energy_card", EnergyCard::new);

    // ── 超速卡（单一等级）─────────────────────────────────────────
    public static final RegistryObject<OverclockCard> OVERCLOCK_CARD =
            ITEMS.register("overclock_card", OverclockCard::new);

    // ── 并行卡（阶梯分级）─────────────────────────────────────────
    /** 并行卡 - 基础 ×2 */
    public static final RegistryObject<ParallelCard> PARALLEL_CARD =
            ITEMS.register("parallel_card", () -> new ParallelCard(2));

    /** 并行卡 8× */
    public static final RegistryObject<ParallelCard> PARALLEL_CARD_8X =
            ITEMS.register("parallel_card_8x", () -> new ParallelCard(8));

    /** 并行卡 64× */
    public static final RegistryObject<ParallelCard> PARALLEL_CARD_64X =
            ITEMS.register("parallel_card_64x", () -> new ParallelCard(64));

    /** 并行卡 1024× */
    public static final RegistryObject<ParallelCard> PARALLEL_CARD_1024X =
            ITEMS.register("parallel_card_1024x", () -> new ParallelCard(1024));

    /** 并行卡 Max - 无上限，仅受木桶效应（材料 / 电量 / 输出空间）约束 */
    public static final RegistryObject<ParallelCard> PARALLEL_CARD_MAX =
            ITEMS.register("parallel_card_max", () -> new ParallelCard(ParallelCard.MAX_PARALLEL));

    // ── 创意模式标签页 ─────────────────────────────────────────────
    public static final RegistryObject<CreativeModeTab> CREATIVE_TAB =
            TABS.register("ae2_overclocked_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2_overclocked"))
                    .icon(() -> PARALLEL_CARD_MAX.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(PARALLEL_CARD.get());
                        output.accept(PARALLEL_CARD_8X.get());
                        output.accept(PARALLEL_CARD_64X.get());
                        output.accept(PARALLEL_CARD_1024X.get());
                        output.accept(PARALLEL_CARD_MAX.get());
                        output.accept(CAPACITY_CARD.get());
                        output.accept(ENERGY_CARD.get());
                        output.accept(OVERCLOCK_CARD.get());
                    })
                    .build());
}
