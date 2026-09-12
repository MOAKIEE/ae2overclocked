package moakiee.ae2oc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.misc.InscriberBlockEntity;
import io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity;
import moakiee.ModItems;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.LocalResourceSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import appeng.core.definitions.AEItems;
import moakiee.ae2oc.migration.MigrationInspection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Validates real Anvil world migration from legacy 1.2.3-fix3 persistence formats:
 * Phase 1: prepare - places test machines and cleanly saves initial chunk.
 * Standalone: inject_legacy (main) - rewrites region MCA block entities to pure legacy NBT.
 * Phase 2: migrate_and_process - boots new server, asserts deserialization & ledger, advances processing, re-saves.
 * Phase 3: verify_modern - inspects raw MCA for standardized ae2ocLongSlots schema and verifies second load ledger.
 */
@Mod.EventBusSubscriber(modid = "ae2_overclocked")
public final class WorldMigrationVerification {
    private static final String PHASE = System.getProperty("ae2oc.migrationPhase", "");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ae2_overclocked/migration");

    public static final BlockPos POS_INSCRIBER_A = new BlockPos(1601, 128, 1601);
    public static final BlockPos POS_INSCRIBER_B = new BlockPos(1604, 128, 1601);
    public static final BlockPos POS_EX_INSCRIBER = new BlockPos(1607, 128, 1601);
    public static final BlockPos POS_PULVERIZER = new BlockPos(1610, 128, 1601);

    private static final File EXPECTED_MIGRATION = new File("migration-expected.nbt");
    private static final File EXPECTED_POST_PROCESS = new File("migration-post-process.nbt");

