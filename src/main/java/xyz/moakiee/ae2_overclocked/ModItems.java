package xyz.moakiee.ae2_overclocked;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import xyz.moakiee.ae2_overclocked.item.CapacityCard;
import xyz.moakiee.ae2_overclocked.item.EnergyCard;
import xyz.moakiee.ae2_overclocked.item.OverclockCard;
import xyz.moakiee.ae2_overclocked.item.ParallelCard;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Ae2Overclocked.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Ae2Overclocked.MODID);

    public static final DeferredItem<CapacityCard> CAPACITY_CARD =
            ITEMS.register("capacity_card", CapacityCard::new);
    public static final DeferredItem<EnergyCard> SUPER_ENERGY_CARD =
            ITEMS.register("super_energy_card", EnergyCard::new);
    public static final DeferredItem<OverclockCard> OVERCLOCK_CARD =
            ITEMS.register("overclock_card", OverclockCard::new);

    public static final DeferredItem<ParallelCard> PARALLEL_CARD =
            ITEMS.register("parallel_card", () -> new ParallelCard(2));
    public static final DeferredItem<ParallelCard> PARALLEL_CARD_8X =
            ITEMS.register("parallel_card_8x", () -> new ParallelCard(8));
    public static final DeferredItem<ParallelCard> PARALLEL_CARD_64X =
            ITEMS.register("parallel_card_64x", () -> new ParallelCard(64));
    public static final DeferredItem<ParallelCard> PARALLEL_CARD_1024X =
            ITEMS.register("parallel_card_1024x", () -> new ParallelCard(1024));
    public static final DeferredItem<ParallelCard> PARALLEL_CARD_MAX =
            ITEMS.register("parallel_card_max", () -> new ParallelCard(ParallelCard.MAX_PARALLEL));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
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
                        output.accept(SUPER_ENERGY_CARD.get());
                        output.accept(OVERCLOCK_CARD.get());
                    })
                    .build());

    private ModItems() {
    }
}
