package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import com.mojang.logging.LogUtils;
import moakiee.support.ParallelCardRuntime;
import moakiee.support.OverclockCardRuntime;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@Pseudo
@Mixin(targets = {
        "io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity",
        "io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity",
        "io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity",
        "io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity"
}, remap = false)
public class MixinAE2CSOverclock {

    @Unique
    private static final org.slf4j.Logger AE2OC_LOGGER = LogUtils.getLogger();

    @Unique
    private int ae2oc_prevProgress = -1;
    @Unique
    private int ae2oc_pendingParallel = 0;
    @Unique
    private boolean ae2oc_overclockArmed = false;
    @Unique
    private boolean ae2oc_hasOverclock = false;
    @Unique
    private Object ae2oc_cachedRecipe;
    @Unique
    private int[] ae2oc_cachedMatch;
    @Unique
    private double ae2oc_cachedUnitEnergy = 0.0;
    @Unique
    private Object ae2oc_cachedLevel = null;

    @Inject(method = "serverTick", at = @At("HEAD"))
    private void ae2oc_beforeServerTick(CallbackInfo ci) {
        this.ae2oc_prevProgress = -1;
        this.ae2oc_pendingParallel = 0;
        this.ae2oc_overclockArmed = false;
        this.ae2oc_hasOverclock = false;
        this.ae2oc_cachedRecipe = null;
        this.ae2oc_cachedMatch = null;
        this.ae2oc_cachedUnitEnergy = 0.0;
        this.ae2oc_cachedLevel = ae2oc_getLevel(this);

        // serverTick() 仅在服务端调用（ServerTickingBlockEntity接口保证），无需 isServer 检查
        int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(this);
        boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(this);
        if (!hasOverclock && parallelMultiplier <= 1) {
            return;
        }

        this.ae2oc_hasOverclock = hasOverclock;

        Integer progress = ae2oc_getIntField(this, "recipeProgress");
        Integer total = ae2oc_getIntField(this, "activeRecipeEnergyCost");
        Object recipe = ae2oc_getFieldRecursive(this, "activeRecipe");
        if (progress == null || total == null || recipe == null || total <= 0) {
            return;
        }

        this.ae2oc_prevProgress = progress;
        this.ae2oc_cachedRecipe = recipe;
        this.ae2oc_cachedUnitEnergy = total;

        int pending = ae2oc_calculateParallel(recipe, parallelMultiplier, hasOverclock, total);
        this.ae2oc_pendingParallel = Math.max(pending, 1);
        this.ae2oc_overclockArmed = hasOverclock && progress < total;

        if (parallelMultiplier > 1) {
            AE2OC_LOGGER.info("[AE2OC][AE2CS] machine={} parallelCard={} actualParallel={} overclock={}",
                this.getClass().getSimpleName(), parallelMultiplier, this.ae2oc_pendingParallel, hasOverclock);
        }
    }

    @Inject(method = "serverTick", at = @At("RETURN"))
    private void ae2oc_afterServerTick(CallbackInfo ci) {
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_cachedRecipe == null) {
            return;
        }

        Integer currentProgress = ae2oc_getIntField(this, "recipeProgress");
        if (currentProgress == null) {
            return;
        }

        boolean completedThisTick = currentProgress == 0 && (this.ae2oc_prevProgress > 0 || this.ae2oc_overclockArmed);
        if (!completedThisTick) {
            return;
        }

        int extraRounds = this.ae2oc_pendingParallel - 1;
        if (extraRounds <= 0) {
            return;
        }

        AE2OC_LOGGER.info("[AE2OC][AE2CS] machine={} apply extra rounds={}",
                this.getClass().getSimpleName(), extraRounds);

        ae2oc_doExtraRounds(extraRounds);

