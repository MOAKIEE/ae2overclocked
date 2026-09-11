package moakiee.ae2oc.core.planning;

/** Splits positive machine-wide budgets between independent processing lanes. */
public final class MachineBudget {
    private MachineBudget() {}

    public static long share(long total, int shares) {
        if (total < 1 || shares < 1) throw new IllegalArgumentException("Budgets and shares must be positive");
        return Math.max(1, total / shares);
    }

    public static int share(int total, int shares) {
        return Math.toIntExact(share((long) total, shares));
    }
}
