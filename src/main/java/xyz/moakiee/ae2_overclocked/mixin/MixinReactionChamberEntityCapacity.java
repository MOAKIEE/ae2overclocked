package xyz.moakiee.ae2_overclocked.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public class MixinReactionChamberEntityCapacity {

    private static final long AE2OC_DEFAULT_FLUID_CAPACITY = 16_000L;
    private static final long AE2OC_MAX_FLUID_CAPACITY = Integer.MAX_VALUE;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void ae2oc_afterCtor(BlockEntityType<?> type, BlockPos pos, BlockState state, CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }

    @Inject(method = "loadTag", at = @At("TAIL"), require = 0)
    private void ae2oc_afterLoadTag(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }

    @Inject(method = "onChangeInventory", at = @At("TAIL"), require = 0)
    private void ae2oc_onUpgradeChange(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }

    @Inject(method = "onChangeTank", at = @At("HEAD"), require = 0)
    private void ae2oc_onTankChange(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, AE2OC_MAX_FLUID_CAPACITY);
    }
}