        // 额外回合结束后，尝试将本地输出槽中的产物转移到 ME 网络（释放空间）
        ae2oc_flushOutputToMENetwork();
    }

    @Inject(method = "getEnergyPerTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2oc_getEnergyPerTick(CallbackInfoReturnable<Double> cir) {
        if (!OverclockCardRuntime.hasOverclockCard(this)) {
            return;
        }

        Integer progress = ae2oc_getIntField(this, "recipeProgress");
        Integer total = ae2oc_getIntField(this, "activeRecipeEnergyCost");
        if (progress == null || total == null) {
            return;
        }

        int remaining = total - progress;
        if (remaining > 0) {
            cir.setReturnValue((double) remaining);
        }
    }

    // 并行模式不修改 getEnergyPerTick：
    // 在 AE2CS 机器中 progress = consumedEnergy，缩放能耗会直接加速配方完成。
    // 并行的额外能量由 ae2oc_doOneExtraRound 中逐轮扣除。

    @Unique
    private int ae2oc_calculateParallel(Object recipe, int cardMultiplier, boolean hasOverclock, int unitEnergy) {
        if (cardMultiplier <= 1) {
            return 1;
        }

        int materialLimit = ae2oc_getMaterialLimit(recipe);
        if (materialLimit <= 0) {
            return 1;
        }

        int energyLimit = Integer.MAX_VALUE;
        if (hasOverclock && unitEnergy > 0) {
            double available = ae2oc_getAvailableEnergy();
            energyLimit = (int) Math.max(0, available / unitEnergy);
        }

        int actual = Math.min(cardMultiplier, Math.min(materialLimit, energyLimit));
        return Math.max(actual, 1);
    }

    @Unique
    private int ae2oc_getMaterialLimit(Object recipe) {
        try {
            String machine = this.getClass().getName();
            if (machine.endsWith("CircuitEtcherBlockEntity") || machine.endsWith("CrystalAggregatorBlockEntity")) {
                Object inputInv = ae2oc_invokeNoArg(this, "getInputInv");
                if (inputInv == null) {
                    return 1;
                }

                Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                ItemStack s0 = (ItemStack) getStackInSlot.invoke(inputInv, 0);
                ItemStack s1 = (ItemStack) getStackInSlot.invoke(inputInv, 1);
                ItemStack s2 = (ItemStack) getStackInSlot.invoke(inputInv, 2);

                Class<?> inputCls = Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput");
                Method of = inputCls.getMethod("of", ItemStack.class, ItemStack.class, ItemStack.class);
                Object input = of.invoke(null, s0, s1, s2);

                Method findMatch = recipe.getClass().getMethod("findMatch", inputCls);
                int[] match = (int[]) findMatch.invoke(recipe, input);
                if (match == null) {
                    return 0;
                }
                this.ae2oc_cachedMatch = match;

                Method required = recipe.getClass().getMethod("required");
                @SuppressWarnings("unchecked")
                List<Object> requiredList = (List<Object>) required.invoke(recipe);

                int min = Integer.MAX_VALUE;
                for (int i = 0; i < requiredList.size(); i++) {
                    Object ing = requiredList.get(i);
                    Method count = ing.getClass().getMethod("count");
                    int need = (int) count.invoke(ing);
                    int slot = match[i];
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, slot);
                    min = Math.min(min, stack.getCount() / Math.max(need, 1));
                }
                return min == Integer.MAX_VALUE ? 0 : min;
            }

            if (machine.endsWith("CrystalPulverizerBlockEntity")) {
                Object inputInv = ae2oc_invokeNoArg(this, "getInputInv");
                Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                ItemStack stack = (ItemStack) getStackInSlot.invoke(inputInv, 0);

                Method input = recipe.getClass().getMethod("input");
                Object sizedIng = input.invoke(recipe);
                Method count = sizedIng.getClass().getMethod("count");
                int need = (int) count.invoke(sizedIng);
                return stack.getCount() / Math.max(need, 1);
            }

            if (machine.endsWith("EntropyVariationReactionChamberBlockEntity")) {
                Object inputInv = ae2oc_invokeNoArg(this, "getInputInv");
                Method getStack = inputInv.getClass().getMethod("getStack", int.class);
                Object gs = getStack.invoke(inputInv, 0);
                if (gs == null) {
                    return 0;
                }
                Method what = gs.getClass().getMethod("what");
                Method amount = gs.getClass().getMethod("amount");
                Object key = what.invoke(gs);
                long amt = ((Number) amount.invoke(gs)).longValue();
                if (key != null && key.getClass().getName().contains("Fluid")) {
                    return (int) Math.max(amt / 1000L, 0L);
                }
                return (int) Math.max(amt, 0L);
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    @Unique
    private void ae2oc_doExtraRounds(int extraRounds) {
        for (int i = 0; i < extraRounds; i++) {
            if (!ae2oc_doOneExtraRound()) {
                break;
            }
        }
    }

    @Unique
    private boolean ae2oc_doOneExtraRound() {
        try {
            String machine = this.getClass().getName();
            Object recipe = this.ae2oc_cachedRecipe;
            double unitEnergy = Math.max(1.0, this.ae2oc_cachedUnitEnergy);

            // 每轮额外回合都需要消耗能量（无论是否超频）
            if (!ae2oc_tryConsumeEnergy(unitEnergy)) {
                return false;
            }

            if (machine.endsWith("CircuitEtcherBlockEntity") || machine.endsWith("CrystalAggregatorBlockEntity")) {
                Object inputInv = ae2oc_invokeNoArg(this, "getInputInv");
                Object outputInv = ae2oc_invokeNoArg(this, "getOutputInv");
                if (inputInv == null || outputInv == null) {
                    return false;
                }

                Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                ItemStack s0 = (ItemStack) getStackInSlot.invoke(inputInv, 0);
                ItemStack s1 = (ItemStack) getStackInSlot.invoke(inputInv, 1);
                ItemStack s2 = (ItemStack) getStackInSlot.invoke(inputInv, 2);

                Class<?> inputCls = Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput");
                Method of = inputCls.getMethod("of", ItemStack.class, ItemStack.class, ItemStack.class);
                Object input = of.invoke(null, s0, s1, s2);

                Object level = this.ae2oc_cachedLevel;
                if (level == null) return false;
                Object registryAccess = ae2oc_getRegistryAccess(level);
                if (registryAccess == null) return false;
                ItemStack result = ae2oc_invokeAssemble(recipe, input, registryAccess);
                if (result == null || result.isEmpty()) {
                    return false;
                }

                Method insertItem = outputInv.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
                ItemStack remain = (ItemStack) insertItem.invoke(outputInv, 0, result, true);
                if (remain != null && !remain.isEmpty()) {
                    // 本地输出槽已满，尝试输出到 ME 网络
                    if (!ae2oc_tryOutputToMENetwork(result)) {
                        return false;
                    }
                    // ME 网络接收成功，继续扣材料
                    int[] match = this.ae2oc_cachedMatch;
                    if (match == null) {
                        match = ae2oc_recalculateMatch(inputInv, recipe);
                    }
                    if (match == null) {
                        return false;
                    }

                    Method consume = this.getClass().getDeclaredMethod("consumeInputs", recipe.getClass(), int[].class);
                    consume.setAccessible(true);
                    Object consumed = consume.invoke(this, recipe, match);
                    if (!(consumed instanceof Boolean ok) || !ok) {
                        return false;
                    }
                    return true;
                }

                int[] match = this.ae2oc_cachedMatch;
                if (match == null) {
                    match = ae2oc_recalculateMatch(inputInv, recipe);
                }
                if (match == null) {
                    return false;
                }

                Method consume = this.getClass().getDeclaredMethod("consumeInputs", recipe.getClass(), int[].class);
                consume.setAccessible(true);
                Object consumed = consume.invoke(this, recipe, match);
                if (!(consumed instanceof Boolean ok) || !ok) {
                    return false;
                }

                insertItem.invoke(outputInv, 0, result, false);
                return true;
            }

            if (machine.endsWith("CrystalPulverizerBlockEntity")) {
                Object inputInv = ae2oc_invokeNoArg(this, "getInputInv");
                Object outputInv = ae2oc_invokeNoArg(this, "getOutputInv");
                if (inputInv == null || outputInv == null) {
                    return false;
                }
                Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
                ItemStack s0 = (ItemStack) getStackInSlot.invoke(inputInv, 0);

                Class<?> inputCls = Class.forName("io.github.lounode.ae2cs.common.recipe.input.SingleItemStackRecipeInput");
                Method of = inputCls.getMethod("of", ItemStack.class);
                Object input = of.invoke(null, s0);

                Object level = this.ae2oc_cachedLevel;
                if (level == null) return false;
                Object registryAccess = ae2oc_getRegistryAccess(level);
                if (registryAccess == null) return false;
                ItemStack result = ae2oc_invokeAssemble(recipe, input, registryAccess);
                if (result == null || result.isEmpty()) {
                    return false;
                }

                Method addItems = outputInv.getClass().getMethod("addItems", ItemStack.class, boolean.class);
                ItemStack remain = (ItemStack) addItems.invoke(outputInv, result, true);
                if (remain != null && !remain.isEmpty()) {
                    // 本地输出槽已满，尝试输出到 ME 网络
                    if (!ae2oc_tryOutputToMENetwork(result)) {
                        return false;
                    }
                    // ME 网络接收成功，继续扣材料
                    Method consume = this.getClass().getDeclaredMethod("consumeInputs", recipe.getClass());
                    consume.setAccessible(true);
                    Object consumed = consume.invoke(this, recipe);
                    if (!(consumed instanceof Boolean ok) || !ok) {
                        return false;
                    }
                    return true;
                }

                Method consume = this.getClass().getDeclaredMethod("consumeInputs", recipe.getClass());
                consume.setAccessible(true);
                Object consumed = consume.invoke(this, recipe);
                if (!(consumed instanceof Boolean ok) || !ok) {
                    return false;
                }

                addItems.invoke(outputInv, result, false);
                return true;
            }

            if (machine.endsWith("EntropyVariationReactionChamberBlockEntity")) {
                Object outputInv = ae2oc_invokeNoArg(this, "getOutputInv");
                Object actionSource = ae2oc_getFieldRecursive(this, "actionSource");

                Method getRecipeOutput = this.getClass().getDeclaredMethod("getRecipeOutput", recipe.getClass());
                getRecipeOutput.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Object> outputs = (List<Object>) getRecipeOutput.invoke(null, recipe);
                if (outputs == null || outputs.isEmpty()) {
                    return false;
                }

                Method insert = outputInv.getClass().getMethod("insert", Class.forName("appeng.api.stacks.AEKey"), long.class, Actionable.class, Class.forName("appeng.api.networking.security.IActionSource"));

                for (Object gs : outputs) {
                    Method what = gs.getClass().getMethod("what");
                    Method amount = gs.getClass().getMethod("amount");
                    Object key = what.invoke(gs);
                    long amt = ((Number) amount.invoke(gs)).longValue();
                    long accepted = ((Number) insert.invoke(outputInv, key, amt, Actionable.SIMULATE, actionSource)).longValue();
                    if (accepted < amt) {
                        return false;
                    }
                }

                Method consume = this.getClass().getDeclaredMethod("consumeInputs", recipe.getClass());
                consume.setAccessible(true);
                Object consumed = consume.invoke(this, recipe);
                if (!(consumed instanceof Boolean ok) || !ok) {
                    return false;
                }

                for (Object gs : outputs) {
                    Method what = gs.getClass().getMethod("what");
                    Method amount = gs.getClass().getMethod("amount");
                    Object key = what.invoke(gs);
                    long amt = ((Number) amount.invoke(gs)).longValue();
                    insert.invoke(outputInv, key, amt, Actionable.MODULATE, actionSource);
                }
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    @Unique
    private static int[] ae2oc_recalculateMatch(Object inputInv, Object recipe) {
        try {
            Method getStackInSlot = inputInv.getClass().getMethod("getStackInSlot", int.class);
            ItemStack s0 = (ItemStack) getStackInSlot.invoke(inputInv, 0);
            ItemStack s1 = (ItemStack) getStackInSlot.invoke(inputInv, 1);
            ItemStack s2 = (ItemStack) getStackInSlot.invoke(inputInv, 2);

            Class<?> inputCls = Class.forName("io.github.lounode.ae2cs.common.recipe.input.ThreeItemStackRecipeInput");
            Method of = inputCls.getMethod("of", ItemStack.class, ItemStack.class, ItemStack.class);
            Object input = of.invoke(null, s0, s1, s2);

            Method findMatch = recipe.getClass().getMethod("findMatch", inputCls);
            return (int[]) findMatch.invoke(recipe, input);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static ItemStack ae2oc_invokeAssemble(Object recipe, Object input, Object registryAccess) {
        try {
            for (Method method : recipe.getClass().getMethods()) {
                if (!"assemble".equals(method.getName()) || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] p = method.getParameterTypes();
                if (!p[0].isInstance(input)) {
                    continue;
                }
                if (!p[1].isInstance(registryAccess)) {
                    continue;
                }
                Object result = method.invoke(recipe, input, registryAccess);
                if (result instanceof ItemStack stack) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @Unique
    private double ae2oc_getAvailableEnergy() {
        try {
            Method extract = this.getClass().getMethod("extractAEPower", double.class, Actionable.class);
            Object v = extract.invoke(this, Double.MAX_VALUE, Actionable.SIMULATE);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    /**
     * 将产物直接输出到 ME 网络存储。
     * AE2CS 机器继承自 AENetworkBlockEntity，可通过 getMainNode() 获取 IGridNode。
     */
    @Unique
    private boolean ae2oc_tryOutputToMENetwork(ItemStack outputStack) {
        try {
            // 获取 IGridNode：getMainNode().getNode()
            Object mainNode = ae2oc_invokeNoArg(this, "getMainNode");
            if (mainNode == null) return false;
            Object gridNodeObj = ae2oc_invokeNoArg(mainNode, "getNode");
            if (!(gridNodeObj instanceof IGridNode gridNode)) return false;

            var grid = gridNode.getGrid();
            if (grid == null) return false;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return false;

            AEItemKey key = AEItemKey.of(outputStack);
            if (key == null) return false;

            // 先模拟
            long accepted = storageService.getInventory().insert(key, outputStack.getCount(), Actionable.SIMULATE, IActionSource.empty());
            if (accepted < outputStack.getCount()) return false;

            // 实际插入
            storageService.getInventory().insert(key, outputStack.getCount(), Actionable.MODULATE, IActionSource.empty());
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 将本地输出槽中的物品转移到 ME 网络（清空输出槽释放空间）。
     * 仅对 CircuitEtcher、CrystalPulverizer、CrystalAggregator 生效。
     * 熵变反应器的输出本身就是 ME 存储，无需额外处理。
     */
    @Unique
    private void ae2oc_flushOutputToMENetwork() {
        try {
            String machine = this.getClass().getName();
            if (machine.endsWith("EntropyVariationReactionChamberBlockEntity")) {
                return; // 熵变反应器已通过 MEStorage 输出
            }

            Object outputInv = ae2oc_invokeNoArg(this, "getOutputInv");
            if (outputInv == null) return;

            Object mainNode = ae2oc_invokeNoArg(this, "getMainNode");
            if (mainNode == null) return;
            Object gridNodeObj = ae2oc_invokeNoArg(mainNode, "getNode");
            if (!(gridNodeObj instanceof IGridNode gridNode)) return;

            var grid = gridNode.getGrid();
            if (grid == null) return;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) return;

            // CircuitEtcher/CrystalAggregator 使用 getStackInSlot + setItemDirect
            if (machine.endsWith("CircuitEtcherBlockEntity") || machine.endsWith("CrystalAggregatorBlockEntity")) {
                Method getStackInSlot = outputInv.getClass().getMethod("getStackInSlot", int.class);
                ItemStack stack = (ItemStack) getStackInSlot.invoke(outputInv, 0);
                if (stack.isEmpty()) return;

                AEItemKey key = AEItemKey.of(stack);
                if (key == null) return;

                long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, IActionSource.empty());
                if (inserted >= stack.getCount()) {
                    Method setItemDirect = outputInv.getClass().getMethod("setItemDirect", int.class, ItemStack.class);
                    setItemDirect.invoke(outputInv, 0, ItemStack.EMPTY);
                } else if (inserted > 0) {
                    stack.shrink((int) inserted);
                }
            }

            // CrystalPulverizer 使用 addItems，输出槽也是 AppEngInternalInventory
            if (machine.endsWith("CrystalPulverizerBlockEntity")) {
                Method getStackInSlot = outputInv.getClass().getMethod("getStackInSlot", int.class);
                ItemStack stack = (ItemStack) getStackInSlot.invoke(outputInv, 0);
                if (stack.isEmpty()) return;

                AEItemKey key = AEItemKey.of(stack);
                if (key == null) return;

                long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, IActionSource.empty());
                if (inserted >= stack.getCount()) {
                    Method setItemDirect = outputInv.getClass().getMethod("setItemDirect", int.class, ItemStack.class);
                    setItemDirect.invoke(outputInv, 0, ItemStack.EMPTY);
                } else if (inserted > 0) {
                    stack.shrink((int) inserted);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private boolean ae2oc_tryConsumeEnergy(double amount) {
        try {
            Method extract = this.getClass().getMethod("extractAEPower", double.class, Actionable.class);
            Object v = extract.invoke(this, amount, Actionable.MODULATE);
            if (v instanceof Number n) {
                return n.doubleValue() >= amount - 0.01;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Unique
    private static Object ae2oc_getLevel(Object target) {
        // 尝试方法调用（MCP 名称）
        Object level = ae2oc_invokeNoArg(target, "getLevel");
        if (level != null) return level;
        // 尝试字段访问（MCP 名称）
        level = ae2oc_getFieldRecursive(target, "level");
        if (level != null) return level;
        // 尝试 SRG 字段名（BlockEntity.level -> f_58857_）
        level = ae2oc_getFieldRecursive(target, "f_58857_");
        if (level != null) return level;
        // 尝试 SRG 方法名（BlockEntity.getLevel -> m_58904_）
        level = ae2oc_invokeNoArg(target, "m_58904_");
        return level;
    }

    @Unique
    private static Object ae2oc_invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static Object ae2oc_getFieldRecursive(Object target, String fieldName) {
        if (target == null) {
            return null;
        }

        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    @Unique
    private static Integer ae2oc_getIntField(Object target, String fieldName) {
        Object value = ae2oc_getFieldRecursive(target, fieldName);
        if (value instanceof Integer i) {
            return i;
        }
        return null;
    }

    @Unique
    private static Object ae2oc_getRegistryAccess(Object level) {
        // 尝试 MCP -> SRG 方法名
        Object ra = ae2oc_invokeNoArg(level, "registryAccess");
        if (ra != null) return ra;
        ra = ae2oc_invokeNoArg(level, "m_9598_");
        return ra;
    }
}