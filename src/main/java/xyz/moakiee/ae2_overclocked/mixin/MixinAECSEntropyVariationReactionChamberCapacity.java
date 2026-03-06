package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;
import xyz.moakiee.ae2_overclocked.support.OverstackingRegistry;

import java.lang.reflect.Method;

/**
 * Applies Capacity Card support to AE2CS EntropyVariationReactionChamberBlockEntity.
 * <p>
 * This machine's input (1 slot) and output (4 slots) are GenericStackInv (ConfigInventory)
 * that can hold both items and fluids. The default capacities are 64 for items and
 * 16000 mB for fluids (via useRegisteredCapacities()). When a Capacity Card is installed,
 * this mixin increases both item and fluid capacities and registers the inventories with
 * OverstackingRegistry so that MixinGenericStackInv / MixinConfigInventory will bypass
 * the stacking limits.
 */
@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity",
        remap = false)
public class MixinAECSEntropyVariationReactionChamberCapacity {

    @Unique
    private long ae2oc_defaultInputItemCapacity = 64L;
    @Unique
    private long ae2oc_defaultInputFluidCapacity = 16_000L;
    @Unique
    private long ae2oc_defaultOutputItemCapacity = 64L;
    @Unique
    private long ae2oc_defaultOutputFluidCapacity = 16_000L;
    @Unique
    private boolean ae2oc_defaultsCaptured = false;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void ae2oc_afterCtor(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState, CallbackInfo ci) {
        ae2oc_applyCapacity();
    }

    @Inject(method = "loadTag", at = @At("TAIL"), require = 0)
    private void ae2oc_afterLoadTag(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        ae2oc_applyCapacity();
    }

    @Inject(method = "serverTick", at = @At("HEAD"), require = 0)
    private void ae2oc_beforeServerTick(CallbackInfo ci) {
        ae2oc_applyCapacity();
    }

    @Unique
    private void ae2oc_applyCapacity() {
        Object inputObj = ae2oc_tryInvokeNoArg(this, "getInputInv");
        Object outputObj = ae2oc_tryInvokeNoArg(this, "getOutputInv");
        if (!(inputObj instanceof GenericStackInv inputInv) || !(outputObj instanceof GenericStackInv outputInv)) {
            return;
        }

        if (!this.ae2oc_defaultsCaptured) {
            this.ae2oc_defaultInputItemCapacity = ae2oc_tryGetCapacity(inputInv, AEKeyType.items(), this.ae2oc_defaultInputItemCapacity);
            this.ae2oc_defaultInputFluidCapacity = ae2oc_tryGetCapacity(inputInv, AEKeyType.fluids(), this.ae2oc_defaultInputFluidCapacity);
            this.ae2oc_defaultOutputItemCapacity = ae2oc_tryGetCapacity(outputInv, AEKeyType.items(), this.ae2oc_defaultOutputItemCapacity);
            this.ae2oc_defaultOutputFluidCapacity = ae2oc_tryGetCapacity(outputInv, AEKeyType.fluids(), this.ae2oc_defaultOutputFluidCapacity);
            this.ae2oc_defaultsCaptured = true;
        }

        boolean hasCard = CapacityCardRuntime.getInstalledCapacityCards(this) > 0;

        // Always update the registry state
        if (hasCard) {
            OverstackingRegistry.register(inputInv);
            OverstackingRegistry.register(outputInv);
        } else {
            OverstackingRegistry.unregister(inputInv);
            OverstackingRegistry.unregister(outputInv);
        }

        long targetItem = hasCard ? Ae2OcConfig.getCapacityCardSlotLimit() : this.ae2oc_defaultInputItemCapacity;
        long targetInputFluid = hasCard ? Integer.MAX_VALUE : this.ae2oc_defaultInputFluidCapacity;
        long targetOutputFluid = hasCard ? Integer.MAX_VALUE : this.ae2oc_defaultOutputFluidCapacity;

        inputInv.setCapacity(AEKeyType.items(), targetItem);
        inputInv.setCapacity(AEKeyType.fluids(), targetInputFluid);

        outputInv.setCapacity(AEKeyType.items(), hasCard ? Ae2OcConfig.getCapacityCardSlotLimit() : this.ae2oc_defaultOutputItemCapacity);
        outputInv.setCapacity(AEKeyType.fluids(), targetOutputFluid);
    }

    @Unique
    private static long ae2oc_tryGetCapacity(GenericStackInv inv, AEKeyType keyType, long fallback) {
        try {
            Method method = inv.getClass().getMethod("getCapacity", AEKeyType.class);
            Object result = method.invoke(inv, keyType);
            if (result instanceof Long value) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    @Unique
    private static Object ae2oc_tryInvokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
