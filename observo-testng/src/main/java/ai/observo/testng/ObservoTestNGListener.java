package ai.observo.testng;

import ai.observo.manifest.LinkEntry;
import ai.observo.manifest.LinkManifest;
import ai.observo.manifest.ManifestOutput;
import ai.observo.manifest.Result;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.testng.IExecutionListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

/**
 * A TestNG listener that records each test's Observo link and outcome and, when
 * the whole run finishes, emits {@code observo-link-manifest.json} for
 * {@code observo jvm push --manifest} and the coverage verdict to consume.
 *
 * <p>The join key is read from the test's native TestNG groups: a
 * {@code @Test(groups = {"observo:PD-101"})} links it to Observo case
 * {@code PD-101}. A test with no such group is still recorded, with a null
 * code, so it stays visible as untracked.
 *
 * <p>Registered through {@code META-INF/services/org.testng.ITestNGListener},
 * so TestNG's ServiceLoader discovery picks it up — the client adds the jar and
 * nothing else. It is strictly additive: it never fails a test, and it does not
 * touch the client's Allure or any other reporter.
 *
 * <p>It implements {@link IExecutionListener} to flush exactly once at the end
 * of the entire run (after every suite/test), so a multi-{@code <test>} suite
 * produces one complete manifest rather than several partial ones.
 */
public final class ObservoTestNGListener implements ITestListener, IExecutionListener {

    private static final String GROUP_PREFIX = "observo:";

    // Keyed by fq_name so the invocations of a retried or data-driven test
    // (one method, many rows) collapse into one entry, aggregated the way the
    // CLI's collapseByFQName does — any FAIL wins, durations summed — never
    // last-write-wins, which could record a failing row as passed.
    private final Map<String, LinkEntry> byFqName = new ConcurrentHashMap<>();

    @Override
    public void onTestSuccess(ITestResult result) {
        record(result, Result.PASS);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        record(result, Result.FAIL);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        record(result, Result.SKIP);
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        // Still a failed invocation as far as the case status is concerned.
        record(result, Result.FAIL);
    }

    @Override
    public void onExecutionFinish() {
        LinkManifest manifest = new LinkManifest("testng");
        for (LinkEntry entry : byFqName.values()) {
            manifest.add(entry);
        }
        ManifestOutput.write(manifest);
    }

    private void record(ITestResult result, String status) {
        String fqName = result.getTestClass().getName() + "#" + result.getMethod().getMethodName();
        long durationMs = result.getEndMillis() - result.getStartMillis();
        LinkEntry entry = LinkEntry.builder(fqName)
                .code(codeOf(result))
                .displayName(result.getName())
                .description(result.getMethod().getDescription())
                .result(new Result(status, durationMs))
                .build();
        byFqName.merge(fqName, entry, LinkEntry::mergedWith);
    }

    /** The first {@code observo:<code>} group on the test method, else null. */
    private static String codeOf(ITestResult result) {
        for (String group : result.getMethod().getGroups()) {
            if (group.startsWith(GROUP_PREFIX)) {
                return group.substring(GROUP_PREFIX.length());
            }
        }
        return null;
    }
}
