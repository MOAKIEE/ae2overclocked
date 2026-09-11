package moakiee.ae2oc.core;

import java.util.List;
import moakiee.ae2oc.core.execution.SlotTransaction;

final class SlotTransactionTest {
    static void run() {
        // Inject failures both before and after mutation, at every position.
        for (int position = 0; position < 3; position++) {
            for (boolean mutateFirst : new boolean[] {false, true}) {
                int[] slots = {10, 20, 30};
                int failing = position;
                var failure = new IllegalStateException("reserve");
                var writes = new java.util.ArrayList<SlotTransaction.Write<Integer>>();
                for (int index = 0; index < slots.length; index++) {
                    int slot = index;
                    writes.add(new SlotTransaction.Write<>(value -> {
                        if (slot == failing && value == 0) {
                            if (mutateFirst) slots[slot] = value;
                            throw failure;
                        }
                        slots[slot] = value;
                    }, slots[slot], 0));
                }
                try {
                    SlotTransaction.apply(writes);
                    throw new AssertionError("Expected reservation failure");
                } catch (IllegalStateException actual) {
                    check(actual == failure);
                    check(java.util.Arrays.equals(slots, new int[] {10, 20, 30}));
                }
            }
        }
        int[] slots = {10, 20};
        var failure = new IllegalStateException("reserve");
        var rollback = new IllegalStateException("rollback");
        try {
            SlotTransaction.apply(List.of(
                    new SlotTransaction.Write<Integer>(value -> slots[0] = value, 10, 0),
                    new SlotTransaction.Write<Integer>(value -> {
                        slots[1] = value;
                        throw value == 0 ? failure : rollback;
                    }, 20, 0),
                    new SlotTransaction.Write<Integer>(value -> {
                        throw new AssertionError("Unattempted slot must not be written");
                    }, 30, 0)));
            throw new AssertionError("Expected reservation failure");
        } catch (IllegalStateException actual) {
            check(actual == failure && actual.getSuppressed().length == 1);
            check(actual.getSuppressed()[0] == rollback);
            check(slots[0] == 10 && slots[1] == 20);
        }
        SlotTransaction.apply(List.of(new SlotTransaction.Write<Integer>(value -> slots[0] = value, 10, 4)));
        check(slots[0] == 4);
        // A callback may reuse its exception; avoid self-suppression interrupting recovery.
        try {
            SlotTransaction.apply(List.of(new SlotTransaction.Write<Integer>(value -> { throw failure; }, 4, 0)));
            throw new AssertionError("Expected reused failure");
        } catch (IllegalStateException actual) {
            check(actual == failure);
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Slot transaction contract failed");
    }
}
