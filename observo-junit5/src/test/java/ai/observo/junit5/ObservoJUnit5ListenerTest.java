package ai.observo.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import ai.observo.manifest.ManifestOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Drives {@link SampleJUnit5Tests} through an in-process Launcher with the
 * listener attached and asserts the emitted manifest. Proves the listener
 * reads the {@code observo:<code>} tag, maps PASS/FAIL/SKIP correctly, and
 * keeps an untracked test visible.
 */
class ObservoJUnit5ListenerTest {

    @Test
    void emitsManifestForATaggedRun(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("observo-link-manifest.json");
        String previous = System.getProperty(ManifestOutput.SYS_PROP);
        System.setProperty(ManifestOutput.SYS_PROP, out.toString());
        try {
            // Disable service-loader auto-registration for THIS inner run so the
            // only listener is the one we attach — the outer Gradle JVM already
            // auto-registers a second instance from META-INF/services, and we
            // don't want the two racing on the output path.
            LauncherConfig config = LauncherConfig.builder()
                    .enableTestExecutionListenerAutoRegistration(false)
                    .build();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(SampleJUnit5Tests.class))
                    .build();
            Launcher launcher = LauncherFactory.create(config);
            launcher.execute(request, new ObservoJUnit5Listener());

            assertTrue(Files.exists(out), "manifest was not written");
            String json = Files.readString(out, StandardCharsets.UTF_8);

            assertTrue(json.contains("\"framework\": \"junit5\""), json);
            assertEquals("PASS", statusForCode(json, "PD-PASS"), json);
            assertEquals("FAIL", statusForCode(json, "PD-FAIL"), json);
            assertEquals("SKIP", statusForCode(json, "PD-SKIP"), json);
            assertEquals("SKIP", statusForCode(json, "PD-DISABLED"), json);
            // Two invocations (fail then pass) collapse to one FAIL entry — a
            // later passing row must never overwrite an earlier failure.
            assertEquals("FAIL", statusForCode(json, "PD-PARAM"), json);

            // The untracked test is present with an explicit null code.
            assertTrue(json.contains("SampleJUnit5Tests#untracked"), json);
            assertTrue(json.contains("\"code\": null"), json);
        } finally {
            if (previous == null) {
                System.clearProperty(ManifestOutput.SYS_PROP);
            } else {
                System.setProperty(ManifestOutput.SYS_PROP, previous);
            }
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
