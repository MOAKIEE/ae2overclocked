package moakiee.ae2oc.core.execution;

/** Deterministic exponential retry schedule for blocked machine work. */
public final class RetryBackoff {
    private final int minimumTicks;
    private final int maximumTicks;
    private int nextDelay;
    private long retryAt;

    public RetryBackoff(int minimumTicks, int maximumTicks) {
        if (minimumTicks < 1 || maximumTicks < minimumTicks) {
            throw new IllegalArgumentException("Invalid retry bounds");
        }
        this.minimumTicks = minimumTicks;
        this.maximumTicks = maximumTicks;
        reset();
    }

    public boolean ready(long gameTime) {
        return gameTime >= retryAt;
    }

    public void blocked(long gameTime) {
        retryAt = saturatedAdd(gameTime, nextDelay);
        nextDelay = (int) Math.min(maximumTicks, (long) nextDelay * 2);
    }

    public void reset() {
        retryAt = 0;
        nextDelay = minimumTicks;
    }

    long retryAt() {
        return retryAt;
    }

    int nextDelay() {
        return nextDelay;
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
