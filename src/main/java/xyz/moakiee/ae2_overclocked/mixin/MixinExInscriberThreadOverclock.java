package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelEngine;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.me.InscriberThread", remap = false)
public abstract class MixinExInscriberThreadOverclock {

    @Unique
    private static final double AE2OC_THREAD_RECIPE_ENERGY = 2000.0;

    
    @Unique
    private int ae2oc_pendingParallel = 0;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ae2oc_parallelOverclockThreadTick(CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;

        try {
            Field hostField = ae2oc_getField(self.getClass(), "host");
            hostField.setAccessible(true);
            Object host = hostField.get(self);

            boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(host);
            int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(host);
            if (!hasOverclock && parallelMultiplier <= 1) {
                return;
            }
            Field smashField = ae2oc_getField(self.getClass(), "smash");
            smashField.setAccessible(true);
            boolean smash = smashField.getBoolean(self);

            Field finalStepField = ae2oc_getField(self.getClass(), "finalStep");
            finalStepField.setAccessible(true);
            int finalStep = finalStepField.getInt(self);
            if (smash) {
                finalStep += 4;
                finalStepField.setInt(self, finalStep);

                if (finalStep >= 8 && finalStep < 16) {
                    int parallel = Math.max(this.ae2oc_pendingParallel, 1);
                    ae2oc_finishCraftParallel(self, host, parallel);
                    this.ae2oc_pendingParallel = 0;
                    finalStepField.setInt(self, 16);
                }
                if (finalStep >= 16) {
                    finalStepField.setInt(self, 0);
                    Method setSmash = self.getClass().getMethod("setSmash", boolean.class);
                    setSmash.invoke(self, false);
                    ae2oc_markHostForUpdate(host);
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }
            Method getTask = self.getClass().getMethod("getTask");
            InscriberRecipe recipe = (InscriberRecipe) getTask.invoke(self);
            if (recipe == null) {
                return;
            }
            int actualParallel = ae2oc_calculateParallel(self, host, recipe, parallelMultiplier);
            if (actualParallel < 1) {
                return;
            }

            if (hasOverclock) {
                double totalEnergy = actualParallel * AE2OC_THREAD_RECIPE_ENERGY;
                if (!ae2oc_tryConsumePower(host, totalEnergy)) {
                    return;
                }

                this.ae2oc_pendingParallel = actualParallel;
                Method setSmash = self.getClass().getMethod("setSmash", boolean.class);
                setSmash.invoke(self, true);
                finalStepField.setInt(self, 0);
                ae2oc_markHostForUpdate(host);
                cir.setReturnValue(TickRateModulation.URGENT);
            } else {
                this.ae2oc_pendingParallel = actualParallel;
            }

        } catch (Exception e) {
        }
    }

    
    @Unique
    private int ae2oc_calculateParallel(Object self, Object host, InscriberRecipe recipe, int cardMultiplier) {
        try {
            Field sideField = ae2oc_getField(self.getClass(), "sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            ItemStack inputStack = (ItemStack) getStackInSlot.invoke(sideHandler, 0);
            int inputCount = inputStack.getCount();
            if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                Field topField = ae2oc_getField(self.getClass(), "topItemHandler");
                topField.setAccessible(true);
                Object topHandler = topField.get(self);

                Field bottomField = ae2oc_getField(self.getClass(), "bottomItemHandler");
                bottomField.setAccessible(true);
                Object bottomHandler = bottomField.get(self);

                ItemStack topStack = (ItemStack) getStackInSlot.invoke(topHandler, 0);
                ItemStack bottomStack = (ItemStack) getStackInSlot.invoke(bottomHandler, 0);

                int topCount = topStack.isEmpty() ? Integer.MAX_VALUE : topStack.getCount();
                int bottomCount = bottomStack.isEmpty() ? Integer.MAX_VALUE : bottomStack.getCount();
                cardMultiplier = Math.min(cardMultiplier, Math.min(topCount, bottomCount));
            }

            ItemStack outputStack = recipe.getResultItem().copy();

            Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            double availableEnergy = ae2oc_getAvailableEnergy(host);

            ParallelEngine.ParallelResult result = ParallelEngine.calculate(
                    cardMultiplier, inputCount, 1, outputStack,
                    (stack, simulate) -> {
                        try {
                            return (ItemStack) insertMethod.invoke(sideHandler, 1, stack, simulate);
                        } catch (Exception e) {
                            return stack;
                        }
                    },
                    availableEnergy, AE2OC_THREAD_RECIPE_ENERGY
            );

            return result.actualParallel();
        } catch (Exception e) {
            return 0;
        }
    }

    
    @Unique
    private void ae2oc_finishCraftParallel(Object self, Object host, int parallel) {
        try {
            Method getTask = self.getClass().getMethod("getTask");
            InscriberRecipe recipe = (InscriberRecipe) getTask.invoke(self);
            if (recipe == null || parallel <= 0) return;

            Field sideField = ae2oc_getField(self.getClass(), "sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);

            Field topField = ae2oc_getField(self.getClass(), "topItemHandler");
            topField.setAccessible(true);
            Object topHandler = topField.get(self);

            Field bottomField = ae2oc_getField(self.getClass(), "bottomItemHandler");
            bottomField.setAccessible(true);
            Object bottomHandler = bottomField.get(self);

            ItemStack singleOutput = recipe.getResultItem().copy();
            int singleOutputCount = Math.max(singleOutput.getCount(), 1);
            long totalOutputLong = (long) singleOutputCount * parallel;
            int totalOutput = (int) Math.min(totalOutputLong, Integer.MAX_VALUE);

            ItemStack outputCopy = singleOutput.copy();
            outputCopy.setCount(totalOutput);

            Method insertMethod = sideHandler.getClass().getMethod("insertItem",
                    int.class, ItemStack.class, boolean.class);

            int insertedTotal = 0;
            boolean directToNetwork = ParallelCardRuntime.getParallelMultiplier(host) > 1;
            if (directToNetwork) {
                int insertedToNetwork = ae2oc_insertStackToNetwork(host, outputCopy.copy(), Actionable.MODULATE);
                insertedTotal += insertedToNetwork;

                int remainingCount = totalOutput - insertedToNetwork;
                if (remainingCount > 0) {
                    ItemStack remaining = outputCopy.copy();
                    remaining.setCount(remainingCount);
                    ItemStack leftover = (ItemStack) insertMethod.invoke(sideHandler, 1, remaining, false);
                    insertedTotal += remainingCount - leftover.getCount();
                }
            } else {
                ItemStack leftover = (ItemStack) insertMethod.invoke(sideHandler, 1, outputCopy, false);
                insertedTotal = totalOutput - leftover.getCount();
            }

            int actualParallel = insertedTotal / singleOutputCount;

            if (actualParallel > 0) {
                Method setProcessingTime = self.getClass().getDeclaredMethod("setProcessingTime", int.class);
                setProcessingTime.setAccessible(true);
                setProcessingTime.invoke(self, 0);

                if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                    Method extractItem = topHandler.getClass().getMethod("extractItem",
                            int.class, int.class, boolean.class);
                    extractItem.invoke(topHandler, 0, actualParallel, false);
                    extractItem.invoke(bottomHandler, 0, actualParallel, false);
                }

                Method sideExtract = sideHandler.getClass().getMethod("extractItem",
                        int.class, int.class, boolean.class);
                sideExtract.invoke(sideHandler, 0, actualParallel, false);
            }

            if (directToNetwork) {
                ae2oc_transferOutputToNetwork(host, sideHandler);
            }

            ae2oc_saveHostChanges(host);
        } catch (Exception e) {
        }
    }
    
    
    @Unique
    private void ae2oc_transferOutputToNetwork(Object host, Object sideHandler) {
        try {
            Method getStackInSlot = sideHandler.getClass().getMethod("getStackInSlot", int.class);
            Method extractItem = sideHandler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
            ItemStack stack = (ItemStack) getStackInSlot.invoke(sideHandler, 1);
            if (stack.isEmpty()) return;

            int inserted = ae2oc_insertStackToNetwork(host, stack.copy(), Actionable.MODULATE);
            if (inserted > 0) {
                extractItem.invoke(sideHandler, 1, inserted, false);
            }
        } catch (Exception e) {
        }
    }

    @Unique
    private int ae2oc_insertStackToNetwork(Object host, ItemStack stack, Actionable mode) {
        if (stack.isEmpty()) {
            return 0;
        }
        try {
            Method getMainNode = host.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(host);
            if (mainNode == null) {
                return 0;
            }

            Method getGrid = mainNode.getClass().getMethod("getGrid");
            Object grid = getGrid.invoke(mainNode);
            if (!(grid instanceof appeng.api.networking.IGrid iGrid)) {
                return 0;
            }

            IStorageService storageService = iGrid.getService(IStorageService.class);
            if (storageService == null) {
                return 0;
            }

            AEItemKey key = AEItemKey.of(stack);
            if (key == null) {
                return 0;
            }

            appeng.api.networking.security.IActionSource actionSource = ae2oc_getActionSource(host, mainNode);
            long inserted = storageService.getInventory().insert(key, stack.getCount(), mode, actionSource);
            if (inserted <= 0) {
                return 0;
            }
            return (int) Math.min(inserted, Integer.MAX_VALUE);
        } catch (Exception e) {
            return 0;
        }
    }

    
    @Unique
    private appeng.api.networking.security.IActionSource ae2oc_getActionSource(Object host, Object mainNode) {
        try {
            if (mainNode != null) {
                Method getNode = mainNode.getClass().getMethod("getNode");
                Object node = getNode.invoke(mainNode);
                if (node instanceof appeng.api.networking.IGridNode gridNode) {
                    Object owner = gridNode.getOwner();
                    if (owner instanceof appeng.api.networking.security.IActionHost actionHost) {
                        return appeng.api.networking.security.IActionSource.ofMachine(actionHost);
                    }
                }
            }
        } catch (Exception e) {
        }
        return appeng.api.networking.security.IActionSource.empty();
    }

    @Unique
    private double ae2oc_getAvailableEnergy(Object host) {
        double total = 0;
        try {
            Method extractMethod = host.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            total += (double) extractMethod.invoke(host, Double.MAX_VALUE,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);

            Method getMainNode = host.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(host);
            if (mainNode != null) {
                Method getGrid = mainNode.getClass().getMethod("getGrid");
                Object grid = getGrid.invoke(mainNode);
                if (grid != null) {
                    Method getEnergyService = grid.getClass().getMethod("getEnergyService");
                    IEnergyService energyService = (IEnergyService) getEnergyService.invoke(grid);
                    total += energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
                }
            }
        } catch (Exception e) {
        }
        return total;
    }

    @Unique
    private boolean ae2oc_tryConsumePower(Object host, double powerNeeded) {
        try {
            Method extractMethod = host.getClass().getMethod(
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);

            double extracted = (double) extractMethod.invoke(host, powerNeeded,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (extracted >= powerNeeded - 0.01) {
                extractMethod.invoke(host, powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }

            Method getMainNode = host.getClass().getMethod("getMainNode");
            Object mainNode = getMainNode.invoke(host);
            if (mainNode != null) {
                Method getGrid = mainNode.getClass().getMethod("getGrid");
                Object grid = getGrid.invoke(mainNode);
                if (grid != null) {
                    Method getEnergyService = grid.getClass().getMethod("getEnergyService");
                    IEnergyService energyService = (IEnergyService) getEnergyService.invoke(grid);
                    double networkExtracted = energyService.extractAEPower(powerNeeded, Actionable.SIMULATE,
                            PowerMultiplier.CONFIG);
                    if (networkExtracted >= powerNeeded - 0.01) {
                        energyService.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Unique
    private Field ae2oc_getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return ae2oc_getField(superClass, fieldName);
            }
            throw e;
        }
    }

    @Unique
    private void ae2oc_markHostForUpdate(Object host) {
        try {
            Method method = host.getClass().getMethod("markForUpdate");
            method.invoke(host);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_saveHostChanges(Object host) {
        try {
            Method method = host.getClass().getMethod("saveChanges");
            method.invoke(host);
        } catch (Exception ignored) {
        }
    }
}
