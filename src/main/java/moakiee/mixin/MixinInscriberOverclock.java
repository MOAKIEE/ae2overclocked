package moakiee.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import moakiee.support.OverclockCardRuntime;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 超频卡功能注入 - AE2 原版压印器
 * 
 * 功能：安装超频卡后，机器配方在1 tick内瞬间完成
 * 
 * 实现原理：
 * 1. 在 tickingRequest HEAD 注入，先于原版所有逻辑
 * 2. 检测是否安装超频卡
 * 3. 如果有配方，检查能量和输出空间
 * 4. 满足条件时直接完成配方，跳过进度条
 * 5. 取消原版 tick 逻辑
 * 
 * 安全机制：
 * - 能量不足时降级为原版速度
 * - 输出槽满时不执行（避免虚空吞噬物品）
 * - 配方缓存由原版处理，无需额外实现
 */
@Mixin(InscriberBlockEntity.class)
public abstract class MixinInscriberOverclock {

    /**
     * 压印器配方总能量消耗
     * 原版每tick消耗 10*speedFactor AE, 共需约 200/speedFactor ticks
     * 总计约 2000 AE
     */
    @Unique
    private static final double AE2OC_INSCRIBER_RECIPE_ENERGY = 2000.0;

    // ========== Shadow 访问原版字段和方法 ==========

    @Shadow
    private boolean smash;

    @Shadow
    private int finalStep;

    @Shadow
    public abstract InscriberRecipe getTask();

    @Shadow
    public abstract int getMaxProcessingTime();

    @Shadow
    public abstract int getProcessingTime();

    @Shadow
    protected abstract void setSmash(boolean smash);

    // 需要通过反射访问私有方法和字段
    // sideItemHandler, topItemHandler, bottomItemHandler 是私有的

    /**
     * 在 tickingRequest 方法头部注入超频逻辑
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2oc_overclockTick(IGridNode node, int ticksSinceLastCall, 
                                      CallbackInfoReturnable<TickRateModulation> cir) {
        InscriberBlockEntity self = (InscriberBlockEntity) (Object) this;

        // 检测是否安装超频卡
        if (!OverclockCardRuntime.hasOverclockCard(self)) {
            return; // 没有超频卡，执行原版逻辑
        }

        // 如果正在播放动画，使用加速动画逻辑
        if (this.smash) {
            // 超频模式下加速动画（但仍然需要几tick来显示效果）
            this.finalStep += 4; // 加速4倍
            if (this.finalStep >= 8 && this.finalStep < 16) {
                // 执行产物输出
                ae2oc_finishCraft(self);
                this.finalStep = 16; // 跳到动画结束
            }
            if (this.finalStep >= 16) {
                this.finalStep = 0;
                this.setSmash(false);
                ae2oc_markForUpdate(self);
            }
            cir.setReturnValue(TickRateModulation.URGENT);
            return;
        }

        // 获取当前配方
        InscriberRecipe recipe = this.getTask();
        if (recipe == null) {
            return; // 没有配方，执行原版逻辑
        }

        // 检查输出槽是否有空间（模拟插入）
        ItemStack outputCopy = recipe.getResultItem().copy();
        if (!ae2oc_canInsertOutput(self, outputCopy)) {
            return; // 输出槽满，执行原版逻辑（等待空间）
        }

        // 检查并消耗能量
        if (!ae2oc_tryConsumePower(self, node, AE2OC_INSCRIBER_RECIPE_ENERGY)) {
            return; // 能量不足，执行原版逻辑（渐进充能）
        }

        // === 所有条件满足，瞬间完成配方 ===
        
        // 触发完成动画
        this.setSmash(true);
        this.finalStep = 0;
        ae2oc_markForUpdate(self);

        cir.setReturnValue(TickRateModulation.URGENT);
    }

    /**
     * 完成配方制作（消耗材料和产出物品）
     */
    @Unique
    private void ae2oc_finishCraft(InscriberBlockEntity self) {
        InscriberRecipe recipe = this.getTask();
        if (recipe == null) {
            return;
        }

        ItemStack outputCopy = recipe.getResultItem().copy();

        // 使用反射访问私有的 inventory handlers
        try {
            // 获取 sideItemHandler
            java.lang.reflect.Field sideField = InscriberBlockEntity.class.getDeclaredField("sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);
            
            // 获取 topItemHandler
            java.lang.reflect.Field topField = InscriberBlockEntity.class.getDeclaredField("topItemHandler");
            topField.setAccessible(true);
            Object topHandler = topField.get(self);
            
            // 获取 bottomItemHandler
            java.lang.reflect.Field bottomField = InscriberBlockEntity.class.getDeclaredField("bottomItemHandler");
            bottomField.setAccessible(true);
            Object bottomHandler = bottomField.get(self);

            // 调用 insertItem(1, outputCopy, false) 插入产物
            java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem", 
                    int.class, ItemStack.class, boolean.class);
            ItemStack remaining = (ItemStack) insertMethod.invoke(sideHandler, 1, outputCopy, false);
            
            if (remaining.isEmpty()) {
                // 成功插入，设置进度为0
                java.lang.reflect.Method setProcessingTime = InscriberBlockEntity.class.getDeclaredMethod(
                        "setProcessingTime", int.class);
                setProcessingTime.setAccessible(true);
                setProcessingTime.invoke(self, 0);

                // 消耗模板（如果是 PRESS 类型）
                if (recipe.getProcessType() == InscriberProcessType.PRESS) {
                    java.lang.reflect.Method extractTop = topHandler.getClass().getMethod("extractItem", 
                            int.class, int.class, boolean.class);
                    extractTop.invoke(topHandler, 0, 1, false);
                    
                    java.lang.reflect.Method extractBottom = bottomHandler.getClass().getMethod("extractItem", 
                            int.class, int.class, boolean.class);
                    extractBottom.invoke(bottomHandler, 0, 1, false);
                }

                // 消耗输入材料
                java.lang.reflect.Method extractSide = sideHandler.getClass().getMethod("extractItem", 
                        int.class, int.class, boolean.class);
                extractSide.invoke(sideHandler, 0, 1, false);
            }
        } catch (Exception e) {
            // 反射失败，记录错误但不崩溃
            e.printStackTrace();
        }

        ae2oc_saveChanges(self);
    }

