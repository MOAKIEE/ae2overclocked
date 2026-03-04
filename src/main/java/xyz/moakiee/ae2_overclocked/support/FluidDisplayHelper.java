package xyz.moakiee.ae2_overclocked.support;

/**
 * Utility for formatting fluid amounts in mB to human-readable units.
 * <ul>
 *   <li>&lt; 1000 mB → "xxx mB"</li>
 *   <li>1,000 ~ 999,999 mB → "x.x B"</li>
 *   <li>1,000,000 ~ 999,999,999 mB → "x.x kB"</li>
 *   <li>≥ 1,000,000,000 mB → "x.x MB"</li>
 * </ul>
 */
public final class FluidDisplayHelper {

    private static final long MB_PER_BUCKET = 1000L;
    private static final long MB_PER_KB = 1_000_000L;
    private static final long MB_PER_MB = 1_000_000_000L;

    private FluidDisplayHelper() {
    }

    /**
     * Format a capacity value in mB to a human-readable string.
     */
    public static String formatMb(long amountMb) {
        if (amountMb < MB_PER_BUCKET) {
            return amountMb + " mB";
        }
        if (amountMb < MB_PER_KB) {
            return formatDecimal(amountMb, MB_PER_BUCKET) + " B";
        }
        if (amountMb < MB_PER_MB) {
            return formatDecimal(amountMb, MB_PER_KB) + " kB";
        }
        return formatDecimal(amountMb, MB_PER_MB) + " MB";
    }

    /**
     * Format an amount as x.x with one decimal place, trimming trailing ".0".
     */
    private static String formatDecimal(long amount, long divisor) {
        long wholePart = amount / divisor;
        long remainder = amount % divisor;
        int tenths = (int) (remainder * 10 / divisor);

        if (tenths == 0) {
            return String.valueOf(wholePart);
        }
        return wholePart + "." + tenths;
    }
}
