package ai.observo.manifest;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The run artifact that links JVM tests to Observo cases — the Java mirror of
 * the CLI's manifest.go ({@code internal/jvm/manifest.go}). It is the single
 * wire contract shared by the runtime listeners (OB-547) that PRODUCE it and
 * the CLI's {@code jvm push} / coverage verdict (OB-550) that CONSUME it.
 *
 * <p>Compatibility rides on {@link #VERSION}, not on matching release numbers:
 * observo-jvm and observo-cli version independently, and a consumer refuses a
 * manifest whose version it does not understand rather than misreading it. Keep
 * {@code VERSION} and the field meanings in lockstep with manifest.go's
 * {@code ManifestVersion}.
 */
public final class LinkManifest {

    /**
     * Stamped into every emitted manifest. Bump together with manifest.go's
     * {@code ManifestVersion} when a field's meaning changes.
     */
    public static final int VERSION = 1;

    private final String framework;
    private final List<LinkEntry> entries = new ArrayList<>();

    /**
     * @param framework {@code testng} / {@code junit5} / {@code playwright}, or
     *                  null/empty to omit the label (Allure alone can't tell
     *                  TestNG from JUnit5 — we don't guess)
     */
    public LinkManifest(String framework) {
        this.framework = (framework == null || framework.isEmpty()) ? null : framework;
    }

    public void add(LinkEntry entry) {
        entries.add(entry);
    }

    public int size() {
        return entries.size();
    }

    /**
     * Writes the manifest as 2-space-indented JSON with entries in a stable
     * order (sorted by {@code fq_name}), matching manifest.go's {@code Emit} so
     * repeated builds of the same run produce identical output and a
     * listener-emitted manifest diffs cleanly against a CLI-derived one. An
     * empty run still writes {@code "entries": []}, never null, so a run that
     * linked nothing is distinguishable from a missing artifact.
     */
    public void writeTo(Writer w) throws IOException {
        List<LinkEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(LinkEntry::fqName));

        Json.Obj root = new Json.Obj();
        root.put("version", VERSION);
        if (framework != null) {
            root.put("framework", framework);
        }
        Json.Arr arr = new Json.Arr();
        for (LinkEntry e : sorted) {
            arr.add(e.toJson());
        }
        root.put("entries", arr);

        root.writeTo(w, 0);
        w.write("\n");
    }
}
