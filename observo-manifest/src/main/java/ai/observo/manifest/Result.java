package ai.observo.manifest;

/**
 * The per-test outcome, present only when a manifest was built from an actual
 * run. Mirrors {@code Result} in the CLI's manifest.go.
 *
 * <p>Statuses are framework-agnostic on purpose (Allure "broken" and TestNG
 * "FAIL" both collapse to {@link #FAIL}) so every consumer switches on one
 * small set.
 */
public final class Result {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIP = "SKIP";

    private final String status;
    private final long durationMs;

    /**
     * @param status     one of {@link #PASS} / {@link #FAIL} / {@link #SKIP}
     * @param durationMs wall time in ms; negatives (clock skew) clamp to 0,
     *                   and 0 is also the honest value when the source omits
     *                   timing
     */
    public Result(String status, long durationMs) {
        this.status = status;
        this.durationMs = Math.max(0, durationMs);
    }

    Json.Obj toJson() {
        return new Json.Obj()
                .put("status", status)
                .put("duration_ms", durationMs);
    }

    /**
     * Orders statuses for aggregating repeated invocations of one logical test:
     * any FAIL dominates, and a run that executed (PASS) beats a pure SKIP.
     * Mirrors the CLI's {@code statusRank}.
     */
    static int rank(String status) {
        if (FAIL.equals(status)) {
            return 3;
        }
        if (PASS.equals(status)) {
            return 2;
        }
        if (SKIP.equals(status)) {
            return 1;
        }
        return 0;
    }

    /**
     * Folds {@code b} into {@code a}: the dominant status wins and durations
     * sum. Either operand may be null. Mirrors the CLI's {@code aggregateResult}
     * so a data-driven / parametrized / retried test collapses to one honest
     * result instead of last-write-wins (which could report a failing test as
     * passed).
     */
    static Result aggregate(Result a, Result b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        String status = rank(b.status) > rank(a.status) ? b.status : a.status;
        return new Result(status, a.durationMs + b.durationMs);
    }
}
