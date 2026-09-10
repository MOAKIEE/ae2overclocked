package moakiee.ae2oc.core.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import moakiee.ae2oc.api.ResourceAmount;

/** Server-thread processor. Resource ports must return the exact amount actually transferred. */
public final class BatchProcessor<K> {
    private ProcessingState<K> state;

    public ProcessingState<K> snapshot() { return state; }
    public void restore(ProcessingState<K> saved) {
        if (state != null) throw new IllegalStateException("Cannot overwrite an owned batch");
        state = saved;
    }

    /** Call only after the adapter has moved exact inputs into the batch's ownership. */
    public void begin(ProcessingState<K> reserved) {
        if (state != null) throw new IllegalStateException("A batch is already owned");
        state = reserved;
    }

    public boolean advance(ToDoubleFunction<Double> extractEnergy) {
        if (state == null || state.finished()) return false;
        double remaining = state.energyRequired() - state.energyPaid();
        double paid = remaining == 0 ? 0 : extractEnergy.applyAsDouble(remaining);
        if (!Double.isFinite(paid) || paid < 0 || paid > remaining) {
            throw new IllegalStateException("Energy port violated its exact transfer contract");
        }
        double total = Math.min(state.energyRequired(), state.energyPaid() + paid);
        int ticks = state.ticksRemaining();
        if (total == state.energyRequired() && ticks > 0) ticks--;
        boolean progress = paid > 0 || ticks != state.ticksRemaining();
        state = new ProcessingState<>(state.recipe(), state.inputs(), state.outputs(),
                state.energyRequired(), total, ticks);
        return progress;
    }

    public boolean drain(long amountBudget, int keyBudget, ToLongFunction<ResourceAmount<K>> insert) {
        if (amountBudget < 0 || keyBudget < 0) throw new IllegalArgumentException("Negative transfer budget");
        if (state == null || !state.finished()) return false;
        boolean progress = false;
        // Publish each successful transfer before attempting the next external port call.
        int calls = 0;
        while (state != null && !state.outputs().isEmpty() && amountBudget > 0 && calls < keyBudget) {
            var first = state.outputs().get(0);
            long requested = Math.min(first.amount(), amountBudget);
            long accepted = insert.applyAsLong(new ResourceAmount<>(first.key(), requested));
            if (accepted < 0 || accepted > requested) throw new IllegalStateException("Invalid accepted amount");
            calls++;
            if (accepted == 0) break;
            List<ResourceAmount<K>> pending = new ArrayList<>(state.outputs());
            if (accepted == first.amount()) pending.remove(0);
            else pending.set(0, new ResourceAmount<>(first.key(), first.amount() - accepted));
            state = new ProcessingState<>(state.recipe(), List.of(), pending,
                    state.energyRequired(), state.energyPaid(), 0);
            amountBudget -= accepted;
            progress = true;
        }
        if (state != null && state.outputs().isEmpty()) state = null;
        return progress;
    }

    /** Resources to preserve when a machine is dismantled. */
    public List<ResourceAmount<K>> ownedResources() {
        return state == null ? List.of() : state.finished() ? state.outputs() : state.inputs();
    }
}
