package moakiee.ae2oc.compat.ae2cs;

import java.util.function.Supplier;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;
import moakiee.ae2oc.compat.ae2.RecipeBatch;

/** Uses the pulverizer's complete four-slot output contract without exposing any input slots. */
public final class PulverizerAdapter extends MachineProcessorAdapter {
    private final InternalInventory output;

    public PulverizerAdapter(CrystalPulverizerBlockEntity host, Supplier<RecipeBatch> recipeSource) {
        super(host, host, host.getOutputInv(), recipeSource, 1, 0);
        this.output = host.getOutputInv();
    }

    @Override
    protected long insertLocalOutput(ResourceAmount<AEKey> resource) {
        if (!(resource.key() instanceof AEItemKey item)) return 0;
        int offered = (int) Math.min(resource.amount(), item.getMaxStackSize());
        return offered - output.addItems(item.toStack(offered), false).getCount();
    }
}