    /**
     * 调用父类的 markForUpdate()
     */
    @Unique
    private void ae2oc_markForUpdate(InscriberBlockEntity self) {
        try {
            java.lang.reflect.Method method = self.getClass().getMethod("markForUpdate");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    /**
     * 调用父类的 saveChanges()
     */
    @Unique
    private void ae2oc_saveChanges(InscriberBlockEntity self) {
        try {
            java.lang.reflect.Method method = self.getClass().getMethod("saveChanges");
            method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    /**
     * 检查输出槽是否有足够空间（模拟插入）
     */
    @Unique
    private boolean ae2oc_canInsertOutput(InscriberBlockEntity self, ItemStack output) {
        try {
            java.lang.reflect.Field sideField = InscriberBlockEntity.class.getDeclaredField("sideItemHandler");
            sideField.setAccessible(true);
            Object sideHandler = sideField.get(self);
            
            java.lang.reflect.Method insertMethod = sideHandler.getClass().getMethod("insertItem", 
                    int.class, ItemStack.class, boolean.class);
            ItemStack remaining = (ItemStack) insertMethod.invoke(sideHandler, 1, output, true); // simulate=true
            
            return remaining.isEmpty();
        } catch (Exception e) {
            return false; // 反射失败，保守处理
        }
    }

    /**
     * 尝试消耗配方所需的全部能量
     * 
     * @return 如果能量足够并成功消耗则返回 true
     */
    @Unique
    private boolean ae2oc_tryConsumePower(InscriberBlockEntity self, IGridNode node, double powerNeeded) {
        // 首先尝试从机器内部缓存提取
        double extracted = self.extractAEPower(powerNeeded, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        
        if (extracted >= powerNeeded - 0.01) {
            // 内部缓存足够
            self.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
            return true;
        }

        // 尝试从网络提取
        var grid = node.getGrid();
        if (grid != null) {
            IEnergyService energyService = grid.getEnergyService();
            double networkExtracted = energyService.extractAEPower(powerNeeded, Actionable.SIMULATE, 
                    PowerMultiplier.CONFIG);
            
            if (networkExtracted >= powerNeeded - 0.01) {
                // 网络能量足够
                energyService.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }
        }

        return false; // 能量不足
    }
}
