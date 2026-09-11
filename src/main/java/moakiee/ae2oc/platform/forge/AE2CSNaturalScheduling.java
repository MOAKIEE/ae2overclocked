package moakiee.ae2oc.platform.forge;

import java.util.List;
import java.util.function.LongSupplier;
import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.me.helpers.IGridConnectedBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Optional-mod fixtures observed once per world tick; no direct calls to machine tickers. */
final class AE2CSNaturalScheduling {
    private AE2CSNaturalScheduling() {}

    private record Fixture(AEBaseBlockEntity host, List<LocalResourceSlot> inputs, List<AEItemKey> keys,
            int inputPerOperation, int outputPerOperation, double energyPerOperation, String recipe,
            LongSupplier drain) {}

    private static AEItemKey item(String id) {
        return AEItemKey.of(java.util.Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id))));
    }

    private static long drainItems(GameTestHelper helper, InternalInventory inventory, AEItemKey expected) {
        long total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            // The largest fixture produces 72 items, so two legal-stack pulls drain it completely.
            for (int pull = 0; pull < 2; pull++) {
                var stack = inventory.extractItem(slot, 64, false);
                helper.assertTrue(stack.isEmpty() || expected.matches(stack) && stack.getCount() <= stack.getMaxStackSize(),
                        "Unexpected or oversized natural scheduler output");
                total += stack.getCount();
            }
            helper.assertTrue(inventory.getStackInSlot(slot).isEmpty(), "Fixture output exceeded drain budget");
        }
        return total;
    }

    private static Fixture fixture(GameTestHelper helper, String machineId, BlockPos pos) {
        helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2cs:" + machineId)));
        var host = (AEBaseBlockEntity) helper.getBlockEntity(pos);
        if (host instanceof CrystalPulverizerBlockEntity machine) {
            return new Fixture(host, ManagedItemStorages.slots(machine.getInputInv()), List.of(item("minecraft:flint")),
                    1, 1, 8000, "ae2cs:pulverizer/gunpowder",
                    () -> drainItems(helper, machine.getOutputInv(), item("minecraft:gunpowder")));
        }
        if (host instanceof CrystalAggregatorBlockEntity machine) {
            return new Fixture(host, ManagedItemStorages.slots(machine.getInputInv()),
                    List.of(item("ae2:printed_logic_processor"), item("minecraft:redstone"), item("ae2:printed_silicon")),
                    32, 32, 51200, "ae2cs:aggregator/logic_processor",
                    () -> drainItems(helper, machine.getOutputInv(), item("ae2:logic_processor")));
        }
        if (host instanceof CircuitEtcherBlockEntity machine) {
            return new Fixture(host, ManagedItemStorages.slots(machine.getInputInv()),
                    List.of(item("minecraft:gold_block"), item("minecraft:redstone_block"), item("ae2cs:silicon_block")),
                    4, 36, 14400, "ae2cs:circuit_etcher/logic_processor",
                    () -> drainItems(helper, machine.getOutputInv(), item("ae2:logic_processor")));
        }
        var machine = (EntropyVariationReactionChamberBlockEntity) host;
        return new Fixture(host, List.of(LocalResourceSlot.generic(machine.getInputInv(), 0)),
                List.of(item("minecraft:cobblestone")), 1, 1, 1600, "ae2:entropy/heat/cobblestone_stone", () -> {
                    long total = 0;
                    var output = machine.getOutputInv();
                    for (int slot = 0; slot < output.size(); slot++) {
                        var value = output.getStack(slot);
                        if (value == null) continue;
                        helper.assertTrue(value.what().equals(item("minecraft:stone")), "Unexpected entropy output");
                        total += output.extract(slot, value.what(), value.amount(), Actionable.MODULATE);
                    }
                    return total;
                });
    }

    static void run(GameTestHelper helper, String machineId, boolean destroyNode) {
        var pos = new BlockPos(1, 1, 1);
        if (destroyNode) helper.setBlock(pos.west(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        var fixture = fixture(helper, machineId, pos);
        helper.runAfterDelay(40, () -> {
            var node = ((IGridConnectedBlockEntity) fixture.host()).getMainNode();
            if (destroyNode) {
                helper.assertTrue(node.isActive(), "Expected connected " + machineId);
                node.destroy();
                helper.assertTrue(node.getGrid() == null, "Destroyed node still exposes grid");
            }
            helper.assertTrue(!node.isActive(), "Expected inactive " + machineId);
            var upgrades = ((IUpgradeableObject) fixture.host()).getUpgrades();
            upgrades.setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
            upgrades.setItemDirect(1, new ItemStack(ModItems.SUPER_ENERGY_CARD.get()));
            upgrades.setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
            for (int slot = 0; slot < fixture.keys().size(); slot++) {
                fixture.inputs().get(slot).write(new ResourceAmount<AEKey>(fixture.keys().get(slot), 2L * fixture.inputPerOperation()));
            }
            if (destroyNode) charge(helper, fixture);
            else starved(helper, fixture, 60);
        });
    }

    private static long amount(LocalResourceSlot slot) {
        var value = slot.read();
        return value == null ? 0 : value.amount();
    }

    /** Wait for a naturally reserved, paid, unfinished batch before exercising its ownership boundary. */
    static void lifecycle(GameTestHelper helper, String machineId, boolean destroy) {
        var fixture = fixture(helper, machineId, new BlockPos(1, 1, 1));
        helper.runAfterDelay(40, () -> {
            var upgrades = ((IUpgradeableObject) fixture.host()).getUpgrades();
            upgrades.setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
            upgrades.setItemDirect(1, new ItemStack(ModItems.SUPER_ENERGY_CARD.get()));
            upgrades.setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
            for (int slot = 0; slot < fixture.keys().size(); slot++) {
                fixture.inputs().get(slot).write(new ResourceAmount<AEKey>(fixture.keys().get(slot), 2L * fixture.inputPerOperation()));
            }
            var energy = (IAEPowerStorage) fixture.host();
            energy.injectAEPower(2 * fixture.energyPerOperation(), Actionable.MODULATE);
            double initialEnergy = energy.getAECurrentPower();
            helper.assertTrue(initialEnergy >= 2 * fixture.energyPerOperation(), "Lifecycle fixture needs a full energy payment");
            interruptBatch(helper, fixture, initialEnergy, destroy, 0);
        });
    }

    private static void interruptBatch(GameTestHelper helper, Fixture fixture, double initialEnergy,
            boolean destroy, int elapsed) {
        var saved = fixture.host().saveWithFullMetadata();
        if (!saved.contains("ae2ocProcessing")) {
            helper.assertTrue(elapsed < 100, "Lifecycle fixture never reserved a batch");
            helper.runAfterDelay(1, () -> interruptBatch(helper, fixture, initialEnergy, destroy, elapsed + 1));
            return;
        }
        var state = ProcessingCodec.read(saved.getCompound("ae2ocProcessing"));
        helper.assertTrue(state.recipe().equals(fixture.recipe()) && !state.finished() && state.energyPaid() > 0,
                "Lifecycle interruption requires a paid, unfinished real batch");
        helper.assertTrue(fixture.drain().getAsLong() == 0, "Lifecycle interruption happened after output");
        for (var input : fixture.inputs()) helper.assertTrue(amount(input) == 0, "Expected all inputs to be reserved");
        if (destroy) {
            var pos = new BlockPos(1, 1, 1);
            helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(pos), true, helper.makeMockPlayer()),
                    "Machine destruction failed");
            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) == null, "Destroyed machine remains");
            helper.runAfterDelay(2, () -> {
                var entities = helper.getEntities(net.minecraft.world.entity.EntityType.ITEM, pos, 3);
                for (var key : fixture.keys()) {
                    helper.assertTrue(entityAmount(entities, key) == 2L * fixture.inputPerOperation(),
                            "Real destruction lost or duplicated reserved input: " + key);
                }
                for (var output : state.outputs()) {
                    helper.assertTrue(entityAmount(entities, output.key()) == 0, "Unfinished real destruction created products");
                }
                helper.succeed();
            });
            return;
        }
        fixture.host().load(saved);
        fixture.host().load(saved);
        helper.assertTrue(fixture.host().saveWithFullMetadata().getCompound("ae2ocProcessing")
                .equals(saved.getCompound("ae2ocProcessing")), "Repeated host load changed batch payment or ownership");
        var upgrades = ((IUpgradeableObject) fixture.host()).getUpgrades();
        upgrades.setItemDirect(0, ItemStack.EMPTY);
        upgrades.setItemDirect(2, ItemStack.EMPTY);
        helper.assertTrue(fixture.host().saveWithFullMetadata().getCompound("ae2ocProcessing")
                .equals(saved.getCompound("ae2ocProcessing")), "Card removal changed the held batch");
        // Retain the energy card: truncating its buffer is a separate, intentional gameplay rule.
        observe(helper, fixture, initialEnergy, 0, 0, true);
    }

    private static long entityAmount(List<net.minecraft.world.entity.item.ItemEntity> entities, AEKey key) {
        long total = 0;
        for (var entity : entities) {
            var stack = entity.getItem();
            if (key instanceof AEItemKey itemKey && itemKey.matches(stack)) total += stack.getCount();
            else if (stack.is(ModItems.STORED_RESOURCES.get()) && stack.hasTag()
                    && key.equals(AEKey.fromTagGeneric(stack.getTag().getCompound("resource")))) {
                total += stack.getTag().getLong("amount");
            }
        }
        return total;
    }

    private static void starved(GameTestHelper helper, Fixture fixture, int remaining) {
        helper.assertTrue(((IAEPowerStorage) fixture.host()).getAECurrentPower() == 0, "Unexpected energy in lone machine");
        helper.assertTrue(fixture.drain().getAsLong() == 0, "Unpowered machine produced output");
        helper.assertTrue(!fixture.host().saveWithFullMetadata().contains("ae2ocProcessing"), "Unpowered machine reserved batch");
        for (var input : fixture.inputs()) {
            helper.assertTrue(amount(input) == 2L * fixture.inputPerOperation(), "Unpowered machine consumed input");
        }
        if (remaining == 0) charge(helper, fixture);
        else helper.runAfterDelay(1, () -> starved(helper, fixture, remaining - 1));
    }

    private static void charge(GameTestHelper helper, Fixture fixture) {
        var energy = (IAEPowerStorage) fixture.host();
        energy.injectAEPower(2 * fixture.energyPerOperation(), Actionable.MODULATE);
        double initial = energy.getAECurrentPower();
        helper.assertTrue(initial >= 2 * fixture.energyPerOperation(), "Internal energy buffer too small");
        observe(helper, fixture, initial, 0, 0, false);
    }

    private static void observe(GameTestHelper helper, Fixture fixture, double initialEnergy,
            long collected, int elapsed, boolean sawBatch) {
        long produced = collected + fixture.drain().getAsLong();
        var tag = fixture.host().saveWithFullMetadata();
        var state = tag.contains("ae2ocProcessing") ? ProcessingCodec.read(tag.getCompound("ae2ocProcessing")) : null;
        if (state != null) helper.assertTrue(state.recipe().equals(fixture.recipe()), "Wrong natural scheduler recipe");
        long pending = state == null || !state.finished() ? 0 : state.outputs().stream().mapToLong(ResourceAmount::amount).sum();
        for (int slot = 0; slot < fixture.keys().size(); slot++) {
            AEKey key = fixture.keys().get(slot);
            long reserved = state == null || state.finished() ? 0 : state.inputs().stream()
                    .filter(value -> value.key().equals(key)).mapToLong(ResourceAmount::amount).sum();
            long consumed = 2L * fixture.inputPerOperation() - amount(fixture.inputs().get(slot)) - reserved;
            helper.assertTrue(consumed * fixture.outputPerOperation() == (produced + pending) * fixture.inputPerOperation(),
                    "Natural scheduler conservation failed: " + fixture.recipe() + ", slot=" + slot + ", tick=" + elapsed);
        }
        boolean observed = sawBatch || state != null;
        if (produced == 2L * fixture.outputPerOperation()) {
            helper.assertTrue(observed && state == null, "Shared processor batch was not observed or did not clear");
            double spent = initialEnergy - ((IAEPowerStorage) fixture.host()).getAECurrentPower();
            helper.assertTrue(Math.abs(spent - 2 * fixture.energyPerOperation()) < 0.001, "Unexpected energy spent: " + spent);
            helper.succeed();
            return;
        }
        helper.assertTrue(elapsed < 400, "Natural scheduler stalled: " + fixture.recipe());
        helper.runAfterDelay(1, () -> observe(helper, fixture, initialEnergy, produced, elapsed + 1, observed));
    }
}
