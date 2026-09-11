package moakiee.ae2oc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import appeng.api.config.Actionable;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.AEBaseBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Three independent dedicated-server processes, using vanilla region files for all restoration. */
@Mod.EventBusSubscriber(modid = "ae2_overclocked")
public final class RestartVerification {
    private static final String PHASE = System.getProperty("ae2oc.restartPhase", "");
    private static final File EXPECTED = new File("restart-expected.nbt");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ae2_overclocked/restart");
    private static final List<Fixture> FIXTURES = new ArrayList<>();
    private static int ticks;
    private static int stableTicks;
    private static boolean stopping;

    private record Spec(String machine, List<String> inputs, int inputCount, String output, int outputCount,
                        double energy, String recipe) {}
    private static final List<Spec> SPECS = List.of(
            new Spec("crystal_pulverizer", List.of("minecraft:flint"), 2, "minecraft:gunpowder", 2,
                    16000, "ae2cs:pulverizer/gunpowder"),
            new Spec("crystal_aggregator", List.of("ae2:printed_logic_processor", "minecraft:redstone", "ae2:printed_silicon"),
                    64, "ae2:logic_processor", 64, 102400, "ae2cs:aggregator/logic_processor"),
            new Spec("circuit_etcher", List.of("minecraft:gold_block", "minecraft:redstone_block", "ae2cs:silicon_block"),
                    8, "ae2:logic_processor", 72, 28800, "ae2cs:circuit_etcher/logic_processor"),
            new Spec("entropy_variation_reaction_chamber", List.of("minecraft:cobblestone"), 2, "minecraft:stone", 2,
                    3200, "ae2:entropy/heat/cobblestone_stone"));

    private record Fixture(Spec spec, AEBaseBlockEntity host, List<LocalResourceSlot> inputs,
                           List<LocalResourceSlot> outputs) {
        double energy() { return ((IAEPowerStorage) host).getAECurrentPower(); }
        CompoundTag batchTag() { return host.saveWithFullMetadata().getCompound("ae2ocProcessing"); }
        ProcessingState<AEKey> batch() {
            var tag = batchTag();
            return tag.isEmpty() ? null : ProcessingCodec.read(tag);
        }
        long output() {
            long total = 0;
            for (var slot : outputs) {
                var value = slot.read();
                if (value != null) {
                    require(value.key().equals(item(spec.output())), spec.machine() + ": unexpected output key");
                    total += value.amount();
                }
            }
            return total;
        }
    }

    private static AEItemKey item(String id) {
        var name = ResourceLocation.parse(id);
        require(ForgeRegistries.ITEMS.containsKey(name), "Missing fixture item: " + id);
        return AEItemKey.of(ForgeRegistries.ITEMS.getValue(name));
    }

    private static Fixture bind(Spec spec, AEBaseBlockEntity host) {
        if (host instanceof CrystalPulverizerBlockEntity machine)
            return new Fixture(spec, host, ManagedItemStorages.slots(machine.getInputInv()), ManagedItemStorages.slots(machine.getOutputInv()));
        if (host instanceof CrystalAggregatorBlockEntity machine)
            return new Fixture(spec, host, ManagedItemStorages.slots(machine.getInputInv()), ManagedItemStorages.slots(machine.getOutputInv()));
        if (host instanceof CircuitEtcherBlockEntity machine)
            return new Fixture(spec, host, ManagedItemStorages.slots(machine.getInputInv()), ManagedItemStorages.slots(machine.getOutputInv()));
        var machine = (EntropyVariationReactionChamberBlockEntity) host;
        var outputs = new ArrayList<LocalResourceSlot>();
        for (int i = 0; i < machine.getOutputInv().size(); i++) outputs.add(LocalResourceSlot.generic(machine.getOutputInv(), i));
        return new Fixture(spec, host, List.of(LocalResourceSlot.generic(machine.getInputInv(), 0)), outputs);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (PHASE.isEmpty()) return;
        try {
            require(List.of("prepare", "resume", "verify").contains(PHASE), "Unknown restart phase");
            var expected = PHASE.equals("prepare") ? new CompoundTag() : NbtIo.readCompressed(EXPECTED);
            if (PHASE.equals("prepare")) require(!EXPECTED.exists(), "Refusing to reuse an existing restart fixture");
            else require(expected.getLong("pid") != ProcessHandle.current().pid(), "Restart reused the original JVM");
            ServerLevel level = event.getServer().overworld();
            // Far outside the spawn ticket; these four machines have no external energy source.
            level.setChunkForced(100, 100, true);
            for (int i = 0; i < SPECS.size(); i++) {
                var spec = SPECS.get(i);
                var pos = new BlockPos(1601 + i * 3, 128, 1601);
                level.getChunkAt(pos);
                if (PHASE.equals("prepare")) {
                    require(level.getBlockState(pos).isAir(), "Fixture position is not empty");
                    var id = ResourceLocation.parse("ae2cs:" + spec.machine());
                    require(ForgeRegistries.BLOCKS.containsKey(id), "Missing fixture machine: " + id);
                    level.setBlockAndUpdate(pos, ForgeRegistries.BLOCKS.getValue(id).defaultBlockState());
                }
                require(level.getBlockEntity(pos) instanceof AEBaseBlockEntity, "Machine missing from region file: " + spec.machine());
                var fixture = bind(spec, (AEBaseBlockEntity) level.getBlockEntity(pos));
                FIXTURES.add(fixture);
                if (PHASE.equals("prepare")) continue;
                var saved = expected.getCompound(spec.machine());
                require(fixture.batchTag().equals(saved.getCompound("batch")), "Restart changed batch NBT: " + spec.machine());
                require(Math.abs(fixture.energy() - saved.getDouble("energy")) < 0.001, "Restart changed energy: " + spec.machine());
                if (PHASE.equals("resume")) {
                    require(fixture.batch() != null && !fixture.batch().finished() && fixture.batch().energyPaid() > 0,
                            "Expected paid unfinished batch on restart: " + spec.machine());
                    require(fixture.output() == 0, "Premature output on restart");
                } else require(complete(fixture), "Completed state did not survive second restart: " + spec.machine());
                conserve(fixture);
            }
            LOG.info("Restart verification loaded phase={} pid={}", PHASE, ProcessHandle.current().pid());
        } catch (Exception failure) { fail(event.getServer(), failure); }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (PHASE.isEmpty() || stopping || event.phase != TickEvent.Phase.END) return;
        try {
            require(++ticks < 800, "Restart phase timed out: " + PHASE);
            if (PHASE.equals("prepare")) {
                if (ticks == 40) {
                    for (var fixture : FIXTURES) {
                        var upgrades = ((IUpgradeableObject) fixture.host()).getUpgrades();
                        upgrades.setItemDirect(0, new ItemStack(ModItems.PARALLEL_CARD.get()));
                        upgrades.setItemDirect(1, new ItemStack(ModItems.SUPER_ENERGY_CARD.get()));
                        upgrades.setItemDirect(2, new ItemStack(ModItems.CAPACITY_CARD.get()));
                        for (int i = 0; i < fixture.spec().inputs().size(); i++)
                            fixture.inputs().get(i).write(new ResourceAmount<>(item(fixture.spec().inputs().get(i)), fixture.spec().inputCount()));
                        ((IAEPowerStorage) fixture.host()).injectAEPower(fixture.spec().energy() + 1000, Actionable.MODULATE);
                        require(Math.abs(fixture.energy() - fixture.spec().energy() - 1000) < 0.001, "Fixture charging failed");
                    }
                }
                if (ticks <= 40) return;
                for (var fixture : FIXTURES) conserve(fixture);
                if (FIXTURES.stream().allMatch(f -> f.batch() != null && f.batch().energyPaid() > 0)) {
                    for (var fixture : FIXTURES) {
                        require(!fixture.batch().finished() && fixture.output() == 0, "Missed unfinished batch boundary");
                        for (var slot : fixture.inputs()) require(amount(slot) == 0, "Inputs not fully reserved");
                    }
                    finish(event.getServer());
                }
            } else {
                for (var fixture : FIXTURES) conserve(fixture);
                if (FIXTURES.stream().allMatch(RestartVerification::complete)) {
                    if (++stableTicks == 20) finish(event.getServer());
                } else {
                    require(PHASE.equals("resume"), "Completed machine changed after restart");
                    stableTicks = 0;
                }
            }
        } catch (Exception failure) { fail(event.getServer(), failure); }
    }

