package moakiee.ae2oc;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEBlocks;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.InscriberMenu;
import appeng.menu.locator.MenuLocators;
import moakiee.Ae2Overclocked;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.client.LogicalMenuSlot;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Development-only loopback client contract. It uses an integrated server, but all menu state changes
 * after opening go through the actual client click packet and server synchronizer path.
 */
@Mod.EventBusSubscriber(modid = Ae2Overclocked.MODID, value = Dist.CLIENT)
public final class ClientInteractionSmokeTest {
    private static final Logger LOGGER = LogManager.getLogger("ae2_overclocked/clientInteractionSmoke");
    private static final long INITIAL_AMOUNT = 5_000_000L;
    private static final long AFTER_CLICK_AMOUNT = INITIAL_AMOUNT - 64;
    private static final long EXTERNAL_UPDATE_AMOUNT = 4_242L;
    private static final long SHIFT_AMOUNT = 65L;
    private static final int MAX_WAIT_TICKS = 240;
    private static final BlockPos MACHINE_OFFSET = new BlockPos(1, -1, 0);
    private static final String WORLD_NAME = "ae2oc-client-interaction-smoke-" + System.currentTimeMillis();

    private enum Phase {
        LOAD_WORLD,
        WAIT_PLAYER,
        WAIT_INITIAL_MENU,
        WAIT_CLICK,
        WAIT_EXTERNAL_UPDATE,
        WAIT_CLOSE,
        WAIT_REOPENED,
        WAIT_SHIFT_UPDATE,
        WAIT_SHIFT_CLICK,
        DONE
    }

    private static Phase phase = Phase.LOAD_WORLD;
    private static volatile boolean clientReady;
    private static int waited;
    private static int playerWarmupTicks;
    private static boolean worldLoadRequested;
    private static boolean serverSetupScheduled;
    private static boolean initialOpenScheduled;
    private static boolean clickSent;
    private static boolean externalUpdateScheduled;
    private static boolean closeSent;
    private static boolean reopenScheduled;
    private static boolean shiftUpdateScheduled;
    private static boolean shiftClickSent;
    private static boolean initialScreenshotArmed;
    private static boolean reopenedScreenshotArmed;
    private static String lastScreenClass;
    private static int lastClientMenuId = Integer.MIN_VALUE;
    private static volatile Throwable failure;
    private static volatile BlockPos machinePos;
    private static volatile AEItemKey identity;
    private static volatile boolean serverSetupComplete;

    private ClientInteractionSmokeTest() {}

