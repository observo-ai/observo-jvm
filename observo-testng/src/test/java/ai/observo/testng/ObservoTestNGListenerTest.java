package ai.observo.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import ai.observo.manifest.ManifestOutput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.TestNG;
import org.testng.annotations.Test;

/**
 * Drives {@link SampleTestNGTests} through an in-process TestNG instance with
 * the listener attached and asserts the emitted manifest. Proves the listener
 * reads the {@code observo:<code>} group, maps PASS/FAIL/SKIP correctly, and
 * keeps an untracked test visible.
 */
public class ObservoTestNGListenerTest {

    @Test
    public void emitsManifestForATaggedRun() throws Exception {
        Path dir = Files.createTempDirectory("observo-testng-test");
        Path out = dir.resolve("observo-link-manifest.json");
        String previous = System.getProperty(ManifestOutput.SYS_PROP);
        System.setProperty(ManifestOutput.SYS_PROP, out.toString());
        try {
            TestNG tng = new TestNG();
            tng.setUseDefaultListeners(false);
            tng.setTestClasses(new Class[] {SampleTestNGTests.class});
            tng.addListener(new ObservoTestNGListener());
            tng.run();

            assertTrue(Files.exists(out), "manifest was not written");
            String json = Files.readString(out, StandardCharsets.UTF_8);

            assertTrue(json.contains("\"framework\": \"testng\""), json);
            assertEquals(statusForCode(json, "PD-PASS"), "PASS", json);
            assertEquals(statusForCode(json, "PD-FAIL"), "FAIL", json);
            assertEquals(statusForCode(json, "PD-SKIP"), "SKIP", json);
            // Two data rows (fail then pass) collapse to one FAIL entry — a
            // later passing row must never overwrite an earlier failure.
            assertEquals(statusForCode(json, "PD-DATA"), "FAIL", json);

            // The untracked test is present with an explicit null code.
            assertTrue(json.contains("SampleTestNGTests#untracked"), json);
            assertTrue(json.contains("\"code\": null"), json);
        } finally {
            if (previous == null) {
                System.clearProperty(ManifestOutput.SYS_PROP);
            } else {
                System.setProperty(ManifestOutput.SYS_PROP, previous);
            }
            Files.deleteIfExists(out);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Returns the result status of the entry carrying {@code code}. Within one
     * entry {@code code} precedes {@code result.status}, and entries are sorted,
     * so the first status after the code belongs to that entry.
     */
    private static String statusForCode(String json, String code) {
        Matcher m = Pattern.compile(
                        "\"code\": \"" + Pattern.quote(code) + "\".*?\"status\": \"(PASS|FAIL|SKIP)\"",
                        Pattern.DOTALL)
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
