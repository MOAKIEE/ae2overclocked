package moakiee.ae2oc.compat.ae2;

import java.util.List;
import appeng.api.stacks.AEKey;
import moakiee.ae2oc.api.ResourceAmount;

/** One recipe operation, including exact slot identities, catalysts and all outputs. */
public record RecipeBatch(String id, List<Debit> inputs, List<ResourceAmount<AEKey>> outputs,
                          double unitEnergy, int normalTicks) {
    public RecipeBatch {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        if (!Double.isFinite(unitEnergy) || unitEnergy < 0 || normalTicks < 1) throw new IllegalArgumentException();
    }

    public record Debit(LocalResourceSlot slot, ResourceAmount<AEKey> before, long consumed) {
        public Debit {
            if (before == null || consumed < 0 || consumed > before.amount()) throw new IllegalArgumentException("Invalid recipe debit");
        }
    }
}