    @Mod.EventBusSubscriber(modid = Ae2Overclocked.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class LoadCheck {
        private LoadCheck() {}

        @SubscribeEvent
        public static void load(FMLLoadCompleteEvent event) {
            event.enqueueWork(() -> clientReady = true);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase == Phase.DONE || !clientReady) return;
        if (failure != null) throw new IllegalStateException("Client interaction smoke failed", failure);

        var minecraft = Minecraft.getInstance();
        try {
            switch (phase) {
                case LOAD_WORLD -> loadWorld(minecraft);
                case WAIT_PLAYER -> awaitPlayer(minecraft);
                case WAIT_INITIAL_MENU -> awaitInitialMenu(minecraft);
                case WAIT_CLICK -> awaitClick(minecraft);
                case WAIT_EXTERNAL_UPDATE -> awaitExternalUpdate(minecraft);
                case WAIT_CLOSE -> awaitClose(minecraft);
                case WAIT_REOPENED -> awaitReopened(minecraft);
                case WAIT_SHIFT_UPDATE -> awaitShiftUpdate(minecraft);
                case WAIT_SHIFT_CLICK -> awaitShiftClick(minecraft);
                case DONE -> { return; }
            }
            if (phase != Phase.LOAD_WORLD && phase != Phase.DONE) {
                waited++;
                if (waited > MAX_WAIT_TICKS) fail("Timed out in phase " + phase);
            }
        } catch (Throwable throwable) {
            fail(throwable);
        }
        if (failure != null) throw new IllegalStateException("Client interaction smoke failed", failure);
    }

    private static void loadWorld(Minecraft minecraft) {
        if (minecraft.level != null) {
            phase = Phase.WAIT_PLAYER;
            waited = 0;
            return;
        }
        if (!worldLoadRequested && minecraft.screen instanceof TitleScreen) {
            // Use a fresh disposable world so a server-only GameTest save can never poison this client run.
            minecraft.createWorldOpenFlows().createFreshLevel(WORLD_NAME,
                    new LevelSettings(WORLD_NAME, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0L, false, false), WorldPresets::createNormalWorldDimensions);
            worldLoadRequested = true;
            phase = Phase.WAIT_PLAYER;
            waited = 0;
        }
    }

    private static void awaitPlayer(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
        // Do not race the final join/registry packets while the client is still receiving the level.
        if (minecraft.screen != null) {
            playerWarmupTicks = 0;
            return;
        }
        if (playerWarmupTicks++ < 20) return;
        if (serverSetupScheduled) return;
        serverSetupScheduled = true;
        LOGGER.info("Client interaction smoke: scheduling server setup for {}", minecraft.player.getUUID());
        minecraft.getSingleplayerServer().execute(() -> {
            try {
                var server = minecraft.getSingleplayerServer();
                var clientPlayer = minecraft.player;
                if (server == null || clientPlayer == null) throw new IllegalStateException("Integrated server player disappeared");
                var player = server.getPlayerList().getPlayer(clientPlayer.getUUID());
                if (player == null) throw new IllegalStateException("Integrated server did not create the test player");
                var level = player.serverLevel();
                machinePos = player.blockPosition().offset(MACHINE_OFFSET.getX(), MACHINE_OFFSET.getY(), MACHINE_OFFSET.getZ());
                level.setBlock(machinePos, AEBlocks.INSCRIBER.block().defaultBlockState(), 3);
                var machine = (appeng.blockentity.misc.InscriberBlockEntity) level.getBlockEntity(machinePos);
                if (machine == null) throw new IllegalStateException("Could not create the test inscriber");

                player.closeContainer();
                player.getInventory().clearContent();
                var namedGold = new ItemStack(Items.GOLD_INGOT);
                namedGold.setHoverName(Component.literal("client menu identity"));
                identity = AEItemKey.of(namedGold);
                ManagedItemStorages.slots(machine.getInternalInventory()).get(3)
                        .write(new ResourceAmount<>(identity, INITIAL_AMOUNT));
                serverSetupComplete = true;
                LOGGER.info("Client interaction smoke: server prepared InscriberMenu host at {}", machinePos);
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: server setup failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_INITIAL_MENU;
        waited = 0;
    }

    private static void awaitInitialMenu(Minecraft minecraft) {
        logClientSurface(minecraft);
        if (!serverSetupComplete || machinePos == null
                || !(minecraft.level.getBlockEntity(machinePos) instanceof appeng.blockentity.misc.InscriberBlockEntity)) {
            return;
        }
        if (!initialOpenScheduled) {
            initialOpenScheduled = true;
            var server = minecraft.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                    var machine = (appeng.blockentity.misc.InscriberBlockEntity) server.overworld().getBlockEntity(machinePos);
                    openMenu(player, machine);
                    LOGGER.info("Client interaction smoke: server opened InscriberMenu id={} at {}",
                            player.containerMenu.containerId, machinePos);
                } catch (Throwable throwable) {
                    LOGGER.error("Client interaction smoke: initial open failed", throwable);
                    failure = throwable;
                }
            });
        }
        var menu = clientMenu(minecraft);
        if (menu == null || identity == null) return;
        var output = logicalOutput(menu);
        var value = unwrap(output.getItem());
        if (value == null || value.amount() != INITIAL_AMOUNT || !identity.equals(value.what())) return;
        if (!(minecraft.screen instanceof AbstractContainerScreen<?>)) return;
        if (!initialScreenshotArmed) {
            initialScreenshotArmed = true;
            return;
        }
        takeScreenshot("client-menu-initial.png", minecraft);
        if (clickSent) return;
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, output.index, 0, ClickType.PICKUP, minecraft.player);
        clickSent = true;
        phase = Phase.WAIT_CLICK;
        waited = 0;
    }

