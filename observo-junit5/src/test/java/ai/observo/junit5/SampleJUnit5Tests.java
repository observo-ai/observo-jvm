package ai.observo.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A fixture suite driven by {@link ObservoJUnit5ListenerTest} through its own
 * Launcher. Every method carries {@code @Tag("observo-sample")} so Gradle's
 * outer run excludes them — one is deliberately a failing test, and it must not
 * fail this module's build.
 */
@Tag("observo-sample")
class SampleJUnit5Tests {

    @Test
    @Tag("observo:PD-PASS")
    void passes() {
    }

    @Test
    @Tag("observo:PD-FAIL")
    void failsHard() {
        fail("intentional failure");
    }

    @Test
    @Tag("observo:PD-SKIP")
    void aborted() {
        assumeTrue(false, "assumption not met");
    }

    @Test
    @Disabled("exercises the executionSkipped path")
    @Tag("observo:PD-DISABLED")
    void disabled() {
    }

    @Test
    void untracked() {
        // No observo:<code> tag → recorded with a null code.
    }

    // One logical test, two invocations sharing a fq_name: the first fails, the
    // second passes. The collapse must keep FAIL, never let the later PASS win.
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @Tag("observo:PD-PARAM")
    void parametrizedFailsThenPasses(int i) {
        assertEquals(2, i, "row 1 fails, row 2 passes");
    }
}
