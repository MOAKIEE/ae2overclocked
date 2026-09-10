package moakiee.ae2oc.compat.ae2cs;

import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEKey;
import io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;

public final class EntropyAdapter extends MachineProcessorAdapter {
    private final EntropyVariationReactionChamberBlockEntity host;
    public EntropyAdapter(EntropyVariationReactionChamberBlockEntity host) {
        super(host, host, InternalInventory.empty(), new EntropyRecipes(host), 1, 0);
        this.host = host;
    }
    @Override protected long insertLocalOutput(ResourceAmount<AEKey> resource) {
        var output = host.getOutputInv();
        for (int slot = 0; slot < output.size(); slot++) {
            long accepted = output.insert(slot, resource.key(), resource.amount(), Actionable.MODULATE);
            if (accepted > 0) return accepted;
        }
        return 0;
    }
}