    private static void awaitClick(Minecraft minecraft) {
        var menu = clientMenu(minecraft);
        if (menu == null || identity == null) return;
        var output = logicalOutput(menu);
        var value = unwrap(output.getItem());
        if (value == null || value.amount() != AFTER_CLICK_AMOUNT) return;
        var carried = menu.getCarried();
        if (carried.getCount() != 64 || !identity.matches(carried)) return;
        if (externalUpdateScheduled) return;
        externalUpdateScheduled = true;
        var server = minecraft.getSingleplayerServer();
        if (server == null || machinePos == null) fail("Integrated server disappeared after click");
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                var machine = (appeng.blockentity.misc.InscriberBlockEntity) server.overworld().getBlockEntity(machinePos);
                ManagedItemStorages.slots(machine.getInternalInventory()).get(3)
                        .write(new ResourceAmount<>(identity, EXTERNAL_UPDATE_AMOUNT));
                player.containerMenu.broadcastChanges();
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: external update failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_EXTERNAL_UPDATE;
        waited = 0;
    }

    private static void awaitExternalUpdate(Minecraft minecraft) {
        var menu = clientMenu(minecraft);
        if (menu == null) return;
        var value = unwrap(logicalOutput(menu).getItem());
        if (value == null || value.amount() != EXTERNAL_UPDATE_AMOUNT) return;
        if (closeSent) return;
        minecraft.player.closeContainer();
        closeSent = true;
        phase = Phase.WAIT_CLOSE;
        waited = 0;
    }

    private static void awaitClose(Minecraft minecraft) {
        if (minecraft.screen instanceof AbstractContainerScreen<?>) return;
        if (reopenScheduled) return;
        var server = minecraft.getSingleplayerServer();
        if (server == null || machinePos == null) fail("Integrated server disappeared during close");
        reopenScheduled = true;
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                var machine = (appeng.blockentity.misc.InscriberBlockEntity) server.overworld().getBlockEntity(machinePos);
                openMenu(player, machine);
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: reopen failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_REOPENED;
        waited = 0;
    }

    private static void awaitReopened(Minecraft minecraft) {
        var menu = clientMenu(minecraft);
        if (menu == null) return;
        var value = unwrap(logicalOutput(menu).getItem());
        if (value == null || value.amount() != EXTERNAL_UPDATE_AMOUNT || !identity.equals(value.what())) return;
        if (!(minecraft.screen instanceof AbstractContainerScreen<?>)) return;
        if (!reopenedScreenshotArmed) {
            reopenedScreenshotArmed = true;
            return;
        }
        takeScreenshot("client-menu-reopened.png", minecraft);
        if (shiftUpdateScheduled) return;
        shiftUpdateScheduled = true;
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                var machine = (appeng.blockentity.misc.InscriberBlockEntity) server.overworld().getBlockEntity(machinePos);
                player.getInventory().clearContent();
                ManagedItemStorages.slots(machine.getInternalInventory()).get(3)
                        .write(new ResourceAmount<>(identity, SHIFT_AMOUNT));
                player.containerMenu.broadcastChanges();
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: Shift update failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_SHIFT_UPDATE;
        waited = 0;
    }