    private static int ticks;
    private static boolean stopping;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (PHASE.isEmpty()) return;
        try {
            ServerLevel level = event.getServer().overworld();
            level.setChunkForced(100, 100, true);
            LOG.info("World migration verification loaded phase={} pid={}", PHASE, ProcessHandle.current().pid());

            if (PHASE.equals("prepare")) {
                setupWorld(level);
                LOG.info("Preparation complete; halting server to flush chunks...");
                finish(event.getServer());
            } else if (PHASE.equals("migrate_and_process")) {
                verifyMigratedLedger(level);
            } else if (PHASE.equals("verify_modern")) {
                verifyModernLoadedState(level);
            }
        } catch (Exception failure) {
            fail(event.getServer(), failure);
        }
    }

    private static void setupWorld(ServerLevel level) {
        // Place Inscriber A (ListTag format legacy target)
        placeBlock(level, POS_INSCRIBER_A, "ae2:inscriber");
        placeBlock(level, POS_INSCRIBER_A.west(), "ae2:creative_energy_cell");

        // Place Inscriber B (CompoundTag inv {item0..} legacy target)
        placeBlock(level, POS_INSCRIBER_B, "ae2:inscriber");
        placeBlock(level, POS_INSCRIBER_B.west(), "ae2:creative_energy_cell");

        if (hasBlock("expatternprovider:ex_inscriber")) {
            placeBlock(level, POS_EX_INSCRIBER, "expatternprovider:ex_inscriber");
            placeBlock(level, POS_EX_INSCRIBER.west(), "ae2:creative_energy_cell");
        }
        if (hasBlock("ae2cs:crystal_pulverizer")) {
            placeBlock(level, POS_PULVERIZER, "ae2cs:crystal_pulverizer");
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (PHASE.isEmpty() || stopping || event.phase != TickEvent.Phase.END) return;
        try {
            require(++ticks < 500, "Migration phase timed out: " + PHASE);

            if (PHASE.equals("migrate_and_process")) {
                if (hasBlock("ae2cs:crystal_pulverizer")) {
                    chargeMachine(event.getServer().overworld(), POS_PULVERIZER, 50000);
                }
                if (ticks >= 40) {
                    var beA = (InscriberBlockEntity) event.getServer().overworld().getBlockEntity(POS_INSCRIBER_A);
                    var slotsA = ManagedItemStorages.slots(beA.getInternalInventory());
                    long printedSiliconProduced = slotsA.get(3).read() == null ? 0 : slotsA.get(3).read().amount();
                    boolean pulverizerProduced = !hasBlock("ae2cs:crystal_pulverizer")
                            || slotResourceAmount(ManagedItemStorages.slots(((CrystalPulverizerBlockEntity) event
                                    .getServer().overworld().getBlockEntity(POS_PULVERIZER)).getOutputInv()),
                                    AEItemKey.of(Items.GUNPOWDER)) > 0;
                    if (printedSiliconProduced > 0 && pulverizerProduced || ticks >= 100) {
                        assertProcessingSuccess(event.getServer().overworld());
                        finish(event.getServer());
                    }
                }
            } else if (PHASE.equals("verify_modern")) {
                if (ticks >= 20) {
                    LOG.info("Migration verification PASSED across all phases!");
                    finish(event.getServer());
                }
            }
        } catch (Exception failure) {
            fail(event.getServer(), failure);
        }
    }

    private static void chargeMachine(ServerLevel level, BlockPos pos, double power) {
        var be = level.getBlockEntity(pos);
        if (be instanceof IAEPowerStorage storage) {
            storage.injectAEPower(power, Actionable.MODULATE);
        }
    }

    private static void verifyMigratedLedger(ServerLevel level) throws Exception {
        require(EXPECTED_MIGRATION.exists(), "Missing migration-expected.nbt");
        var expected = NbtIo.readCompressed(EXPECTED_MIGRATION);

        // Machine A: Inscriber with ListTag
        var beA = level.getBlockEntity(POS_INSCRIBER_A);
        require(beA instanceof InscriberBlockEntity, "Machine A missing");
        var inspectA = MigrationInspection.inspect(beA.saveWithoutMetadata());
        require(inspectA.unknownDataVersions() == 0, "Machine A had unknown data versions");
        require(inspectA.hasAe2OcData(), "Machine A missing ae2oc data");

        var invA = ((InscriberBlockEntity) beA).getInternalInventory();
        var slotsA = ManagedItemStorages.slots(invA);
        require(slotsA.get(0).read() != null && slotsA.get(0).read().amount() == 500_000,
                "Machine A slot 0 (silicon press) wrong amount: " + slotsA.get(0).read());
        require(slotsA.get(0).read().key() instanceof AEItemKey itemKey &&
                itemKey.toStack().getHoverName().getString().contains("Special Legacy Silicon Press"),
                "Machine A slot 0 lost custom NBT name");
        require(slotsA.get(2).read() != null && slotsA.get(2).read().amount() == 1_000_000,
                "Machine A slot 2 (silicon) wrong amount: " + slotsA.get(2).read());
        LOG.info("Machine A (ListTag format) successfully migrated with exact counts and custom NBT preserved!");

        // Machine B: Inscriber with CompoundTag
        var beB = level.getBlockEntity(POS_INSCRIBER_B);
        require(beB instanceof InscriberBlockEntity, "Machine B missing");
        var invB = ((InscriberBlockEntity) beB).getInternalInventory();
        var slotsB = ManagedItemStorages.slots(invB);
        require(slotsB.get(0).read() != null && slotsB.get(0).read().amount() == 333_333,
                "Machine B slot 0 wrong amount: " + slotsB.get(0).read());
        require(slotsB.get(2).read() != null && slotsB.get(2).read().amount() == 888_888,
                "Machine B slot 2 wrong amount: " + slotsB.get(2).read());
        LOG.info("Machine B (CompoundTag format) successfully migrated with exact counts!");

        // Extended Inscriber (if present)
        if (hasBlock("expatternprovider:ex_inscriber")) {
            var beEx = level.getBlockEntity(POS_EX_INSCRIBER);
            require(beEx instanceof AEBaseBlockEntity, "ExInscriber missing");
            var inspectEx = MigrationInspection.inspect(beEx.saveWithoutMetadata());
            require(inspectEx.unknownDataVersions() == 0, "ExInscriber had unknown data versions");
            var invEx = ((appeng.blockentity.AEBaseInvBlockEntity) beEx).getInternalInventory();
            var slotsEx = ManagedItemStorages.slots(invEx);
            require(slotsEx.get(2).read() != null && slotsEx.get(2).read().amount() == 1_000_000,
                    "ExInscriber lane 0 silicon wrong amount");
            require(slotsEx.get(6).read() != null && slotsEx.get(6).read().amount() == 500_000,
                    "ExInscriber lane 1 gold wrong amount");
            LOG.info("Extended Inscriber 4-lane legacy inventory successfully migrated!");
        }

        // Pulverizer (if present)
        if (hasBlock("ae2cs:crystal_pulverizer")) {
            var bePulv = level.getBlockEntity(POS_PULVERIZER);
            require(bePulv instanceof CrystalPulverizerBlockEntity, "Pulverizer missing");
            var inspectPulv = MigrationInspection.inspect(bePulv.saveWithoutMetadata());
            require(inspectPulv.unknownDataVersions() == 0, "Pulverizer had unknown data versions");
            var pulverizer = (CrystalPulverizerBlockEntity) bePulv;
            var input = ManagedItemStorages.slots(pulverizer.getInputInv()).get(0).read();
            require(input != null && input.key().equals(AEItemKey.of(Items.FLINT)) && input.amount() == 10_000,
                    "Pulverizer input did not migrate exactly: " + input);
            var fixture = expected.getCompound("pulverizer");
            require(fixture.contains("inv_input", Tag.TAG_LIST) && fixture.contains("inv_work", Tag.TAG_LIST)
                            && !fixture.contains("inv", Tag.TAG_LIST),
                    "Pulverizer fixture does not use the upstream component inventory fields");
            LOG.info("AE2CS Pulverizer component inventory migrated with exact item identity and count!");
        }

        LOG.info("Migrated ledger fully verified: 0 loss, 0 duplicate!");
    }

    private static void assertProcessingSuccess(ServerLevel level) throws Exception {
        var beA = (InscriberBlockEntity) level.getBlockEntity(POS_INSCRIBER_A);
        var invA = beA.getInternalInventory();
        var slotsA = ManagedItemStorages.slots(invA);

        // Inscriber A recipe: Silicon Press (slot 0) + Silicon (slot 2) -> Printed Silicon (slot 3)
        long siliconRemaining = slotsA.get(2).read() == null ? 0 : slotsA.get(2).read().amount();
        long printedSiliconProduced = slotsA.get(3).read() == null ? 0 : slotsA.get(3).read().amount();

        var batchTag = beA.saveWithFullMetadata().getCompound("ae2ocProcessing");
        var batch = batchTag.isEmpty() ? null : ProcessingCodec.read(batchTag);
        long reserved = batch == null || batch.finished() ? 0 : batch.inputs().stream()
                .filter(res -> res.key().equals(AEItemKey.of(AEItems.SILICON.asItem())))
                .mapToLong(ResourceAmount::amount).sum();
        long pendingOutput = batch == null || !batch.finished() ? 0 : batch.outputs().stream()
                .filter(res -> res.key().equals(AEItemKey.of(AEItems.SILICON_PRINT.asItem())))
                .mapToLong(ResourceAmount::amount).sum();

        long totalSiliconAccounted = siliconRemaining + reserved;
        long totalPrintedAccounted = printedSiliconProduced + pendingOutput;

        require(siliconRemaining < 1_000_000, "Machine A did not consume silicon");
        require(totalPrintedAccounted > 0, "Machine A did not produce printed silicon");
        require(1_000_000 - totalSiliconAccounted == totalPrintedAccounted,
                "Machine A mass conservation failure: consumed=" + (1_000_000 - totalSiliconAccounted)
                        + " (reserved=" + reserved + ") produced=" + totalPrintedAccounted
                        + " (pending=" + pendingOutput + ")");
        require(slotsA.get(0).read().amount() == 500_000, "Machine A consumed reusable press template");

        LOG.info("Live processing conservation verified on Machine A: consumed={} produced={} (in-flight batch reserved={})",
                1_000_000 - totalSiliconAccounted, totalPrintedAccounted, reserved);

        // Save post-processing ledger
        var postProcessTag = new CompoundTag();
        postProcessTag.putLong("totalSiliconAccountedA", totalSiliconAccounted);
        postProcessTag.putLong("totalPrintedAccountedA", totalPrintedAccounted);
        if (hasBlock("ae2cs:crystal_pulverizer")) {
            var pulverizer = (CrystalPulverizerBlockEntity) level.getBlockEntity(POS_PULVERIZER);
            var pulverizerBatchTag = pulverizer.saveWithFullMetadata().getCompound("ae2ocProcessing");
            var pulverizerBatch = pulverizerBatchTag.isEmpty() ? null : ProcessingCodec.read(pulverizerBatchTag);
            long flint = slotResourceAmount(ManagedItemStorages.slots(pulverizer.getInputInv()), AEItemKey.of(Items.FLINT));
            long gunpowder = slotResourceAmount(ManagedItemStorages.slots(pulverizer.getOutputInv()), AEItemKey.of(Items.GUNPOWDER));
            long reservedFlint = pulverizerBatch == null || pulverizerBatch.finished() ? 0
                    : batchResourceAmount(pulverizerBatch.inputs(), AEItemKey.of(Items.FLINT));
            long pendingGunpowder = pulverizerBatch == null || !pulverizerBatch.finished() ? 0
                    : batchResourceAmount(pulverizerBatch.outputs(), AEItemKey.of(Items.GUNPOWDER));
            long totalFlint = flint + reservedFlint;
            long totalGunpowder = gunpowder + pendingGunpowder;
            require(totalGunpowder > 0, "Pulverizer did not process its migrated input");
            require(10_000 - totalFlint == totalGunpowder,
                    "Pulverizer migration/process conservation failure: flint=" + totalFlint
                            + " gunpowder=" + totalGunpowder);
            postProcessTag.putLong("totalFlintPulverizer", totalFlint);
            postProcessTag.putLong("totalGunpowderPulverizer", totalGunpowder);
        }
        NbtIo.writeCompressed(postProcessTag, EXPECTED_POST_PROCESS);
    }

    private static void verifyModernLoadedState(ServerLevel level) throws Exception {
        require(EXPECTED_POST_PROCESS.exists(), "Missing migration-post-process.nbt");
        var expected = NbtIo.readCompressed(EXPECTED_POST_PROCESS);

        var beA = (InscriberBlockEntity) level.getBlockEntity(POS_INSCRIBER_A);
        var slotsA = ManagedItemStorages.slots(beA.getInternalInventory());
        long silicon = slotsA.get(2).read() == null ? 0 : slotsA.get(2).read().amount();
        long printed = slotsA.get(3).read() == null ? 0 : slotsA.get(3).read().amount();

        var batchTag = beA.saveWithFullMetadata().getCompound("ae2ocProcessing");
        var batch = batchTag.isEmpty() ? null : ProcessingCodec.read(batchTag);
        long reserved = batch == null || batch.finished() ? 0 : batch.inputs().stream()
                .filter(res -> res.key().equals(AEItemKey.of(AEItems.SILICON.asItem())))
                .mapToLong(ResourceAmount::amount).sum();
        long pendingOutput = batch == null || !batch.finished() ? 0 : batch.outputs().stream()
                .filter(res -> res.key().equals(AEItemKey.of(AEItems.SILICON_PRINT.asItem())))
                .mapToLong(ResourceAmount::amount).sum();

        long totalSiliconAccounted = silicon + reserved;
        long totalPrintedAccounted = printed + pendingOutput;

        require(totalSiliconAccounted == expected.getLong("totalSiliconAccountedA"), "Machine A silicon mismatch on restart");
        require(totalPrintedAccounted == expected.getLong("totalPrintedAccountedA"), "Machine A printed silicon mismatch on restart");

        // Inspect raw block entity tag
        var savedTag = beA.saveWithoutMetadata();
        var inspect = MigrationInspection.inspect(savedTag);
        require(inspect.currentDataVersions() > 0, "Machine A missing modern dataVersion");
        require(inspect.unknownDataVersions() == 0, "Machine A unknown dataVersion");
        require(inspect.legacyCountFields() == 0, "Machine A still has legacy count fields: " + inspect.legacyCountFields());
        require(savedTag.contains("ae2ocLongSlots"), "Machine A missing ae2ocLongSlots tag");

        LOG.info("Machine A clean modern schema confirmed: currentDataVersions={} legacyCountFields={}",
                inspect.currentDataVersions(), inspect.legacyCountFields());

        if (hasBlock("ae2cs:crystal_pulverizer")) {
            var pulverizer = (CrystalPulverizerBlockEntity) level.getBlockEntity(POS_PULVERIZER);
            var pulverizerBatchTag = pulverizer.saveWithFullMetadata().getCompound("ae2ocProcessing");
            var pulverizerBatch = pulverizerBatchTag.isEmpty() ? null : ProcessingCodec.read(pulverizerBatchTag);
            long flint = slotResourceAmount(ManagedItemStorages.slots(pulverizer.getInputInv()), AEItemKey.of(Items.FLINT));
            long gunpowder = slotResourceAmount(ManagedItemStorages.slots(pulverizer.getOutputInv()), AEItemKey.of(Items.GUNPOWDER));
            flint += pulverizerBatch == null || pulverizerBatch.finished() ? 0
                    : batchResourceAmount(pulverizerBatch.inputs(), AEItemKey.of(Items.FLINT));
            gunpowder += pulverizerBatch == null || !pulverizerBatch.finished() ? 0
                    : batchResourceAmount(pulverizerBatch.outputs(), AEItemKey.of(Items.GUNPOWDER));
            require(flint == expected.getLong("totalFlintPulverizer"), "Pulverizer flint mismatch on restart");
            require(gunpowder == expected.getLong("totalGunpowderPulverizer"), "Pulverizer gunpowder mismatch on restart");
            var pulverizerTag = pulverizer.saveWithoutMetadata();
            var pulverizerInspect = MigrationInspection.inspect(pulverizerTag);
            require(pulverizerInspect.currentDataVersions() > 0, "Pulverizer missing modern dataVersion");
            require(pulverizerInspect.legacyCountFields() == 0, "Pulverizer retained legacy count fields");
            require(pulverizerTag.contains("inv_input", Tag.TAG_LIST)
                            && pulverizerTag.contains("inv_work", Tag.TAG_LIST)
                            && pulverizerTag.contains("inv_output", Tag.TAG_LIST),
                    "Pulverizer missing modern component inventory fields");
            LOG.info("Pulverizer clean component schema and second-load conservation confirmed");
        }
    }

    // -----------------------------------------------------------------------
    // Standalone legacy region injector (invoked between Phase 1 and Phase 2)
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Missing migration directory argument");
        File baseDir = new File(args[0]);
        File worldDir = new File(baseDir, "migration-world");
        File regionDir = new File(worldDir, "region");
        File mcaFile = new File(regionDir, "r.3.3.mca");
        if (!mcaFile.exists()) throw new IllegalStateException("Region file not found: " + mcaFile.getAbsolutePath());

        LOG.info("Injecting legacy NBT into region file: {}", mcaFile.getAbsolutePath());
        ChunkPos chunkPos = new ChunkPos(100, 100);
        CompoundTag chunkTag;

        try (var rf = new RegionFile(mcaFile.toPath(), regionDir.toPath(), true)) {
            try (var in = rf.getChunkDataInputStream(chunkPos)) {
                if (in == null) throw new IllegalStateException("Chunk 100,100 not found in region file");
                chunkTag = NbtIo.read(in);
            }
        }

        ListTag blockEntities = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        require(!blockEntities.isEmpty(), "No block entities in chunk 100,100");

        CompoundTag expectedTag = new CompoundTag();

        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag be = blockEntities.getCompound(i);
            int x = be.getInt("x");
            int y = be.getInt("y");
            int z = be.getInt("z");

            if (x == POS_INSCRIBER_A.getX() && y == POS_INSCRIBER_A.getY() && z == POS_INSCRIBER_A.getZ()) {
                injectLegacyInscriberListTag(be);
                expectedTag.put("inscriberA", be.copy());
                LOG.info("Injected Machine A legacy ListTag NBT at {}", POS_INSCRIBER_A);
            } else if (x == POS_INSCRIBER_B.getX() && y == POS_INSCRIBER_B.getY() && z == POS_INSCRIBER_B.getZ()) {
                injectLegacyInscriberCompoundTag(be);
                expectedTag.put("inscriberB", be.copy());
                LOG.info("Injected Machine B legacy CompoundTag NBT at {}", POS_INSCRIBER_B);
            } else if (x == POS_EX_INSCRIBER.getX() && y == POS_EX_INSCRIBER.getY() && z == POS_EX_INSCRIBER.getZ()) {
                injectLegacyExInscriber(be);
                expectedTag.put("exInscriber", be.copy());
                LOG.info("Injected Extended Inscriber legacy NBT at {}", POS_EX_INSCRIBER);
            } else if (x == POS_PULVERIZER.getX() && y == POS_PULVERIZER.getY() && z == POS_PULVERIZER.getZ()) {
                injectLegacyPulverizer(be);
                expectedTag.put("pulverizer", be.copy());
                LOG.info("Injected Pulverizer legacy NBT at {}", POS_PULVERIZER);
            }
        }

        // Write back modified chunk to region file
        try (var rf = new RegionFile(mcaFile.toPath(), regionDir.toPath(), true)) {
            try (var out = rf.getChunkDataOutputStream(chunkPos)) {
                NbtIo.write(chunkTag, out);
            }
        }

        // Save expected pre-migration ledger
        NbtIo.writeCompressed(expectedTag, new File(baseDir, "migration-expected.nbt"));
        LOG.info("Legacy injection completed successfully; chunk written to r.3.3.mca");
    }

    private static void injectLegacyInscriberListTag(CompoundTag be) {
        be.remove("ae2ocLongSlots");
        be.remove("ae2ocProcessing");

        // Set upgrades: 4 overclock + 1 capacity card
        var upgrades = new ListTag();
        upgrades.add(createCardStack(0, "ae2_overclocked:overclock_card", 4));
        upgrades.add(createCardStack(1, "ae2_overclocked:capacity_card", 1));
        be.put("upgrades", upgrades);

        // inv: ListTag of items
        var inv = new ListTag();

        // Slot 0 (Top): Silicon Press (500,000) with custom name and ae2ocNetCount
        var press = new CompoundTag();
        press.putInt("Slot", 0);
        press.putString("id", "ae2:silicon_press");
        press.putByte("Count", (byte) 1);
        var pressTag = new CompoundTag();
        var display = new CompoundTag();
        display.putString("Name", "{\"text\":\"Special Legacy Silicon Press\"}");
        pressTag.put("display", display);
        pressTag.putInt("ae2ocNetCount", 500_000);
        press.put("tag", pressTag);
        inv.add(press);

        // Slot 2 (Middle): Silicon (1,000,000) with ae2ocAmount
        var silicon = new CompoundTag();
        silicon.putInt("Slot", 2);
        silicon.putString("id", "ae2:silicon");
        silicon.putByte("Count", (byte) 1);
        silicon.putLong("ae2ocAmount", 1_000_000L);
        inv.add(silicon);

        be.put("inv", inv);
    }

    private static void injectLegacyInscriberCompoundTag(CompoundTag be) {
        be.remove("ae2ocLongSlots");
        be.remove("ae2ocProcessing");

        // Set upgrades
        var upgrades = new ListTag();
        upgrades.add(createCardStack(0, "ae2_overclocked:overclock_card", 4));
        upgrades.add(createCardStack(1, "ae2_overclocked:capacity_card", 1));
        be.put("upgrades", upgrades);

        // inv: CompoundTag with item0..
        var inv = new CompoundTag();

        // item0 (Top): Calculation Press (333,333) with ae2ocCount
        var press = new CompoundTag();
        press.putString("id", "ae2:calculation_processor_press");
        press.putByte("Count", (byte) 1);
        press.putInt("ae2ocCount", 333_333);
        inv.put("item0", press);

        // item2 (Middle): Certus Quartz (888,888) with ae2ocCount
        var certus = new CompoundTag();
        certus.putString("id", "ae2:certus_quartz_crystal");
        certus.putByte("Count", (byte) 64);
        certus.putInt("ae2ocCount", 888_888);
        inv.put("item2", certus);

        be.put("inv", inv);
    }

    private static void injectLegacyExInscriber(CompoundTag be) {
        be.remove("ae2ocLongSlots");
        be.remove("ae2ocProcessing");
        be.remove("ae2ocThread0");
        be.remove("ae2ocThread1");
        be.remove("ae2ocThread2");
        be.remove("ae2ocThread3");

        var inv = new CompoundTag();
        // Lane 0: Silicon Press (item0, item1), Silicon 1M (item2)
        inv.put("item0", createItemCompound("ae2:silicon_press", 1, 1));
        inv.put("item2", createItemCompound("ae2:silicon", 1, 1_000_000));

        // Lane 1: Gold 500k (item6)
        inv.put("item4", createItemCompound("ae2:logic_processor_press", 1, 1));
        inv.put("item6", createItemCompound("minecraft:gold_ingot", 64, 500_000));

        be.put("inv", inv);
    }

    private static void injectLegacyPulverizer(CompoundTag be) {
        be.remove("ae2ocLongSlots");
        be.remove("ae2ocProcessing");
        be.remove("inv");

        var inv = new ListTag();
        var item = new CompoundTag();
        item.putInt("Slot", 0);
        item.putString("id", "minecraft:flint");
        item.putByte("Count", (byte) 64);
        item.putInt("ae2ocCount", 10_000);
        inv.add(item);
        // AppEngInvComponent serializes each named port independently. INPUT and WORK alias the
        // same inventory in the pulverizer, so a valid legacy fixture must populate both fields.
        be.put("inv_input", inv.copy());
        be.put("inv_work", inv.copy());
        be.put("inv_output", new ListTag());
    }

    private static long batchResourceAmount(List<? extends ResourceAmount<AEKey>> resources, AEKey key) {
        return resources.stream().filter(resource -> resource.key().equals(key))
                .mapToLong(ResourceAmount::amount).sum();
    }

    private static long slotResourceAmount(List<LocalResourceSlot> slots, AEKey key) {
        long total = 0;
        for (var slot : slots) {
            var value = slot.read();
            if (value != null && value.key().equals(key)) total += value.amount();
        }
        return total;
    }

    private static CompoundTag createCardStack(int slot, String itemId, int count) {
        var card = new CompoundTag();
        card.putInt("Slot", slot);
        card.putString("id", itemId);
        card.putByte("Count", (byte) count);
        return card;
    }

    private static CompoundTag createItemCompound(String itemId, int vanillaCount, int ae2ocCount) {
        var tag = new CompoundTag();
        tag.putString("id", itemId);
        tag.putByte("Count", (byte) vanillaCount);
        if (ae2ocCount > vanillaCount) tag.putInt("ae2ocCount", ae2ocCount);
        return tag;
    }

    private static boolean hasBlock(String id) {
        return ForgeRegistries.BLOCKS.containsKey(ResourceLocation.parse(id));
    }

    private static void placeBlock(ServerLevel level, BlockPos pos, String blockId) {
        var id = ResourceLocation.parse(blockId);
        require(ForgeRegistries.BLOCKS.containsKey(id), "Missing block for placement: " + blockId);
        level.setBlockAndUpdate(pos, ForgeRegistries.BLOCKS.getValue(id).defaultBlockState());
    }

    private static void finish(MinecraftServer server) {
        stopping = true;
        server.halt(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void fail(MinecraftServer server, Exception failure) {
        LOG.error("Migration verification FAILED: phase=" + PHASE, failure);
        stopping = true;
        server.halt(false);
    }
}
