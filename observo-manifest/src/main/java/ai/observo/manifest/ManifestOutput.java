package ai.observo.manifest;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves where a listener writes {@code observo-link-manifest.json} and
 * performs the write. Centralized here so the JUnit5 and TestNG listeners share
 * one output-path contract and one fail-safe write.
 *
 * <p>Resolution order: the {@code -Dobservo.manifest.out} system property, then
 * the {@code OBSERVO_MANIFEST_OUT} environment variable, then
 * {@code observo-link-manifest.json} in the working directory. The Gradle
 * plugin sets the system property to a build-directory path; a hand-configured
 * suite can use either knob.
 */
public final class ManifestOutput {

    public static final String SYS_PROP = "observo.manifest.out";
    public static final String ENV_VAR = "OBSERVO_MANIFEST_OUT";
    public static final String DEFAULT_PATH = "observo-link-manifest.json";

    private ManifestOutput() {
    }

    /** The resolved output path (never null). */
    public static Path resolvePath() {
        String p = System.getProperty(SYS_PROP);
        if (isBlank(p)) {
            p = System.getenv(ENV_VAR);
        }
        if (isBlank(p)) {
            p = DEFAULT_PATH;
        }
        return Paths.get(p);
    }

    /**
     * Writes {@code manifest} to the resolved path, creating parent directories
     * as needed. Never throws into the test lifecycle: writeback is
     * observability, not a gate, and a full disk or read-only mount must not
     * turn a green suite red. On failure it prints one warning to stderr and
     * returns null; on success it returns the path written.
     */
    public static Path write(LinkManifest manifest) {
        // resolvePath() is inside the try too: a malformed -Dobservo.manifest.out
        // / $OBSERVO_MANIFEST_OUT (an embedded NUL, or a stray :/*/? on Windows)
        // makes Paths.get throw InvalidPathException, and that must be swallowed
        // like any other write failure — it must never escape into the test
        // lifecycle and fail a green suite.
        Path out = null;
        try {
            out = resolvePath();
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                manifest.writeTo(w);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            System.err.println("[observo] could not write link manifest"
                    + (out != null ? " " + out : "") + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