    private static long amount(LocalResourceSlot slot) { return slot.read() == null ? 0 : slot.read().amount(); }

    private static void conserve(Fixture fixture) {
        var spec = fixture.spec();
        var batch = fixture.batch();
        if (batch != null) require(batch.recipe().equals(spec.recipe()), "Wrong restored recipe");
        long pending = batch == null || !batch.finished() ? 0 : batch.outputs().stream().mapToLong(ResourceAmount::amount).sum();
        long produced = fixture.output() + pending;
        for (int i = 0; i < spec.inputs().size(); i++) {
            var key = item(spec.inputs().get(i));
            long reserved = batch == null || batch.finished() ? 0 : batch.inputs().stream()
                    .filter(value -> value.key().equals(key)).mapToLong(ResourceAmount::amount).sum();
            var local = fixture.inputs().get(i).read();
            require(local == null || local.key().equals(key), "Wrong restored input key");
            long consumed = spec.inputCount() - amount(fixture.inputs().get(i)) - reserved;
            require(consumed * spec.outputCount() == produced * spec.inputCount(), "Restart resource conservation: " + spec.machine());
        }
        double paid = batch == null ? (double) produced / spec.outputCount() * spec.energy() : batch.energyPaid();
        require(Math.abs(fixture.energy() + paid - spec.energy() - 1000) < 0.001, "Restart payment conservation: " + spec.machine());
    }

    private static boolean complete(Fixture fixture) {
        return fixture.batch() == null && fixture.output() == fixture.spec().outputCount()
                && fixture.inputs().stream().allMatch(slot -> amount(slot) == 0)
                && Math.abs(fixture.energy() - 1000) < 0.001;
    }

    private static void finish(MinecraftServer server) throws java.io.IOException {
        var expected = new CompoundTag();
        expected.putLong("pid", ProcessHandle.current().pid());
        for (var fixture : FIXTURES) {
            var saved = new CompoundTag();
            saved.put("batch", fixture.batchTag());
            saved.putDouble("energy", fixture.energy());
            expected.put(fixture.spec().machine(), saved);
            LOG.info("Restart boundary phase={} machine={} energy={} output={} batch={}", PHASE,
                    fixture.spec().machine(), fixture.energy(), fixture.output(), fixture.batchTag());
        }
        // Expectations are comparison data only: never passed to host.load or used to recreate resources.
        NbtIo.writeCompressed(expected, EXPECTED);
        LOG.info("Restart verification passed: {} (4 machines)", PHASE);
        stopping = true;
        server.halt(false); // Normal dedicated-server shutdown saves level and region data.
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void fail(MinecraftServer server, Exception failure) {
        LOG.error("Restart verification FAILED: " + PHASE, failure);
        stopping = true;
        server.halt(false); // The runner requires a success marker as well as a clean process exit.
    }
}
