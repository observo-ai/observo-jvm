package ai.observo.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestOutputTest {

    // An embedded NUL byte is rejected by Paths.get on every OS. Built with an
    // explicit (char) 0 so the invalid byte is unambiguous in source.
    private static final String MALFORMED_PATH = "bad" + ((char) 0) + "path.json";

    @Test
    void writesToTheResolvedPath(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("nested/observo-link-manifest.json");
        withSysProp(out.toString(), () -> {
            LinkManifest m = new LinkManifest("testng");
            m.add(LinkEntry.builder("a.T#m").code("PD-1").result(new Result(Result.PASS, 1)).build());
            Path written = ManifestOutput.write(m);
            assertEquals(out, written);
            assertTrue(Files.exists(out), "manifest not written");
            assertTrue(Files.readString(out, StandardCharsets.UTF_8).contains("\"code\": \"PD-1\""));
        });
    }

    @Test
    void aMalformedOutputPathIsSwallowedNotThrownIntoTheLifecycle() {
        // Paths.get(MALFORMED_PATH) throws InvalidPathException. write() must
        // catch it — a bad -D value must not fail a green suite (AC2).
        withSysProp(MALFORMED_PATH, () ->
                assertNull(ManifestOutput.write(new LinkManifest("testng")),
                        "a malformed path must return null, not throw"));
    }

    private static void withSysProp(String value, ThrowingRunnable body) {
        String previous = System.getProperty(ManifestOutput.SYS_PROP);
        System.setProperty(ManifestOutput.SYS_PROP, value);
        try {
            body.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (previous == null) {
                System.clearProperty(ManifestOutput.SYS_PROP);
            } else {
                System.setProperty(ManifestOutput.SYS_PROP, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
