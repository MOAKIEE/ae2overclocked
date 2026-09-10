package moakiee.ae2oc.api;

import java.util.Objects;
import moakiee.ae2oc.core.quantity.SaturatedMath;

public record ResourceAmount<K>(K key, long amount) {
    public ResourceAmount {
        Objects.requireNonNull(key);
        SaturatedMath.requireNonNegative(amount);
    }
}
