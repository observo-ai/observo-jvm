package ai.observo.testng;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * A fixture suite driven by {@link ObservoTestNGListenerTest} through its own
 * TestNG instance. Every method is in the {@code observo-sample} group so
 * Gradle's outer TestNG run excludes them — one is deliberately a failing test,
 * and it must not fail this module's build.
 */
public class SampleTestNGTests {

    @Test(groups = {"observo-sample", "observo:PD-PASS"})
    public void passes() {
    }

    @Test(groups = {"observo-sample", "observo:PD-FAIL"})
    public void failsHard() {
        Assert.fail("intentional failure");
    }

    @Test(groups = {"observo-sample", "observo:PD-SKIP"})
    public void skipped() {
        throw new SkipException("skip me");
    }

    @Test(groups = {"observo-sample"})
    public void untracked() {
        // No observo:<code> group → recorded with a null code.
    }

    @DataProvider(name = "rows")
    public Object[][] rows() {
        return new Object[][] {{false}, {true}};
    }

    // One method, two data rows sharing a fq_name: the first fails, the second
    // passes. The collapse must keep FAIL, never let the later PASS win.
    @Test(dataProvider = "rows", groups = {"observo-sample", "observo:PD-DATA"})
    public void dataDrivenFailsThenPasses(boolean ok) {
        Assert.assertTrue(ok, "row false fails, row true passes");
    }
}
