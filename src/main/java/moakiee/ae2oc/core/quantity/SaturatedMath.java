package moakiee.ae2oc.core.quantity;

/** Arithmetic for resource quantities. Negative inputs are programming errors. */
public final class SaturatedMath {
    private SaturatedMath() {}

    public static long addNonNegative(long a, long b) {
        requireNonNegative(a);
        requireNonNegative(b);
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public static long multiplyNonNegative(long a, long b) {
        requireNonNegative(a);
        requireNonNegative(b);
        return b != 0 && a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    public static long floorDivToLong(double amount, double unit) {
        if (!Double.isFinite(amount) || amount < 0 || !Double.isFinite(unit) || unit <= 0) {
            throw new IllegalArgumentException("Expected finite amount >= 0 and unit > 0");
        }
        return (long) Math.floor(amount / unit);
    }

    public static void requireNonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative resource quantity: " + value);
    }
}
