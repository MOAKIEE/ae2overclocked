package moakiee.ae2oc.migration;

import java.util.OptionalLong;

/** Explicit precedence for old persisted counts; malformed values never silently become one. */
public final class LegacyQuantity {
    private LegacyQuantity() {}
    public static long resolve(long vanillaCount, OptionalLong savedCount, OptionalLong networkCount) {
        long result = savedCount.isPresent() ? savedCount.getAsLong()
                : networkCount.isPresent() ? networkCount.getAsLong() : vanillaCount;
        if (result < 0) throw new IllegalArgumentException("Negative legacy count; migration must retain original data");
        return result;
    }
}
