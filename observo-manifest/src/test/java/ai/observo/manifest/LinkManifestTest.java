package ai.observo.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locks the observo-link-manifest.json wire contract byte-for-byte. This is
 * the counterpart to the CLI's manifest golden tests: if the two drift, a
 * listener-emitted manifest stops matching what {@code jvm push} expects.
 */
class LinkManifestTest {

    private static String render(LinkManifest m) throws IOException {
        StringWriter w = new StringWriter();
        m.writeTo(w);
        return w.toString();
    }

    @Test
    void emitsTrackedEntryWithResult() throws IOException {
        LinkManifest m = new LinkManifest("testng");
        m.add(LinkEntry.builder("api.pd.PdTest#createsWallet")
                .code("PD-101")
                .displayName("trader creates a wallet")
                .result(new Result(Result.PASS, 42))
                .build());

        String expected = "{\n"
                + "  \"version\": 1,\n"
                + "  \"framework\": \"testng\",\n"
                + "  \"entries\": [\n"
                + "    {\n"
                + "      \"code\": \"PD-101\",\n"
                + "      \"fq_name\": \"api.pd.PdTest#createsWallet\",\n"
                + "      \"display_name\": \"trader creates a wallet\",\n"
                + "      \"result\": {\n"
                + "        \"status\": \"PASS\",\n"
                + "        \"duration_ms\": 42\n"
                + "      }\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
        assertEquals(expected, render(m));
    }

    @Test
    void untrackedEntryKeepsCodeAsExplicitNull() throws IOException {
        LinkManifest m = new LinkManifest("junit5");
        m.add(LinkEntry.builder("api.pd.PdTest#unlinked")
                .displayName("unlinked")
                .result(new Result(Result.FAIL, 7))
                .build());

        String json = render(m);
        // code is present as literal null — never omitted — so an untracked
        // test stays visible in the artifact (OB-543 AC4).
        assertTrue(json.contains("\"code\": null"), json);
    }

    @Test
    void emptyCodeIsTreatedAsUntracked() throws IOException {
        LinkManifest m = new LinkManifest("junit5");
        m.add(LinkEntry.builder("api.pd.PdTest#blank").code("").displayName("blank").build());
        assertTrue(render(m).contains("\"code\": null"));
    }

    @Test
    void entriesAreSortedByFqNameForStableOutput() throws IOException {
        LinkManifest m = new LinkManifest("testng");
        m.add(LinkEntry.builder("z.Zeta#last").code("Z-1").build());
        m.add(LinkEntry.builder("a.Alpha#first").code("A-1").build());

        String json = render(m);
        assertTrue(json.indexOf("a.Alpha#first") < json.indexOf("z.Zeta#last"),
                "entries must be sorted by fq_name:\n" + json);
    }

    @Test
    void emptyRunEmitsEntriesArrayNotNull() throws IOException {
        String json = render(new LinkManifest("testng"));
        assertTrue(json.contains("\"entries\": []"), json);
    }

    @Test
    void omitsFrameworkWhenBlank() throws IOException {
        String json = render(new LinkManifest(""));
        assertTrue(!json.contains("framework"), json);
    }

    @Test
    void omitsEmptyOptionalFieldsButKeepsRequiredOnes() throws IOException {
        LinkManifest m = new LinkManifest("testng");
        m.add(LinkEntry.builder("a.T#m").code("A-1").displayName("m").build());
        String json = render(m);
        assertTrue(!json.contains("description"), json);
        assertTrue(!json.contains("source_ref"), json);
        assertTrue(!json.contains("chain_id"), json);
        assertTrue(!json.contains("result"), json); // no result set → omitted
        assertTrue(json.contains("\"fq_name\""), json);
        assertTrue(json.contains("\"display_name\""), json);
    }

    @Test
    void escapesStringsAndClampsNegativeDuration() throws IOException {
        LinkManifest m = new LinkManifest("junit5");
        m.add(LinkEntry.builder("a.T#quote\"tab\t")
                .code("A-1")
                .displayName("line\nbreak")
                .result(new Result(Result.SKIP, -5))
                .build());
        String json = render(m);
        assertTrue(json.contains("\\\"tab\\t"), json);
        assertTrue(json.contains("line\\nbreak"), json);
        assertTrue(json.contains("\"duration_ms\": 0"), json); // negative clamps to 0
    }

