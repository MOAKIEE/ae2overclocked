package moakiee.mixin;

import com.glodblock.github.extendedae.common.me.InscriberThread;
import moakiee.ae2oc.compat.extendedae.InscriberThreadState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileExInscriber", remap = false)
public abstract class MixinExInscriberPersistence {
    @Shadow @Final private InscriberThread[] threads;

    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("TAIL"), require = 1)
    private void ae2oc_save(CompoundTag tag, CallbackInfo ci) {
        for (int i = 0; i < threads.length; i++) {
            var child = new CompoundTag();
            ((InscriberThreadState) threads[i]).ae2oc$getAdapter().save(child);
            tag.put("ae2ocThread" + i, child);
        }
    }

    @Inject(method = "loadTag", at = @At("TAIL"))
    private void ae2oc_load(CompoundTag tag, CallbackInfo ci) {
        for (int i = 0; i < threads.length; i++) {
            ((InscriberThreadState) threads[i]).ae2oc$getAdapter().load(tag.getCompound("ae2ocThread" + i));
        }
    }

    @Inject(method = "addAdditionalDrops", at = @At("TAIL"))
    private void ae2oc_drops(Level level, BlockPos pos, List<ItemStack> drops, CallbackInfo ci) {
        for (var thread : threads) ((InscriberThreadState) thread).ae2oc$getAdapter().addDrops(drops);
    }
}
