package ai.observo.manifest;

/**
 * One test ↔ case link — the Java mirror of {@code LinkEntry} in the CLI's
 * manifest.go. Built through {@link #builder(String)}.
 *
 * <p>Optional fields are left unset (null) rather than defaulted, and the
 * writer omits them, so a manifest from a runtime listener stays small and
 * honest about what it could not determine. The one exception is {@code code}:
 * it is always emitted — as JSON {@code null} for an untracked test — so an
 * untracked test stays visible in the artifact instead of being silently
 * dropped (OB-543 AC4).
 */
public final class LinkEntry {

    private final String code;
    private final String fqName;
    private final String displayName;
    private final String description;
    private final String feature;
    private final String story;
    private final Integer order;
    private final String chainId;
    private final Result result;
    private final String sourceRef;

    private LinkEntry(Builder b) {
        // Treat an empty code as untracked: the wire contract distinguishes
        // "has a code" (non-null string) from "untracked" (null), and an empty
        // string is neither useful nor a valid Observo short code.
        this.code = (b.code == null || b.code.isEmpty()) ? null : b.code;
        this.fqName = b.fqName == null ? "" : b.fqName;
        this.displayName = b.displayName == null ? "" : b.displayName;
        this.description = b.description;
        this.feature = b.feature;
        this.story = b.story;
        this.order = b.order;
        this.chainId = b.chainId;
        this.result = b.result;
        this.sourceRef = b.sourceRef;
    }

    /** The fully-qualified test name, e.g. {@code api.pd.PdTest#createsWallet}. */
    public String fqName() {
        return fqName;
    }

    /**
     * Folds a repeat invocation of the same test into this (first-seen) entry:
     * the result is aggregated (any FAIL wins, durations summed) and every other
     * field takes the first non-empty value. Mirrors the CLI's
     * {@code collapseByFQName}/{@code mergeInto}, so a data-driven / parametrized
     * / retried test — many invocations of one {@code Class#method} — collapses
     * to ONE honest entry instead of last-write-wins, which could record a
     * failing test as passed.
     *
     * <p>Designed as the remap function for {@code ConcurrentHashMap.merge},
     * which applies it atomically per key, so it is also correct under parallel
     * test execution.
     */
    public LinkEntry mergedWith(LinkEntry repeat) {
        return new Builder(fqName)
                .code(code != null ? code : repeat.code)
                .displayName(displayName.isEmpty() ? repeat.displayName : displayName)
                .description(firstNonEmpty(description, repeat.description))
                .feature(firstNonEmpty(feature, repeat.feature))
                .story(firstNonEmpty(story, repeat.story))
                .order(order != null ? order : repeat.order)
                .chainId(firstNonEmpty(chainId, repeat.chainId))
                .sourceRef(firstNonEmpty(sourceRef, repeat.sourceRef))
                .result(Result.aggregate(result, repeat.result))
                .build();
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    Json.Obj toJson() {
        Json.Obj o = new Json.Obj();
        o.putNullable("code", code);
        o.put("fq_name", fqName);
        o.put("display_name", displayName);
        o.putOmitEmpty("description", description);
        o.putOmitEmpty("feature", feature);
        o.putOmitEmpty("story", story);
        if (order != null) {
            o.put("order", order.longValue());
        }
        o.putOmitEmpty("chain_id", chainId);
        if (result != null) {
            o.put("result", result.toJson());
        }
        o.putOmitEmpty("source_ref", sourceRef);
        return o;
    }

    public static Builder builder(String fqName) {
        return new Builder(fqName);
    }

    /** Fluent builder for a {@link LinkEntry}. */
    public static final class Builder {
        private final String fqName;
        private String code;
        private String displayName;
        private String description;
        private String feature;
        private String story;
        private Integer order;
        private String chainId;
        private Result result;
        private String sourceRef;

        private Builder(String fqName) {
            this.fqName = fqName;
        }

        /** The Observo short code, or null/empty for an untracked test. */
        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder feature(String feature) {
            this.feature = feature;
            return this;
        }

        public Builder story(String story) {
            this.story = story;
            return this;
        }

        public Builder order(Integer order) {
            this.order = order;
            return this;
        }

        public Builder chainId(String chainId) {
            this.chainId = chainId;
            return this;
        }

        public Builder result(Result result) {
            this.result = result;
            return this;
        }

        /** {@code file:line} for the test — supplied only when the runtime can
         * determine it honestly; left unset otherwise. */
        public Builder sourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
            return this;
        }

        public LinkEntry build() {
            return new LinkEntry(this);
        }
    }
}
