package moakiee.ae2oc.core.quantity;

import java.util.Objects;

/** A single resource identity with lossless capacity reduction. */
public final class LogicalSlot<K> {
    private K key;
    private long amount;
    private long capacity;
    private long revision;

    public LogicalSlot(long capacity) { setCapacity(capacity); }
    public K key() { return key; }
    public long amount() { return amount; }
    public long capacity() { return capacity; }
    public long revision() { return revision; }
    public boolean overCapacity() { return amount > capacity; }

    public void setCapacity(long value) {
        SaturatedMath.requireNonNegative(value);
        if (capacity != value) { capacity = value; revision++; }
    }

    public long insert(K identity, long requested, boolean simulate) {
        Objects.requireNonNull(identity);
        SaturatedMath.requireNonNegative(requested);
        if (key != null && !key.equals(identity)) return 0;
        long accepted = Math.min(requested, amount >= capacity ? 0 : capacity - amount);
        if (!simulate && accepted > 0) { key = identity; amount += accepted; revision++; }
        return accepted;
    }

    public long extract(long requested, boolean simulate) {
        SaturatedMath.requireNonNegative(requested);
        long extracted = Math.min(amount, requested);
        if (!simulate && extracted > 0) {
            amount -= extracted;
            if (amount == 0) key = null;
            revision++;
        }
        return extracted;
    }

    /** Restoring data deliberately ignores current insertion capacity. */
    public void restore(K identity, long quantity) {
        SaturatedMath.requireNonNegative(quantity);
        if (quantity > 0) Objects.requireNonNull(identity);
        key = quantity == 0 ? null : identity;
        amount = quantity;
        revision++;
    }
}
