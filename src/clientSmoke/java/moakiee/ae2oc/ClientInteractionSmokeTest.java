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
import net.minecraft.server.level.ServerLevel;
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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

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
    private static final BlockPos MACHINE_OFFSET = new BlockPos(0, 0, 2);
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
        WAIT_CLOSE_FOR_EXT_INSCRIBER,
        WAIT_EXT_INSCRIBER_MENU,
        WAIT_EXT_INSCRIBER_HIDDEN_CLICK,
        WAIT_EXT_INSCRIBER_CLICK,
        WAIT_CLOSE_FOR_REACTION_CHAMBER,
        WAIT_REACTION_CHAMBER_MENU,
        WAIT_CLOSE_FOR_CUTTER,
        WAIT_CUTTER_MENU,
        WAIT_WORLD_RENDER,
        DISCONNECT_WORLD,
        WAIT_TITLE_SCREEN,
        RECONNECT_WORLD,
        WAIT_RECONNECTED_PLAYER,
        WAIT_RECONNECTED_MENU,
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
    private static boolean extInscriberSetupScheduled;
    private static boolean extInscriberScreenshotArmed;
    private static boolean extInscriberPage3Requested;
    private static boolean extInscriberPage0Requested;
    private static boolean extInscriberHiddenClickSent;
    private static boolean extInscriberClickSent;
    private static boolean reactionChamberSetupScheduled;
    private static boolean reactionChamberScreenshotArmed;
    private static boolean cutterSetupScheduled;
    private static boolean cutterScreenshotArmed;
    private static int worldRenderTicks;
    private static int disconnectTicks;
    private static int reconnectedWarmupTicks;
    private static boolean reconnectedOpenScheduled;
    private static boolean reconnectedScreenshotArmed;
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
            logClientSurface(minecraft);
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
                case WAIT_CLOSE_FOR_EXT_INSCRIBER -> awaitCloseForExtInscriber(minecraft);
                case WAIT_EXT_INSCRIBER_MENU -> awaitExtInscriberMenu(minecraft);
                case WAIT_EXT_INSCRIBER_HIDDEN_CLICK -> awaitExtInscriberHiddenClick(minecraft);
                case WAIT_EXT_INSCRIBER_CLICK -> awaitExtInscriberClick(minecraft);
                case WAIT_CLOSE_FOR_REACTION_CHAMBER -> awaitCloseForReactionChamber(minecraft);
                case WAIT_REACTION_CHAMBER_MENU -> awaitReactionChamberMenu(minecraft);
                case WAIT_CLOSE_FOR_CUTTER -> awaitCloseForCutter(minecraft);
                case WAIT_CUTTER_MENU -> awaitCutterMenu(minecraft);
                case WAIT_WORLD_RENDER -> awaitWorldRender(minecraft);
                case DISCONNECT_WORLD -> disconnectWorld(minecraft);
                case WAIT_TITLE_SCREEN -> awaitTitleScreen(minecraft);
                case RECONNECT_WORLD -> reconnectWorld(minecraft);
                case WAIT_RECONNECTED_PLAYER -> awaitReconnectedPlayer(minecraft);
                case WAIT_RECONNECTED_MENU -> awaitReconnectedMenu(minecraft);
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
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        for (int dz = -1; dz <= 4; dz++) {
                            var p = player.blockPosition().offset(dx, dy, dz);
                            if (dy == -1) {
                                level.setBlock(p, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                            } else {
                                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                            }
                        }
                    }
                }
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

                if (ModList.get().isLoaded("expatternprovider")) {
                    ExtendedInscriberHelper.setup(level, machinePos.offset(2, 0, 0));
                    LOGGER.info("Client interaction smoke: server prepared ExtendedInscriber host at {}", machinePos.offset(2, 0, 0));
                    CutterHelper.setup(level, machinePos.offset(0, 0, 2));
                    LOGGER.info("Client interaction smoke: server prepared CircuitCutter host at {}", machinePos.offset(0, 0, 2));
                }
                if (ModList.get().isLoaded("advanced_ae")) {
                    ReactionChamberHelper.setup(level, machinePos.offset(-2, 0, 0));
                    LOGGER.info("Client interaction smoke: server prepared ReactionChamber host at {}", machinePos.offset(-2, 0, 0));
                }

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
        if (!serverSetupComplete || machinePos == null
                || !(minecraft.level.getBlockEntity(machinePos) instanceof appeng.blockentity.misc.InscriberBlockEntity)) {
            return;
        }
        if (ModList.get().isLoaded("expatternprovider")
                && (minecraft.level.getBlockEntity(machinePos.offset(2, 0, 0)) == null
                || minecraft.level.getBlockEntity(machinePos.offset(0, 0, 2)) == null)) {
            return;
        }
        if (ModList.get().isLoaded("advanced_ae")
                && minecraft.level.getBlockEntity(machinePos.offset(-2, 0, 0)) == null) {
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
        LOGGER.info("Client interaction smoke: AE2 Inscriber menu interaction passed, closing container");
        minecraft.player.closeContainer();
        phase = Phase.WAIT_CLOSE_FOR_EXT_INSCRIBER;
        waited = 0;
    }

    private static void awaitCloseForExtInscriber(Minecraft minecraft) {
        if (minecraft.screen instanceof AbstractContainerScreen<?>) return;
        if (!ModList.get().isLoaded("expatternprovider")) {
            LOGGER.info("Client interaction smoke: skipping ExtendedInscriber (mod absent)");
            phase = Phase.WAIT_CLOSE_FOR_REACTION_CHAMBER;
            waited = 0;
            return;
        }
        if (extInscriberSetupScheduled) return;
        extInscriberSetupScheduled = true;
        var server = minecraft.getSingleplayerServer();
        if (server == null || machinePos == null) fail("Integrated server disappeared before ExtendedInscriber setup");
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                var extPos = machinePos.offset(2, 0, 0);
                ExtendedInscriberHelper.open(player, extPos);
                LOGGER.info("Client interaction smoke: server opened ContainerExInscriber at {}", extPos);
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: ExtendedInscriber open failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_EXT_INSCRIBER_MENU;
        waited = 0;
    }

    private static void awaitExtInscriberMenu(Minecraft minecraft) {
        if (!ExtendedInscriberHelper.isExtendedInscriberScreen(minecraft.screen)) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for GuiExInscriber, current screen={}",
                        minecraft.screen == null ? "<none>" : minecraft.screen.getClass().getName());
            }
            return;
        }
        var outputs = ExtendedInscriberHelper.getOutputSlots(minecraft.screen);
        if (outputs.size() < 4) return;
        var diamondKey = AEItemKey.of(Items.DIAMOND);
        var ironKey = AEItemKey.of(Items.IRON_INGOT);

        // Switch through the real client-to-server page packet, then wait for the authoritative menu field sync.
        if (!extInscriberPage3Requested) {
            var lane0 = unwrap(outputs.get(0).getItem());
            if (lane0 == null || lane0.amount() != 1_000_000L || !lane0.what().equals(diamondKey)) {
                if (waited % 20 == 0) {
                    LOGGER.info("Client interaction smoke: waiting for lane 0 item, got={}, page={}, enabled={}",
                            lane0, ExtendedInscriberHelper.getPage(minecraft.screen),
                            ExtendedInscriberHelper.getLogicalSlots(minecraft.screen).stream()
                                    .filter(LogicalMenuSlot::isSlotEnabled).count());
                }
                return;
            }
            ExtendedInscriberHelper.requestPage(3);
            extInscriberPage3Requested = true;
            return;
        }
        if (ExtendedInscriberHelper.getPage(minecraft.screen) != 3) return;
        var logicalSlots = ExtendedInscriberHelper.getLogicalSlots(minecraft.screen);
        long enabled = logicalSlots.stream().filter(LogicalMenuSlot::isSlotEnabled).count();
        if (enabled != 4 || outputs.get(0).isSlotEnabled() || !outputs.get(3).isSlotEnabled()) {
            fail("ExtendedInscriber Page 3 did not isolate exactly its four logical slots: enabled=" + enabled);
            return;
        }
        var lane3 = unwrap(outputs.get(3).getItem());
        if (lane3 == null || lane3.amount() != 2_000_000L || !lane3.what().equals(ironKey)) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for lane 3 item, got={}", lane3);
            }
            return;
        }

        if (!extInscriberHiddenClickSent) {
            int menuId = ExtendedInscriberHelper.getMenuId(minecraft.screen);
            minecraft.gameMode.handleInventoryMouseClick(menuId, outputs.get(0).index, 0, ClickType.PICKUP, minecraft.player);
            extInscriberHiddenClickSent = true;
            phase = Phase.WAIT_EXT_INSCRIBER_HIDDEN_CLICK;
            waited = 0;
        }
    }

    private static void awaitExtInscriberHiddenClick(Minecraft minecraft) {
        if (!ExtendedInscriberHelper.isExtendedInscriberScreen(minecraft.screen)) return;
        if (waited < 5) return;
        var outputs = ExtendedInscriberHelper.getOutputSlots(minecraft.screen);
        if (outputs.size() < 4) return;
        if (!ExtendedInscriberHelper.getCarried(minecraft.screen).isEmpty()) {
            fail("ExtendedInscriber hidden Page 0 output was picked up while Page 3 was selected");
            return;
        }
        if (!extInscriberPage0Requested) {
            ExtendedInscriberHelper.requestPage(0);
            extInscriberPage0Requested = true;
            return;
        }
        if (ExtendedInscriberHelper.getPage(minecraft.screen) != 0) return;
        var lane0 = unwrap(outputs.get(0).getItem());
        long enabled = ExtendedInscriberHelper.getLogicalSlots(minecraft.screen).stream()
                .filter(LogicalMenuSlot::isSlotEnabled).count();
        if (enabled != 4 || !outputs.get(0).isSlotEnabled() || outputs.get(3).isSlotEnabled()
                || lane0 == null || lane0.amount() != 1_000_000L) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for Page 0 render state after hidden click, enabled={}, lane0={}",
                        enabled, lane0);
            }
            return;
        }

        if (!extInscriberScreenshotArmed) {
            extInscriberScreenshotArmed = true;
            return;
        }
        takeScreenshot("client-extended-inscriber-menu.png", minecraft);
        if (extInscriberClickSent) return;
        int menuId = ExtendedInscriberHelper.getMenuId(minecraft.screen);
        minecraft.gameMode.handleInventoryMouseClick(menuId, outputs.get(0).index, 0, ClickType.PICKUP, minecraft.player);
        extInscriberClickSent = true;
        phase = Phase.WAIT_EXT_INSCRIBER_CLICK;
        waited = 0;
    }

    private static void awaitExtInscriberClick(Minecraft minecraft) {
        if (!ExtendedInscriberHelper.isExtendedInscriberScreen(minecraft.screen)) return;
        var outputs = ExtendedInscriberHelper.getOutputSlots(minecraft.screen);
        if (outputs.size() < 4) return;
        var lane0 = unwrap(outputs.get(0).getItem());
        if (lane0 == null || lane0.amount() != 1_000_000L - 64) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for lane 0 after click, got={}", lane0);
            }
            return;
        }
        var carried = ExtendedInscriberHelper.getCarried(minecraft.screen);
        if (carried.getCount() != 64 || !carried.is(Items.DIAMOND)) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for carried diamond, got={}", carried);
            }
            return;
        }
        LOGGER.info("Client interaction smoke: ExtendedInscriber 4-lane menu sync and click passed, closing container");
        minecraft.player.closeContainer();
        phase = Phase.WAIT_CLOSE_FOR_REACTION_CHAMBER;
        waited = 0;
    }

    private static void awaitCloseForReactionChamber(Minecraft minecraft) {
        if (minecraft.screen instanceof AbstractContainerScreen<?>) return;
        if (!ModList.get().isLoaded("advanced_ae")) {
            LOGGER.info("Client interaction smoke: skipping ReactionChamber (mod absent)");
            phase = Phase.WAIT_WORLD_RENDER;
            waited = 0;
            return;
        }
        if (reactionChamberSetupScheduled) return;
        reactionChamberSetupScheduled = true;
        var server = minecraft.getSingleplayerServer();
        if (server == null || machinePos == null) fail("Integrated server disappeared before ReactionChamber setup");
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                var rcPos = machinePos.offset(-2, 0, 0);
                ReactionChamberHelper.open(player, rcPos);
                LOGGER.info("Client interaction smoke: server opened ReactionChamberMenu at {}", rcPos);
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: ReactionChamber open failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_REACTION_CHAMBER_MENU;
        waited = 0;
    }

    private static void awaitReactionChamberMenu(Minecraft minecraft) {
        if (!ReactionChamberHelper.isReactionChamberScreen(minecraft.screen)) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for ReactionChamberScreen, current screen={}",
                        minecraft.screen == null ? "<none>" : minecraft.screen.getClass().getName());
            }
            return;
        }
        if (!ReactionChamberHelper.verifyFluidTooltip(minecraft.screen, minecraft)) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for ReactionChamber fluid tooltip");
            }
            return;
        }
        if (!reactionChamberScreenshotArmed) {
            reactionChamberScreenshotArmed = true;
            return;
        }
        takeScreenshot("client-reaction-chamber-menu.png", minecraft);
        LOGGER.info("Client interaction smoke: ReactionChamber fluid capacity card tooltip passed, closing container");
        minecraft.player.closeContainer();
        phase = Phase.WAIT_CLOSE_FOR_CUTTER;
        waited = 0;
    }

    private static void awaitCloseForCutter(Minecraft minecraft) {
        if (minecraft.screen instanceof AbstractContainerScreen<?>) return;
        if (!ModList.get().isLoaded("expatternprovider")) {
            LOGGER.info("Client interaction smoke: skipping Cutter (mod absent)");
            phase = Phase.WAIT_WORLD_RENDER;
            waited = 0;
            return;
        }
        if (cutterSetupScheduled) return;
        cutterSetupScheduled = true;
        var server = minecraft.getSingleplayerServer();
        if (server == null || machinePos == null) fail("Integrated server disappeared before Cutter setup");
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                var cutterPos = machinePos.offset(0, 0, 2);
                CutterHelper.open(player, cutterPos);
                LOGGER.info("Client interaction smoke: server opened ContainerCircuitCutter at {}", cutterPos);
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: Cutter open failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_CUTTER_MENU;
        waited = 0;
    }

    private static void awaitCutterMenu(Minecraft minecraft) {
        if (!CutterHelper.isCutterScreen(minecraft.screen)) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for GuiCircuitCutter, current screen={}",
                        minecraft.screen == null ? "<none>" : minecraft.screen.getClass().getName());
            }
            return;
        }
        if (!cutterScreenshotArmed) {
            cutterScreenshotArmed = true;
            return;
        }
        takeScreenshot("client-cutter-menu.png", minecraft);
        LOGGER.info("Client interaction smoke: Circuit Cutter menu passed, closing container");
        minecraft.player.closeContainer();
        phase = Phase.WAIT_WORLD_RENDER;
        waited = 0;
    }

    private static void awaitWorldRender(Minecraft minecraft) {
        if (minecraft.screen != null) return;
        if (machinePos != null && minecraft.player != null) {
            minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    net.minecraft.world.phys.Vec3.atCenterOf(machinePos.offset(0, 0, 1)));
        }
        if (worldRenderTicks++ < 15) return;
        takeScreenshot("client-machine-world-render.png", minecraft);
        LOGGER.info("Client interaction smoke: world 3D render screenshot captured, proceeding to disconnect phase");
        phase = Phase.DISCONNECT_WORLD;
        waited = 0;
    }

    private static void disconnectWorld(Minecraft minecraft) {
        if (disconnectTicks++ < 10) return;
        LOGGER.info("Client interaction smoke: disconnecting client from world...");
        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(
                net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
        minecraft.setScreen(new TitleScreen());
        LOGGER.info("Client interaction smoke: client disconnected, at TitleScreen; reconnecting to {}", WORLD_NAME);
        phase = Phase.RECONNECT_WORLD;
        waited = 0;
    }

    private static void awaitTitleScreen(Minecraft minecraft) {
        phase = Phase.RECONNECT_WORLD;
        waited = 0;
    }

    private static void reconnectWorld(Minecraft minecraft) {
        minecraft.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD_NAME);
        phase = Phase.WAIT_RECONNECTED_PLAYER;
        waited = 0;
    }

    private static void awaitReconnectedPlayer(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
        if (minecraft.screen != null) {
            reconnectedWarmupTicks = 0;
            return;
        }
        if (reconnectedWarmupTicks++ < 20) return;
        if (reconnectedOpenScheduled) return;
        reconnectedOpenScheduled = true;
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
                if (player == null) throw new IllegalStateException("Reconnected player disappeared");
                var machine = (appeng.blockentity.misc.InscriberBlockEntity) server.overworld().getBlockEntity(machinePos);
                if (machine == null) throw new IllegalStateException("Inscriber disappeared after reconnect");
                openMenu(player, machine);
                LOGGER.info("Client interaction smoke: server reopened InscriberMenu after reconnect");
            } catch (Throwable throwable) {
                LOGGER.error("Client interaction smoke: reopen after reconnect failed", throwable);
                failure = throwable;
            }
        });
        phase = Phase.WAIT_RECONNECTED_MENU;
        waited = 0;
    }

    private static void awaitReconnectedMenu(Minecraft minecraft) {
        var menu = clientMenu(minecraft);
        if (menu == null || identity == null) return;
        var output = logicalOutput(menu);
        var item = output.getItem();
        if (!matchesIdentity(item, identity)) return;
        long amount = getAmount(item);
        if (amount != 1) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for reconnected inscriber slot=1, got={}", amount);
            }
            return;
        }
        int inventoryCount = countMatching(minecraft.player.getInventory(), identity);
        if (inventoryCount != 64) {
            if (waited % 20 == 0) {
                LOGGER.info("Client interaction smoke: waiting for reconnected player inventory=64, got={}", inventoryCount);
            }
            return;
        }
        if (!(minecraft.screen instanceof AbstractContainerScreen<?>)) return;
        if (!reconnectedScreenshotArmed) {
            reconnectedScreenshotArmed = true;
            return;
        }
        takeScreenshot("client-menu-reconnected.png", minecraft);
        LOGGER.info("Client interaction smoke passed: inscriber loopback, extended inscriber 4-lane menu, reaction chamber fluid tooltip, circuit cutter menu and 3D floating render, and disconnect/reconnect ledger conservation (slot=1, inv=64)");
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

    private static long getAmount(ItemStack stack) {
        var value = unwrap(stack);
        return value == null ? (stack.isEmpty() ? 0 : stack.getCount()) : value.amount();
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

    private static final class ExtendedInscriberHelper {
        private ExtendedInscriberHelper() {}

        static void setup(ServerLevel level, BlockPos pos) {
            var block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("expatternprovider", "ex_inscriber"));
            if (block == null) throw new IllegalStateException("Missing ex_inscriber block");
            level.setBlock(pos, block.defaultBlockState(), 3);
            var machine = (com.glodblock.github.extendedae.common.tileentities.TileExInscriber) level.getBlockEntity(pos);
            if (machine == null) throw new IllegalStateException("Missing TileExInscriber entity");
            machine.getUpgrades().setItemDirect(0, new ItemStack(moakiee.ModItems.CAPACITY_CARD.get()));
            var diamondKey = AEItemKey.of(Items.DIAMOND);
            var ironKey = AEItemKey.of(Items.IRON_INGOT);
            ManagedItemStorages.slots(machine.getIndexInventory(0)).get(3)
                    .write(new ResourceAmount<>(diamondKey, 1_000_000L));
            ManagedItemStorages.slots(machine.getIndexInventory(3)).get(3)
                    .write(new ResourceAmount<>(ironKey, 2_000_000L));
        }

        static void open(ServerPlayer player, BlockPos pos) {
            var machine = (com.glodblock.github.extendedae.common.tileentities.TileExInscriber) player.serverLevel().getBlockEntity(pos);
            if (machine == null) throw new IllegalStateException("Missing TileExInscriber entity at " + pos);
            var block = (com.glodblock.github.extendedae.common.blocks.BlockExInscriber) machine.getBlockState().getBlock();
            block.openGui(machine, player);
            if (player.containerMenu == player.inventoryMenu) {
                throw new IllegalStateException("MenuOpener rejected ContainerExInscriber");
            }
        }

        static boolean isExtendedInscriberScreen(Object screen) {
            return screen != null && screen.getClass().getName().equals("com.glodblock.github.extendedae.client.gui.GuiExInscriber");
        }

        static List<LogicalMenuSlot> getOutputSlots(Object screen) {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return List.of();
            List<LogicalMenuSlot> outputs = new ArrayList<>();
            for (var slot : containerScreen.getMenu().slots) {
                if (slot instanceof LogicalMenuSlot logical && logical.getSlotIndex() == 3) {
                    outputs.add(logical);
                }
            }
            return outputs;
        }

        static List<LogicalMenuSlot> getLogicalSlots(Object screen) {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return List.of();
            List<LogicalMenuSlot> slots = new ArrayList<>();
            for (var slot : containerScreen.getMenu().slots) {
                if (slot instanceof LogicalMenuSlot logical) slots.add(logical);
            }
            return slots;
        }

        static int getMenuId(Object screen) {
            return ((AbstractContainerScreen<?>) screen).getMenu().containerId;
        }

        static ItemStack getCarried(Object screen) {
            return ((AbstractContainerScreen<?>) screen).getMenu().getCarried();
        }

        static int getPage(Object screen) {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return -1;
            try {
                var menu = containerScreen.getMenu();
                return (int) menu.getClass().getMethod("getPage").invoke(menu);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to read ContainerExInscriber page", exception);
            }
        }

        static void requestPage(int page) {
            try {
                var handlerClass = Class.forName("com.glodblock.github.extendedae.network.EPPNetworkHandler");
                var packetClass = Class.forName("com.glodblock.github.extendedae.network.packet.CUpdatePage");
                var handler = handlerClass.getField("INSTANCE").get(null);
                var packet = packetClass.getConstructor(int.class).newInstance(page);
                var send = java.util.Arrays.stream(handlerClass.getMethods())
                        .filter(method -> method.getName().equals("sendToServer") && method.getParameterCount() == 1)
                        .findFirst().orElseThrow();
                send.invoke(handler, packet);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to request ContainerExInscriber page", exception);
            }
        }
    }

    private static final class ReactionChamberHelper {
        private ReactionChamberHelper() {}

        static void setup(ServerLevel level, BlockPos pos) {
            var block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("advanced_ae", "reaction_chamber"));
            if (block == null) throw new IllegalStateException("Missing reaction_chamber block");
            level.setBlock(pos, block.defaultBlockState(), 3);
            var machine = (net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity) level.getBlockEntity(pos);
            if (machine == null) throw new IllegalStateException("Missing ReactionChamberEntity");
            machine.getUpgrades().setItemDirect(0, new ItemStack(moakiee.ModItems.CAPACITY_CARD.get()));
            machine.getTank().setStack(1, new GenericStack(appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER), 32000L));
        }

        static void open(ServerPlayer player, BlockPos pos) {
            var machine = (net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity) player.serverLevel().getBlockEntity(pos);
            if (machine == null) throw new IllegalStateException("Missing ReactionChamberEntity at " + pos);
            if (!MenuOpener.open(net.pedroksl.advanced_ae.common.definitions.AAEMenus.REACTION_CHAMBER.get(), player,
                    MenuLocators.forBlockEntity(machine))) {
                throw new IllegalStateException("MenuOpener rejected ReactionChamberMenu");
            }
        }

        static boolean isReactionChamberScreen(Object screen) {
            return screen instanceof net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen;
        }

        static boolean verifyFluidTooltip(Object screen, Minecraft minecraft) {
            if (!(screen instanceof net.pedroksl.advanced_ae.client.gui.ReactionChamberScreen reactionScreen)) return false;
            net.pedroksl.ae2addonlib.client.widgets.FluidTankSlot inputWidget = null;
            for (var child : reactionScreen.children()) {
                if (child instanceof net.pedroksl.ae2addonlib.client.widgets.FluidTankSlot tankSlot && tankSlot.index == 1) {
                    inputWidget = tankSlot;
                    break;
                }
            }
            if (inputWidget == null || inputWidget.getTooltip() == null) return false;
            var text = new StringBuilder();
            for (var line : inputWidget.getTooltip().toCharSequence(minecraft)) {
                line.accept((idx, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
            }
            String str = text.toString();
            return str.contains("32000") && str.contains(String.valueOf(Integer.MAX_VALUE));
        }
    }

    private static final class CutterHelper {
        private CutterHelper() {}

        static void setup(ServerLevel level, BlockPos pos) {
            var block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("expatternprovider", "circuit_cutter"));
            if (block == null) throw new IllegalStateException("Missing circuit_cutter block");
            level.setBlock(pos, block.defaultBlockState(), 3);
            var machine = (com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter) level.getBlockEntity(pos);
            if (machine == null) throw new IllegalStateException("Missing TileCircuitCutter entity");
            machine.getUpgrades().setItemDirect(0, new ItemStack(moakiee.ModItems.CAPACITY_CARD.get()));
            machine.getTank().setStack(0, new GenericStack(appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER), 32000L));
            var state = new moakiee.ae2oc.core.execution.ProcessingState<appeng.api.stacks.AEKey>(
                    "expatternprovider:cutter/logic",
                    List.of(new ResourceAmount<>(AEItemKey.of(net.minecraft.world.item.Items.GOLD_BLOCK), 1)),
                    List.of(new ResourceAmount<>(AEItemKey.of(appeng.core.definitions.AEItems.LOGIC_PROCESSOR_PRINT.asItem()), 9)),
                    100, 25, 3);
            var tag = machine.saveWithFullMetadata();
            tag.put("ae2ocProcessing", moakiee.ae2oc.compat.ae2.ProcessingCodec.write(state));
            machine.load(tag);
            machine.setProgress(com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter.MAX_PROGRESS / 2);
            machine.setWorking(true);
            machine.markForUpdate();
        }

        static void open(ServerPlayer player, BlockPos pos) {
            var machine = (com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter) player.serverLevel().getBlockEntity(pos);
            if (machine == null) throw new IllegalStateException("Missing TileCircuitCutter entity at " + pos);
            var block = (com.glodblock.github.extendedae.common.blocks.BlockCircuitCutter) machine.getBlockState().getBlock();
            block.openGui(machine, player);
            if (player.containerMenu == player.inventoryMenu) {
                throw new IllegalStateException("MenuOpener rejected ContainerCircuitCutter");
            }
        }

        static boolean isCutterScreen(Object screen) {
            return screen != null && screen.getClass().getName().equals("com.glodblock.github.extendedae.client.gui.GuiCircuitCutter");
        }
    }
}
