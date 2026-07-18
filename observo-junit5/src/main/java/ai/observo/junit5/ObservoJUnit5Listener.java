package ai.observo.junit5;

import ai.observo.manifest.LinkEntry;
import ai.observo.manifest.LinkManifest;
import ai.observo.manifest.ManifestOutput;
import ai.observo.manifest.Result;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * A JUnit Platform {@link TestExecutionListener} that records each test's
 * Observo link and outcome and, when the run finishes, emits
 * {@code observo-link-manifest.json} for {@code observo jvm push --manifest}
 * and the coverage verdict to consume.
 *
 * <p>The join key is read from the test's native JUnit 5 tags: a
 * {@code @Tag("observo:PD-101")} on the method (or its class) links it to
 * Observo case {@code PD-101}. A test with no such tag is still recorded, with
 * a null code, so it stays visible as untracked.
 *
 * <p>Registered through {@code META-INF/services}, so JUnit's launcher
 * auto-registers it — the client adds the jar and nothing else. It is
 * strictly additive: it never fails a test, and it does not touch the client's
 * Allure or any other reporter.
 */
public final class ObservoJUnit5Listener implements TestExecutionListener {

    private static final String TAG_PREFIX = "observo:";

    // TestExecutionListener carries no per-test duration, so we time it: start
    // nanos per uniqueId, consumed on finish.
    private final Map<String, Long> startNanos = new ConcurrentHashMap<>();

    // Keyed by fq_name so the invocations of a retried or parametrized test
    // (one @Tag, many runs) collapse into one entry, aggregated the way the
    // CLI's collapseByFQName does — any FAIL wins, durations summed — never
    // last-write-wins, which could record a failing test as passed.
    private final Map<String, LinkEntry> byFqName = new ConcurrentHashMap<>();

    @Override
    public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
            startNanos.put(id.getUniqueId(), System.nanoTime());
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) {
            return;
        }
        Long start = startNanos.remove(id.getUniqueId());
        long durationMs = start == null ? 0 : (System.nanoTime() - start) / 1_000_000L;
        record(id, statusOf(result), durationMs);
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (!id.isTest()) {
            return;
        }
        // A skipped test never starts or finishes; it has no duration.
        startNanos.remove(id.getUniqueId());
        record(id, Result.SKIP, 0);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        LinkManifest manifest = new LinkManifest("junit5");
        for (LinkEntry entry : byFqName.values()) {
            manifest.add(entry);
        }
        ManifestOutput.write(manifest);
    }

    private void record(TestIdentifier id, String status, long durationMs) {
        Optional<String> fqName = fqNameOf(id);
        if (fqName.isEmpty()) {
            // No MethodSource (e.g. a dynamic/factory node): there is no stable
            // fq_name to join on, so recording it would only add noise.
            return;
        }
        LinkEntry entry = LinkEntry.builder(fqName.get())
                .code(codeOf(id))
                .displayName(id.getDisplayName())
                .result(new Result(status, durationMs))
                .build();
        byFqName.merge(fqName.get(), entry, LinkEntry::mergedWith);
    }

    private static String statusOf(TestExecutionResult result) {
        switch (result.getStatus()) {
            case SUCCESSFUL:
                return Result.PASS;
            case FAILED:
                return Result.FAIL;
            case ABORTED:
            default:
                // ABORTED = an assumption failed (assumeTrue) — collapse to SKIP
                // to fit the framework-agnostic PASS/FAIL/SKIP set.
                return Result.SKIP;
        }
    }

    /** Extracts {@code Class#method} from the identifier's MethodSource. */
    private static Optional<String> fqNameOf(TestIdentifier id) {
        return id.getSource()
                .filter(MethodSource.class::isInstance)
                .map(MethodSource.class::cast)
                .map(ms -> ms.getClassName() + "#" + ms.getMethodName());
    }

    /** The first {@code observo:<code>} tag on the test (or its class), else null. */
    private static String codeOf(TestIdentifier id) {
        for (TestTag tag : id.getTags()) {
            String name = tag.getName();
            if (name.startsWith(TAG_PREFIX)) {
                return name.substring(TAG_PREFIX.length());
            }
        }
        return null;
    }
}
