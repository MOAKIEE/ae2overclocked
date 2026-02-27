package moakiee.mixin;

import appeng.util.inv.AppEngInternalInventory;
import moakiee.Ae2OcConfig;
import moakiee.support.CapacityCardRuntime;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = {
        "io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity",
        "io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity",
        "io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity"
}, remap = false)
public class MixinAE2CSItemMachinesCapacity {

    private static final int AE2OC_DEFAULT_ITEM_LIMIT = 64;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_afterCtor(CallbackInfo ci) {
        ae2oc_applyItemCapacity();
    }

    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_afterLoadTag(CompoundTag data, CallbackInfo ci) {
        ae2oc_applyItemCapacity();
    }

    @Inject(method = "serverTick", at = @At("HEAD"))
    private void ae2oc_beforeServerTick(CallbackInfo ci) {
        ae2oc_applyItemCapacity();
    }

    @Unique
    private void ae2oc_applyItemCapacity() {
        int targetLimit = CapacityCardRuntime.getInstalledCapacityCards(this) > 0
                ? Ae2OcConfig.getCapacityCardSlotLimit()
                : AE2OC_DEFAULT_ITEM_LIMIT;

        Object inputInv = ae2oc_tryInvokeNoArg(this, "getInputInv");
        Object outputInv = ae2oc_tryInvokeNoArg(this, "getOutputInv");

        ae2oc_applyInventoryLimit(inputInv, targetLimit);
        ae2oc_applyInventoryLimit(outputInv, targetLimit);
    }

    @Unique
    private static void ae2oc_applyInventoryLimit(Object invObj, int targetLimit) {
        if (!(invObj instanceof AppEngInternalInventory inv)) {
            return;
        }

        try {
            Method setMaxStackSize = inv.getClass().getMethod("setMaxStackSize", int.class, int.class);
            for (int i = 0; i < inv.size(); i++) {
                setMaxStackSize.invoke(inv, i, targetLimit);
            }
        } catch (Throwable ignored) {
        }
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
