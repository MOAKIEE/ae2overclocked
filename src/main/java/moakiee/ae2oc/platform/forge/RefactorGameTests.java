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
            var key = new ResourceLocation(id);
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
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void managedFluidRemovalPreservesResources(GameTestHelper helper) {
        var tank = new appeng.helpers.externalstorage.GenericStackInv(null,
                appeng.helpers.externalstorage.GenericStackInv.Mode.STORAGE, 1);
        moakiee.support.OverstackingRegistry.register(tank);
        var key = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        tank.setCapacity(key.getType(), Long.MAX_VALUE);
        tank.setStack(0, new appeng.api.stacks.GenericStack(key, Long.MAX_VALUE));
        tank.setCapacity(key.getType(), 16000);
        helper.assertTrue(tank.getAmount(0) == Long.MAX_VALUE, "Capacity reduction destroyed fluid");
        helper.assertTrue(tank.insert(0, key, 1, appeng.api.config.Actionable.MODULATE) == 0, "Frozen tank accepted fluid");
        helper.assertTrue(tank.extract(0, key, Long.MAX_VALUE - 16000, appeng.api.config.Actionable.MODULATE) == Long.MAX_VALUE - 16000,
                "Frozen tank lost resources on extraction");
        helper.assertTrue(tank.getAmount(0) == 16000, "Wrong remaining fluid");
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
            var key = new ResourceLocation(id);
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
            machine.load(empty);
            helper.assertTrue(moakiee.support.MachineBreakProtection.getInternalItemTotalCount(machine) == 0,
                    "Loading an empty snapshot retained the previous batch: " + id);
            helper.assertTrue(machine.getType() == helper.getBlockEntity(new BlockPos(1, 1, 1)).getType(), "Machine type changed: " + id);
        }
        helper.succeed();
    }
}
