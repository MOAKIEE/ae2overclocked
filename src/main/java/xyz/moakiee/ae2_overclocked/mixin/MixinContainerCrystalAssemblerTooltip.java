package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.core.localization.Tooltips;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.menu.slot.AppEngSlot;
import com.glodblock.github.extendedae.common.tileentities.TileCrystalAssembler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.container.ContainerCrystalAssembler", remap = false)
public class MixinContainerCrystalAssemblerTooltip {

    @Unique
    private static final int AE2OC_DEFAULT_TANK_MB = 16_000;

    @Shadow
    @Final
    private AppEngSlot tank;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void ae2oc_afterCtor(int containerId, Inventory inventory, TileCrystalAssembler host, CallbackInfo ci) {
        this.tank.setEmptyTooltip(this::ae2oc_buildTankTooltip);
    }

    @Unique
    private List<Component> ae2oc_buildTankTooltip() {
        return List.of(
                Component.translatable("gui.extendedae.crystal_assembler.tank_empty"),
                Component.translatable("gui.extendedae.crystal_assembler.amount", 0, ae2oc_getTankCapacityMb())
                        .withStyle(Tooltips.NORMAL_TOOLTIP_TEXT)
        );
    }

    @Unique
    private int ae2oc_getTankCapacityMb() {
        try {
            Object host = ae2oc_invokeNoArg(this, "getHost");
            if (host == null) {
                return AE2OC_DEFAULT_TANK_MB;
            }

            Object tankObj = ae2oc_invokeNoArg(host, "getTank");
            if (tankObj instanceof GenericStackInv tankInv) {
                long capacity = tankInv.getCapacity(AEKeyType.fluids());
                if (capacity > 0) {
                    return (int) Math.min(capacity, Integer.MAX_VALUE);
                }
            }
        } catch (Exception ignored) {
        }
        return AE2OC_DEFAULT_TANK_MB;
    }

    @Unique
    private static Object ae2oc_invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
