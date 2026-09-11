package moakiee.ae2oc.core.execution;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Best-effort rollback for absolute local slot writes on the server thread. */
public final class SlotTransaction {
    private SlotTransaction() {}

    public record Write<T>(Consumer<T> setter, T before, T after) {
        public Write { Objects.requireNonNull(setter); }
    }

    /**
     * Setters must replace the complete slot value, including when a previous callback failed.
     * A setter may mutate before throwing, so the failing write is also rolled back.
     * Rollback failures are retained on the original exception; every attempted slot is restored
     * independently. Irreversible callback side effects cannot be recovered by this contract.
     */
    public static <T> void apply(List<Write<T>> writes) {
        var changes = List.copyOf(writes);
        int attempted = 0;
        try {
            for (var change : changes) {
                attempted++;
                change.setter().accept(change.after());
            }
        } catch (RuntimeException failure) {
            for (int i = attempted - 1; i >= 0; i--) {
                var change = changes.get(i);
                try {
                    change.setter().accept(change.before());
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }
}
