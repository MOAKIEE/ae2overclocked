package moakiee.ae2oc.api;

/** Immutable performance configuration consumed by machine tick hot paths. */
public record PerformanceBudget(int recipeOperations, long pendingOutputAmount, int transferKeys,
        long transferAmount, int retryMinTicks, int retryMaxTicks) {
    public PerformanceBudget {
        if (recipeOperations < 1 || pendingOutputAmount < 1 || transferKeys < 1 || transferAmount < 1
                || retryMinTicks < 1 || retryMaxTicks < retryMinTicks) {
            throw new IllegalArgumentException("Invalid performance budget");
        }
    }
}
