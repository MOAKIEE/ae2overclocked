package moakiee.ae2oc.core.execution;

import java.util.List;
import java.util.Objects;
import moakiee.ae2oc.api.ResourceAmount;

/** Immutable save boundary. Reserved inputs remain owned until paid processing completes. */
public record ProcessingState<K>(String recipe, List<ResourceAmount<K>> inputs,
        List<ResourceAmount<K>> outputs, double energyRequired, double energyPaid, int ticksRemaining) {
    public ProcessingState {
        Objects.requireNonNull(recipe);
        inputs = List.copyOf(inputs);
        // Empty recipe entries own no resources and must not block later outputs.
        outputs = List.copyOf(outputs).stream().filter(resource -> resource.amount() > 0).toList();
        if (!Double.isFinite(energyRequired) || energyRequired < 0 || !Double.isFinite(energyPaid)
                || energyPaid < 0 || energyPaid > energyRequired || ticksRemaining < 0) {
            throw new IllegalArgumentException("Invalid persisted processing state");
        }
    }

    public boolean finished() { return energyPaid == energyRequired && ticksRemaining == 0; }

    /** Upstream bars measure paid energy. A paid batch stays full during its remaining delay. */
    public int paidProgress(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Negative progress scale");
        return energyRequired == 0 ? maximum : (int) Math.round((energyPaid / energyRequired) * maximum);
    }
}
