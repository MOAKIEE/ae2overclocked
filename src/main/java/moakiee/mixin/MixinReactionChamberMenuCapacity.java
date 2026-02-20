package moakiee.mixin;

import appeng.helpers.externalstorage.GenericStackInv;
import moakiee.support.CapacityCardRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.gui.ReactionChamberMenu", remap = false)
public class MixinReactionChamberMenuCapacity {

    private static final int AE2OC_DEFAULT_BUCKETS = 16;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ae2oc_afterCtor(CallbackInfo ci) {
        Object host = ae2oc_tryInvokeNoArg(this, "getHost");
        ae2oc_syncFluidDisplay(host);
    }

    @Inject(method = "standardDetectAndSendChanges", at = @At("TAIL"))
    private void ae2oc_afterDetectAndSync(CallbackInfo ci) {
        Object host = ae2oc_tryInvokeNoArg(this, "getHost");
        ae2oc_syncFluidDisplay(host);
    }

    private void ae2oc_syncFluidDisplay(Object host) {
        int buckets = AE2OC_DEFAULT_BUCKETS;
        Object tankObj = ae2oc_tryInvokeNoArg(host, "getTank");
        if (tankObj instanceof GenericStackInv tank) {
            long cap = tank.getCapacity(appeng.api.stacks.AEKeyType.fluids());
            buckets = cap >= 1000 ? (int) Math.min((long) Integer.MAX_VALUE, cap / 1000L) : AE2OC_DEFAULT_BUCKETS;
        } else if (CapacityCardRuntime.getInstalledCapacityCards(host) > 0) {
            buckets = Integer.MAX_VALUE / 1000;
        }

        ae2oc_setIntField(this, "INPUT_FLUID_SIZE", buckets);
        ae2oc_setIntField(this, "OUTPUT_FLUID_SIZE", buckets);
    }

    private static Object ae2oc_tryInvokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            var method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ae2oc_setIntField(Object target, String fieldName, int value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (Throwable ignored) {
        }
    }
}