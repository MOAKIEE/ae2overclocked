package moakiee.ae2oc.platform.forge;

import java.util.List;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import moakiee.Ae2Overclocked;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(Ae2Overclocked.MODID)
@PrefixGameTestTemplate(false)
public final class RefactorGameTests {
    private static final int UNLOAD_CHUNK_X = 96;
    private static final int UNLOAD_CHUNK_Z = 96;

    @GameTest(template = "empty")
    public static void parallelCardMutexRejectsSecondCardAndAllowsReplacement(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var machine = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        var upgrades = machine.getUpgrades();
        var first = new net.minecraft.world.item.ItemStack(moakiee.ModItems.PARALLEL_CARD.get());
        helper.assertTrue(upgrades.insertItem(0, first, false).isEmpty(), "First parallel card was rejected");
        var second = new net.minecraft.world.item.ItemStack(moakiee.ModItems.PARALLEL_CARD_8X.get());
        var rejected = upgrades.insertItem(1, second, false);
        helper.assertTrue(rejected.is(moakiee.ModItems.PARALLEL_CARD_8X.get()) && rejected.getCount() == 1
                && upgrades.getStackInSlot(1).isEmpty(), "Second parallel card bypassed mutual exclusion");
        helper.assertTrue(upgrades.extractItem(0, 1, false).is(moakiee.ModItems.PARALLEL_CARD.get()),
                "Existing parallel card could not be removed");
        helper.assertTrue(upgrades.insertItem(1, second, false).isEmpty()
                && upgrades.getStackInSlot(1).is(moakiee.ModItems.PARALLEL_CARD_8X.get()),
                "Replacement parallel tier remained blocked after removal");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void breakProtectionIncludesHiddenAndReservedItems(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var host = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        var ports = moakiee.ae2oc.compat.ae2.ManagedItemStorages.slots(host.getInternalInventory());
        ports.get(2).write(new ResourceAmount<AEKey>(AEItemKey.of(Items.IRON_INGOT), Integer.MAX_VALUE));
        var adapter = new moakiee.ae2oc.compat.ae2.MachineProcessorAdapter(host, host, host.getInternalInventory(), () -> null);
        var saved = new net.minecraft.nbt.CompoundTag();
        saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>("test:reserved",
                List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 1024)),
                List.of(new ResourceAmount<>(AEItemKey.of(Items.DIAMOND), 2048)), 100, 50, 4)));
        adapter.load(saved);
        helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(host) == (long) Integer.MAX_VALUE + 1024,
                "Protection missed hidden or reserved items, or overflowed int");
        ports.get(3).write(new ResourceAmount<AEKey>(AEItemKey.of(Items.DIAMOND), Long.MAX_VALUE));
        helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(host) == Long.MAX_VALUE,
                "Protection count overflowed long");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedStorageDoesNotPartiallyReplaceInventory(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var machine = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        var inventory = new appeng.util.inv.AppEngInternalInventory(null, 2);
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.attach(inventory, machine);
        var ports = moakiee.ae2oc.compat.ae2.ManagedItemStorages.slots(inventory);
        var expected = new ResourceAmount<AEKey>(AEItemKey.of(Items.IRON_INGOT), 1000000);
        ports.get(0).write(expected);
        var tag = new net.minecraft.nbt.CompoundTag();
        inventory.writeToNBT(tag, "items");
        var items = tag.getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var invalid = items.getCompound(0).copy();
        invalid.putInt("Slot", 9);
        items.add(invalid);
        boolean rejected = false;
        try { inventory.readFromNBT(tag, "items"); }
        catch (IllegalArgumentException expectedFailure) { rejected = true; }
        helper.assertTrue(rejected && expected.equals(ports.get(0).read()), "Malformed slots partially replaced live storage");
        var logical = new net.minecraft.nbt.CompoundTag();
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.save(inventory, logical, "logical");
        var entries = logical.getList("logical", net.minecraft.nbt.Tag.TAG_COMPOUND);
        entries.getCompound(0).putLong("amount", 7);
        entries.getCompound(1).put("key", AEItemKey.of(Items.GOLD_INGOT).toTagGeneric());
        entries.getCompound(1).putLong("amount", -1);
        rejected = false;
        try { moakiee.ae2oc.compat.ae2.ManagedItemStorages.load(inventory, logical, "logical"); }
        catch (IllegalArgumentException expectedFailure) { rejected = true; }
        helper.assertTrue(rejected && expected.equals(ports.get(0).read()), "Invalid quantity partially replaced live storage");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void upgradeChangesInvalidateProfileAndClampEnergy(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var machine = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        double base = machine.getInternalMaxPower();
        var first = moakiee.ae2oc.compat.ae2.UpgradeProfileCache.of(machine);
        machine.getUpgrades().setItemDirect(0, new net.minecraft.world.item.ItemStack(moakiee.ModItems.SUPER_ENERGY_CARD.get()));
        var upgraded = moakiee.ae2oc.compat.ae2.UpgradeProfileCache.of(machine);
        helper.assertTrue(!first.energy() && upgraded.energy(), "Upgrade change did not invalidate profile");
        machine.injectAEPower(base * 10, appeng.api.config.Actionable.MODULATE);
        helper.assertTrue(machine.getInternalCurrentPower() > base, "Energy card did not expand internal buffer");
        machine.getUpgrades().setItemDirect(0, net.minecraft.world.item.ItemStack.EMPTY);
        helper.assertTrue(machine.getInternalCurrentPower() == base, "Energy removal did not immediately clamp to base capacity");
        helper.assertTrue(!moakiee.ae2oc.compat.ae2.UpgradeProfileCache.of(machine).energy(), "Removed energy card remained cached");
        for (String id : List.of("ae2cs:circuit_etcher", "ae2cs:crystal_pulverizer",
                "ae2cs:crystal_aggregator", "ae2cs:entropy_variation_reaction_chamber")) {
            var key = ResourceLocation.parse(id);
            if (!ForgeRegistries.BLOCKS.containsKey(key)) continue;
            helper.setBlock(pos, ForgeRegistries.BLOCKS.getValue(key));
            var host = helper.getBlockEntity(pos);
            var upgrades = ((appeng.api.upgrades.IUpgradeableObject) host).getUpgrades();
            var energy = (appeng.api.networking.energy.IAEPowerStorage) host;
            double original = energy.getAEMaxPower();
            upgrades.setItemDirect(0, new net.minecraft.world.item.ItemStack(moakiee.ModItems.SUPER_ENERGY_CARD.get()));
            energy.injectAEPower(original * 10, appeng.api.config.Actionable.MODULATE);
            helper.assertTrue(energy.getAECurrentPower() > original, "Energy buffer did not expand: " + id);
            upgrades.setItemDirect(0, net.minecraft.world.item.ItemStack.EMPTY);
            helper.assertTrue(energy.getAECurrentPower() == original, "Energy removal did not clamp immediately: " + id);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void menuProjectionCannotEscapeToCursor(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var machine = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        var ports = moakiee.ae2oc.compat.ae2.ManagedItemStorages.slots(machine.getInternalInventory());
        ports.get(3).write(new ResourceAmount<>(AEItemKey.of(Items.IRON_INGOT), 1000000));
        var player = helper.makeMockPlayer();
        var menu = new appeng.menu.implementations.InscriberMenu(1, player.getInventory(), machine);
        int index = -1;
        for (var slot : menu.slots) if (slot instanceof moakiee.ae2oc.client.LogicalMenuSlot logical && logical.amount() == 1000000) index = slot.index;
        helper.assertTrue(index >= 0, "Large output slot was not bound to presentation proxy");
        var wrapped = appeng.api.stacks.GenericStack.unwrapItemStack(menu.getSlot(index).getItem());
        helper.assertTrue(wrapped != null && wrapped.amount() == 1000000, "Menu quantity projection is incorrect");
        menu.clicked(index, 40, net.minecraft.world.inventory.ClickType.SWAP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && ports.get(3).read().amount() == 1000000, "Offhand swap bypassed large-slot guard");
        menu.clicked(index, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().getCount() == 64 && !appeng.api.stacks.GenericStack.isWrapped(menu.getCarried()), "Cursor received presentation wrapper or oversized stack");
        helper.assertTrue(ports.get(3).read().amount() == 1000000 - 64, "Menu pickup did not conserve resources");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storedPackageCanBePickedUpAndReinserted(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var machine = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        machine.getUpgrades().setItemDirect(0, new net.minecraft.world.item.ItemStack(moakiee.ModItems.CAPACITY_CARD.get()));

        var player = helper.makeMockPlayer();
        var packed = moakiee.item.StoredResourcesItem.pack(AEItemKey.of(Items.GOLD_INGOT), 130);
        var dropped = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),
                player.getX(), player.getY(), player.getZ(), packed);
        dropped.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(dropped);
        dropped.playerTouch(player);

        int packageSlot = -1;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(moakiee.ModItems.STORED_RESOURCES.get())) packageSlot = i;
        }
        helper.assertTrue(packageSlot >= 0 && dropped.isRemoved(), "Player did not pick up the stored-resource package");
        player.getInventory().selected = packageSlot;
        for (int i = 0; i < 3; i++)
            moakiee.ModItems.STORED_RESOURCES.get().use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);

        long unpacked = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(Items.GOLD_INGOT)) unpacked += stack.getCount();
        }
        helper.assertTrue(unpacked == 130, "Package unpacking did not conserve all 130 items: " + unpacked);
        helper.assertTrue(player.getInventory().getItem(packageSlot).isEmpty(), "Empty package remained after full recovery");

        var menu = new appeng.menu.implementations.InscriberMenu(1, player.getInventory(), machine);
        for (var slot : menu.slots) {
            if (slot.container == player.getInventory() && slot.getItem().is(Items.GOLD_INGOT))
                menu.clicked(slot.index, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
        }
        long inserted = moakiee.ae2oc.compat.ae2.ManagedItemStorages.slots(machine.getInternalInventory()).stream()
                .map(moakiee.ae2oc.compat.ae2.LocalResourceSlot::read)
                .filter(java.util.Objects::nonNull)
                .filter(value -> value.key().equals(AEItemKey.of(Items.GOLD_INGOT)))
                .mapToLong(ResourceAmount::amount).sum();
        helper.assertTrue(inserted == 130, "Recovered items did not re-enter the managed machine slot: " + inserted);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void chunkUnloadReloadPreservesOwnedBatch(GameTestHelper helper) {
        var level = helper.getLevel();
        var remote = new BlockPos((UNLOAD_CHUNK_X << 4) + 8, 64, (UNLOAD_CHUNK_Z << 4) + 8);
        level.setChunkForced(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z, true);
        level.getChunk(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z);
        level.setBlock(remote, appeng.core.definitions.AEBlocks.INSCRIBER.block().defaultBlockState(), 3);
        var original = (appeng.blockentity.misc.InscriberBlockEntity) level.getBlockEntity(remote);
        helper.assertTrue(original != null, "Remote machine was not created in the forced chunk");

        var expected = new ProcessingState<AEKey>("ae2oc:test/chunk_unload",
                List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 130)),
                List.of(new ResourceAmount<>(AEItemKey.of(Items.IRON_INGOT), 260)), 1000, 125, 40);
        var saved = original.saveWithFullMetadata();
        saved.put("ae2ocProcessing", ProcessingCodec.write(expected));
        original.load(saved);
        original.setChanged();
        level.getChunkSource().save(true);

        helper.runAfterDelay(5, () -> {
            level.setChunkForced(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z, false);
            awaitChunkUnload(helper, remote, original, expected, 0);
        });
    }

    private static void awaitChunkUnload(GameTestHelper helper, BlockPos remote,
            appeng.blockentity.misc.InscriberBlockEntity original, ProcessingState<AEKey> expected, int elapsed) {
        var level = helper.getLevel();
        if (level.getChunkSource().getChunkNow(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z) == null && original.isRemoved()) {
            level.setChunkForced(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z, true);
            level.getChunk(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z);
            helper.runAfterDelay(10, () -> {
                var restored = (appeng.blockentity.misc.InscriberBlockEntity) level.getBlockEntity(remote);
                helper.assertTrue(restored != null && restored != original,
                        "Chunk reload reused the old block entity or lost the machine");
                var restoredTag = restored.saveWithFullMetadata();
                helper.assertTrue(restoredTag.contains("ae2ocProcessing"), "Chunk reload lost the owned batch");
                helper.assertTrue(expected.equals(ProcessingCodec.read(restoredTag.getCompound("ae2ocProcessing"))),
                        "Chunk reload changed reserved inputs, outputs, payment, or remaining ticks");
                helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(restored) == 130,
                        "Reloaded machine no longer reports its reserved input ownership");
                level.removeBlock(remote, false);
                level.setChunkForced(UNLOAD_CHUNK_X, UNLOAD_CHUNK_Z, false);
                helper.succeed();
            });
            return;
        }
        helper.assertTrue(elapsed < 240, "Forced-ticket removal never unloaded the remote chunk");
        helper.runAfterDelay(1, () -> awaitChunkUnload(helper, remote, original, expected, elapsed + 1));
    }

    @GameTest(template = "empty")
    public static void longItemProjectionAndSave(GameTestHelper helper) {
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, appeng.core.definitions.AEBlocks.INSCRIBER.block());
        var machine = (appeng.blockentity.misc.InscriberBlockEntity) helper.getBlockEntity(pos);
        var ports = moakiee.ae2oc.compat.ae2.ManagedItemStorages.slots(machine.getInternalInventory());
        var resource = new ResourceAmount<AEKey>(AEItemKey.of(Items.IRON_INGOT), Integer.MAX_VALUE);
        ports.get(2).write(resource);
        helper.assertTrue(machine.getInternalInventory().getStackInSlot(2).getCount() == 64, "Projection is not a legal stack");
        var saved = machine.saveWithFullMetadata();
        machine.load(saved);
        helper.assertTrue(ports.get(2).read().equals(resource), "Save/load lost logical item quantity");
        var extracted = machine.getInternalInventory().extractItem(2, Integer.MAX_VALUE, false);
        helper.assertTrue(extracted.getCount() <= 64, "Extraction produced oversized carried stack");
        helper.assertTrue(ports.get(2).read().amount() + extracted.getCount() == Integer.MAX_VALUE, "Extraction lost resources");
        var oldTag = new net.minecraft.nbt.CompoundTag();
        var legacy = new net.minecraft.world.item.ItemStack(Items.IRON_INGOT).save(new net.minecraft.nbt.CompoundTag());
        legacy.putInt("ae2ocCount", 1234567);
        oldTag.getCompound("inv");
        var invTag = new net.minecraft.nbt.CompoundTag();
        invTag.put("item2", legacy);
        oldTag.put("inv", invTag);
        moakiee.ae2oc.compat.ae2.ManagedItemStorages.load(machine.getInternalInventory(), oldTag, "ae2ocLongSlots");
        helper.assertTrue(ports.get(2).read().amount() == 1234567, "Legacy quantity migration failed");
        helper.assertTrue(moakiee.ae2oc.compat.ae2.ManagedItemStorages.projectedConsumption(
                        Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE - 1) == 1,
                "Recipe projection consumed hidden logical inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void managedFluidRemovalPreservesResources(GameTestHelper helper) {
        var ordinary = new appeng.helpers.externalstorage.GenericStackInv(null,
                appeng.helpers.externalstorage.GenericStackInv.Mode.STORAGE, 1);
        var ordinaryOwnership = (moakiee.ae2oc.compat.ae2.ManagedGenericInventory) ordinary;
        helper.assertTrue(!ordinaryOwnership.ae2oc$isManaged(), "Ordinary inventory acquired machine ownership");
        var tank = new appeng.helpers.externalstorage.GenericStackInv(null,
                appeng.helpers.externalstorage.GenericStackInv.Mode.STORAGE, 1);
        ((moakiee.ae2oc.compat.ae2.ManagedGenericInventory) tank).ae2oc$markManaged();
        var key = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        ordinary.setCapacity(key.getType(), 16000);
        ordinary.setStack(0, new appeng.api.stacks.GenericStack(key, 32000));
        helper.assertTrue(ordinary.getAmount(0) == 16000, "Ordinary inventory bypassed upstream capacity");
        helper.assertTrue(!ordinaryOwnership.ae2oc$isManaged(), "Ownership leaked between inventory instances");
        ((moakiee.ae2oc.compat.ae2.ManagedGenericInventory) tank).ae2oc$markManaged();
        tank.setCapacity(key.getType(), Long.MAX_VALUE);
        tank.setStack(0, new appeng.api.stacks.GenericStack(key, Long.MAX_VALUE));
        tank.setCapacity(key.getType(), 16000);
        helper.assertTrue(tank.getAmount(0) == Long.MAX_VALUE, "Capacity reduction destroyed fluid");
        helper.assertTrue(tank.insert(0, key, 1, appeng.api.config.Actionable.MODULATE) == 0, "Frozen tank accepted fluid");
        helper.assertTrue(tank.extract(0, key, Long.MAX_VALUE - 16000, appeng.api.config.Actionable.MODULATE) == Long.MAX_VALUE - 16000,
                "Frozen tank lost resources on extraction");
        helper.assertTrue(tank.getAmount(0) == 16000, "Wrong remaining fluid");
        tank.extract(0, key, 1, appeng.api.config.Actionable.MODULATE);
        helper.assertTrue(tank.insert(0, key, 2, appeng.api.config.Actionable.SIMULATE) == 1
                        && tank.getAmount(0) == 15999, "Insertion simulation changed recovered tank");
        helper.assertTrue(tank.insert(0, key, 2, appeng.api.config.Actionable.MODULATE) == 1
                        && tank.getAmount(0) == 16000, "Insertion did not resume at base capacity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void processingNbtRoundTrip(GameTestHelper helper) {
        var state = new ProcessingState<AEKey>("ae2:inscriber/test",
                List.of(new ResourceAmount<>(AEItemKey.of(Items.IRON_INGOT), 4096)),
                List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 8192)), 1000, 125, 4);
        helper.assertTrue(state.equals(ProcessingCodec.read(ProcessingCodec.write(state))), "Batch NBT changed owned resources or payments");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void machineSaveReload(GameTestHelper helper) {
        String[] ids = {"ae2:inscriber", "expatternprovider:ex_inscriber", "expatternprovider:circuit_cutter",
                "advanced_ae:reaction_chamber", "ae2cs:circuit_etcher", "ae2cs:crystal_pulverizer",
                "ae2cs:crystal_aggregator", "ae2cs:entropy_variation_reaction_chamber"};
        for (String id : ids) {
            var key = ResourceLocation.parse(id);
            if (!ForgeRegistries.BLOCKS.containsKey(key)) continue;
            var block = ForgeRegistries.BLOCKS.getValue(key);
            helper.setBlock(new BlockPos(1, 1, 1), block);
            var machine = helper.getBlockEntity(new BlockPos(1, 1, 1));
            helper.assertTrue(machine != null, "Missing machine entity: " + id);
            helper.assertTrue(moakiee.support.MachineBreakProtection.isProtectedMachine(machine), "Machine inventories were not registered: " + id);
            var saved = machine.saveWithFullMetadata();
            var empty = saved.copy();
            var batch = ProcessingCodec.write(new ProcessingState<AEKey>("test:reload",
                    List.of(new ResourceAmount<>(AEItemKey.of(Items.IRON_INGOT), 128)),
                    List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 256)), 100, 25, 4));
            if (id.equals("expatternprovider:ex_inscriber")) {
                var thread = new net.minecraft.nbt.CompoundTag();
                thread.put("ae2ocProcessing", batch);
                saved.put("ae2ocThread0", thread);
            } else saved.put("ae2ocProcessing", batch);
            machine.load(saved);
            machine.load(saved);
            helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(machine) == 128,
                    "Repeated loading duplicated or lost the owned batch: " + id);
            if (machine instanceof appeng.api.networking.ticking.IGridTickable ticking) {
                helper.assertTrue(!ticking.getTickingRequest(null).isSleeping(), "Reloaded owned batch was put to sleep");
            }
            machine.load(empty);
            helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(machine) == 0,
                    "Loading an empty snapshot retained the previous batch: " + id);
            helper.assertTrue(machine.getType() == helper.getBlockEntity(new BlockPos(1, 1, 1)).getType(), "Machine type changed: " + id);
        }
        helper.succeed();
    }
}