    private static void awaitShiftUpdate(Minecraft minecraft) {
        var menu = clientMenu(minecraft);
        if (menu == null) return;
        var value = unwrap(logicalOutput(menu).getItem());
        if (value == null || value.amount() != SHIFT_AMOUNT) return;
        if (shiftClickSent) return;
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, logicalOutput(menu).index, 0,
                ClickType.QUICK_MOVE, minecraft.player);
        shiftClickSent = true;
        phase = Phase.WAIT_SHIFT_CLICK;
        waited = 0;
    }

    private static void awaitShiftClick(Minecraft minecraft) {
        var menu = clientMenu(minecraft);
        if (menu == null) return;
        var output = logicalOutput(menu);
        var item = output.getItem();
        var value = unwrap(item);
        var amount = value == null ? (item.isEmpty() ? 0 : item.getCount()) : value.amount();
        var inventoryCount = countMatching(minecraft.player.getInventory(), identity);
        if (waited % 20 == 0) {
            LOGGER.info("Client interaction smoke: Shift result pending output={}, carried={}, inventory={}, menuId={}",
                    amount, menu.getCarried(), inventoryCount, menu.containerId);
        }
        if (amount != 1 || !matchesIdentity(item, identity)) return;
        if (inventoryCount != 64) {
            return;
        }
        LOGGER.info("Client interaction smoke passed: loopback menu sync, NBT, click, external update, close/reopen and Shift conservation");
        phase = Phase.DONE;
        minecraft.stop();
    }

    private static void openMenu(ServerPlayer player, appeng.blockentity.misc.InscriberBlockEntity machine) {
        if (!MenuOpener.open(InscriberMenu.TYPE, player, MenuLocators.forBlockEntity(machine)))
            throw new IllegalStateException("AE2 menu opener rejected the test machine");
    }

    private static InscriberMenu clientMenu(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.player.containerMenu instanceof InscriberMenu menu) return menu;
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return null;
        if (!(screen.getMenu() instanceof InscriberMenu menu))
            throw new IllegalStateException("Expected InscriberMenu, got " + screen.getMenu().getClass().getName());
        return menu;
    }

    private static void logClientSurface(Minecraft minecraft) {
        var screenClass = minecraft.screen == null ? "<none>" : minecraft.screen.getClass().getName();
        var menuId = minecraft.player == null || minecraft.player.containerMenu == null
                ? Integer.MIN_VALUE
                : minecraft.player.containerMenu.containerId;
        var screenFactoryRegistered = MenuScreens.getScreenFactory(
                InscriberMenu.TYPE, minecraft, 0, Component.empty()).isPresent();
        if (!screenClass.equals(lastScreenClass) || menuId != lastClientMenuId) {
            LOGGER.info("Client interaction smoke: client surface screen={}, playerMenu={}, inscriberFactory={}, phase={}",
                    screenClass, menuId, screenFactoryRegistered, phase);
            lastScreenClass = screenClass;
            lastClientMenuId = menuId;
        }
    }

    private static LogicalMenuSlot logicalOutput(InscriberMenu menu) {
        for (var slot : menu.slots) {
            if (slot instanceof LogicalMenuSlot logical && logical.getSlotIndex() == 3) return logical;
        }
        throw new IllegalStateException("Managed inscriber output slot was not projected on the client");
    }

    private static GenericStack unwrap(ItemStack stack) {
        return GenericStack.isWrapped(stack) ? GenericStack.unwrapItemStack(stack) : null;
    }

    private static boolean matchesIdentity(ItemStack stack, AEItemKey key) {
        var value = unwrap(stack);
        return value == null ? key.matches(stack) : key.equals(value.what());
    }

    private static int countMatching(Inventory inventory, AEItemKey key) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (key.matches(stack)) total += stack.getCount();
        }
        return total;
    }

    private static void takeScreenshot(String name, Minecraft minecraft) {
        try (var screenshot = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            var path = Path.of("../build/reports").resolve(name);
            Files.createDirectories(path.getParent());
            screenshot.writeToFile(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save client menu screenshot", exception);
        }
    }

    private static void fail(String message) {
        failure = new IllegalStateException(message);
    }

    private static void fail(Throwable throwable) {
        failure = throwable;
    }
}
