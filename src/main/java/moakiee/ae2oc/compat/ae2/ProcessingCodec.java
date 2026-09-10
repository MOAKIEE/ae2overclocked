package moakiee.ae2oc.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import appeng.api.stacks.AEKey;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class ProcessingCodec {
    private ProcessingCodec() {}

    public static CompoundTag write(ProcessingState<AEKey> state) {
        var tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.putString("recipe", state.recipe());
        tag.put("inputs", writeResources(state.inputs()));
        tag.put("outputs", writeResources(state.outputs()));
        tag.putDouble("energyRequired", state.energyRequired());
        tag.putDouble("energyPaid", state.energyPaid());
        tag.putInt("ticksRemaining", state.ticksRemaining());
        return tag;
    }

    public static ProcessingState<AEKey> read(CompoundTag tag) {
        if (tag.getInt("dataVersion") != 1) throw new IllegalArgumentException("Unsupported processing data version");
        return new ProcessingState<>(tag.getString("recipe"), readResources(tag.getList("inputs", Tag.TAG_COMPOUND)),
                readResources(tag.getList("outputs", Tag.TAG_COMPOUND)), tag.getDouble("energyRequired"),
                tag.getDouble("energyPaid"), tag.getInt("ticksRemaining"));
    }

    private static ListTag writeResources(List<ResourceAmount<AEKey>> resources) {
        var list = new ListTag();
        for (var resource : resources) {
            var tag = new CompoundTag();
            tag.put("key", resource.key().toTagGeneric());
            tag.putLong("amount", resource.amount());
            list.add(tag);
        }
        return list;
    }

    private static List<ResourceAmount<AEKey>> readResources(ListTag list) {
        if (list.size() > 256) throw new IllegalArgumentException("Oversized resource ledger");
        List<ResourceAmount<AEKey>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            var tag = list.getCompound(i);
            var key = AEKey.fromTagGeneric(tag.getCompound("key"));
            if (key == null) throw new IllegalArgumentException("Unknown saved resource; refusing to discard batch");
            result.add(new ResourceAmount<>(key, tag.getLong("amount")));
        }
        return result;
    }
}
