package moakiee.ae2oc.compat.ae2cs;

import appeng.blockentity.AEBaseBlockEntity;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;

/**
 * Restores the upstream ACTIVE blockstate and menu progress sync on ticks the shared processor owns.
 * Upstream serverTick bodies maintain both every tick; cancelling them without a replacement leaves
 * the block light stale and the GUI progress bar frozen whenever an upgrade card drives processing.
 *
 * <p>Progress maps the batch energy payment ratio onto a fixed 0..1000 scale, matching the upstream
 * bar semantics (charged energy toward recipe energy cost). The powered flag must be captured before
 * the adapter runs, because the adapter may drain the internal buffer inside the same tick.
 */
public interface AE2CSStateSync {
    void ae2oc$syncState(boolean powered, int progress, int progressMax);

    int ae2oc$progress();

    int ae2oc$progressMax();

    static void sync(AEBaseBlockEntity host, boolean powered, MachineProcessorAdapter adapter) {
        if (!(host instanceof AE2CSStateSync target)) return;
        var state = adapter.processing();
        if (state == null) {
            target.ae2oc$syncState(powered, 0, 0);
            return;
        }
        target.ae2oc$syncState(powered, state.paidProgress(1000), 1000);
    }
}
