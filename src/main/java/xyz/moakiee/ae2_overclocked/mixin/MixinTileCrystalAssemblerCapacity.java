package xyz.moakiee.ae2_overclocked.mixin;

import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.CapacityCardRuntime;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileCrystalAssembler", remap = false)
public class MixinTileCrystalAssemblerCapacity {

    private static final long AE2OC_DEFAULT_FLUID_CAPACITY = 16_000L;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void ae2oc_afterCtor(BlockPos pos, BlockState state, CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, Ae2OcConfig.getCapacityCardSlotLimit());
    }

    @Inject(method = "loadTag", at = @At("TAIL"), require = 0)
    private void ae2oc_afterLoadTag(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, Ae2OcConfig.getCapacityCardSlotLimit());
    }

    @Inject(
            method = "onChangeInventory(Lappeng/util/inv/AppEngInternalInventory;I)V",
            at = @At("TAIL"),
            require = 0
    )
    private void ae2oc_onUpgradeChange(AppEngInternalInventory inv, int slot, CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, Ae2OcConfig.getCapacityCardSlotLimit());
    }

    @Inject(method = "onChangeTank", at = @At("HEAD"), require = 0)
    private void ae2oc_onTankChange(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, Ae2OcConfig.getCapacityCardSlotLimit());
    }

    // Upgrade inventory callback goes through saveChanges(), not onChangeInventory().
    // Hook saveChanges to ensure fluid capacity is updated when capacity card is inserted/removed.
    @Inject(method = "saveChanges", at = @At("HEAD"), require = 0)
    private void ae2oc_onSaveChanges(CallbackInfo ci) {
        CapacityCardRuntime.applyFluidCapacity(this, AE2OC_DEFAULT_FLUID_CAPACITY, Ae2OcConfig.getCapacityCardSlotLimit());
    }
}