    @Test
    void mergedWithLetsAnyFailDominateAndSumsDurations() throws IOException {
        // A data-driven / parametrized / retried test emits one entry per
        // invocation, all sharing an fq_name. Collapsing them must never let a
        // later PASS overwrite an earlier FAIL — that would report a failing
        // test as passed, the one thing this product must not do.
        LinkEntry pass = LinkEntry.builder("a.T#param")
                .code("PD-1").displayName("param")
                .result(new Result(Result.PASS, 10)).build();
        LinkEntry fail = LinkEntry.builder("a.T#param")
                .result(new Result(Result.FAIL, 5)).build();

        LinkManifest m = new LinkManifest("junit5");
        m.add(pass.mergedWith(fail)); // PASS seen first, then FAIL invocation
        String json = render(m);
        assertTrue(json.contains("\"status\": \"FAIL\""), json);
        assertTrue(json.contains("\"duration_ms\": 15"), json); // 10 + 5
        // First-non-empty fields survive from the base entry.
        assertTrue(json.contains("\"code\": \"PD-1\""), json);
    }

    @Test
    void mergedWithFillsFirstNonEmptyFieldsFromRepeat() throws IOException {
        // Base's first invocation had no code; a later invocation carries it.
        LinkEntry base = LinkEntry.builder("a.T#m")
                .result(new Result(Result.PASS, 1)).build();
        LinkEntry repeat = LinkEntry.builder("a.T#m")
                .code("PD-9").displayName("m")
                .result(new Result(Result.PASS, 1)).build();

        LinkManifest man = new LinkManifest("");
        man.add(base.mergedWith(repeat));
        String json = render(man);
        assertTrue(json.contains("\"code\": \"PD-9\""), json);
        assertTrue(json.contains("\"display_name\": \"m\""), json);
    }

    @Test
    void aggregatePrefersFailThenPassThenSkip() throws IOException {
        assertEquals("FAIL", statusOf(Result.aggregate(new Result(Result.PASS, 1), new Result(Result.FAIL, 1))));
        assertEquals("FAIL", statusOf(Result.aggregate(new Result(Result.FAIL, 1), new Result(Result.PASS, 1))));
        assertEquals("PASS", statusOf(Result.aggregate(new Result(Result.SKIP, 1), new Result(Result.PASS, 1))));
        assertEquals("PASS", statusOf(Result.aggregate(new Result(Result.PASS, 1), new Result(Result.SKIP, 1))));
        assertEquals("PASS", statusOf(Result.aggregate(null, new Result(Result.PASS, 1))));
        assertEquals("SKIP", statusOf(Result.aggregate(new Result(Result.SKIP, 1), null)));
    }

    /** Renders a one-entry manifest carrying {@code r} and pulls its status. */
    private static String statusOf(Result r) throws IOException {
        LinkManifest m = new LinkManifest("");
        m.add(LinkEntry.builder("a.T#m").result(r).build());
        Matcher mm = Pattern.compile("\"status\": \"(PASS|FAIL|SKIP)\"").matcher(render(m));
        return mm.find() ? mm.group(1) : null;
    }

    @Test
    void emitsRicherFieldsWhenSupplied() throws IOException {
        LinkManifest m = new LinkManifest("testng");
        m.add(LinkEntry.builder("a.T#m")
                .code("A-1")
                .displayName("m")
                .description("does a thing")
                .order(2)
                .chainId("chain-a")
                .sourceRef("src/test/java/a/T.java:12")
                .result(new Result(Result.PASS, 1))
                .build());
        String json = render(m);
        assertTrue(json.contains("\"description\": \"does a thing\""), json);
        assertTrue(json.contains("\"order\": 2"), json);
        assertTrue(json.contains("\"chain_id\": \"chain-a\""), json);
        assertTrue(json.contains("\"source_ref\": \"src/test/java/a/T.java:12\""), json);
    }
}
