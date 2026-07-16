package ai.observo.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives a real consumer build through TestKit against a fake `observo` binary.
 *
 * The fake records its own argv and `OBSERVO_API_KEY`, which is the only way to
 * assert the thing that actually matters about this plugin: that it hands the
 * CLI exactly the flags that CLI accepts. Those flags differ per subcommand
 * (`jvm stub` has no `--project`), so a generic "it runs" test would not catch
 * the failure that matters.
 *
 * POSIX-only: the fake CLI is a `/bin/sh` script.
 */
class ObservoPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val buildFile: File get() = File(projectDir, "build.gradle.kts")
    private val argsFile: File get() = File(projectDir, "cli-args.txt")
    private val envFile: File get() = File(projectDir, "cli-env.txt")

    @BeforeTest
    fun setUp() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        writeFakeCli(exitCode = 0)
    }

    /**
     * A stand-in for the real CLI that dumps argv and the API key next to the
     * build, then exits with [exitCode]. One arg per line so assertions can be
     * exact rather than substring-matching a flattened string.
     */
    private fun writeFakeCli(exitCode: Int) {
        val script = File(projectDir, "fake-observo")
        script.writeText(
            """
            #!/bin/sh
            : > cli-args.txt
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a" >> cli-args.txt; done
            printf '%s' "${'$'}{OBSERVO_API_KEY:-<unset>}" > cli-env.txt
            exit $exitCode
            """.trimIndent()
        )
        script.setExecutable(true)
    }

    private fun buildScript(observoBlock: String, extraPlugins: String = "") = """
        plugins {
            id("ai.observo")
            $extraPlugins
        }
        observo {
            cliPath = file("fake-observo")
            $observoBlock
        }
    """.trimIndent()

    private fun run(vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .build()

    private fun runAndFail(vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args)
            .buildAndFail()

    private fun recordedArgs(): List<String> =
        argsFile.readLines().filter { it.isNotBlank() }

    @Test
    fun `push forwards project and plan to the CLI`() {
        buildFile.writeText(
            buildScript(
                """
                project = "PD"
                plan = "REGR-MAIN-CI"
                """.trimIndent()
            )
        )

        val result = run("observoPush")

        assertEquals(TaskOutcome.SUCCESS, result.task(":observoPush")?.outcome)
        val args = recordedArgs()
        assertEquals(listOf("jvm", "push"), args.take(2))
        assertContainsFlag(args, "--project", "PD")
        assertContainsFlag(args, "--plan", "REGR-MAIN-CI")
    }

    @Test
    fun `observo project DSL property is assignable despite colliding with Gradle's own project`() {
        // `observo { project = "PD" }` is the DSL the PRD specifies, but
        // `project` also names Gradle's Project inside a build script. If the
        // extension property ever lost that resolution race this build would
        // fail to compile — which is precisely what this asserts.
        buildFile.writeText(buildScript("""project = "PD""""))

        run("observoPush")

        assertContainsFlag(recordedArgs(), "--project", "PD")
    }

    @Test
    fun `stub never receives --project because the CLI has no such flag`() {
        // `observo jvm stub` addresses cases by short code and defines no
        // --project flag; passing it would abort with "unknown flag".
        buildFile.writeText(buildScript("""project = "PD""""))

        run("observoStub", "--cases=PD-201,PD-202", "--out=src/test/kotlin/api/pd")

        val args = recordedArgs()
        assertEquals(listOf("jvm", "stub"), args.take(2))
        assertContainsFlag(args, "--cases", "PD-201,PD-202")
        assertContainsFlag(args, "--out", "src/test/kotlin/api/pd")
        assertFalse("--project" in args, "stub must not be passed --project; got $args")
    }

    @Test
    fun `import is dry-run unless --apply is given`() {
        buildFile.writeText(buildScript("""project = "PD""""))

        run("observoImport")
        assertFalse("--apply" in recordedArgs(), "import must default to dry-run")

        run("observoImport", "--apply", "--allow-ci")
        val args = recordedArgs()
        assertTrue("--apply" in args)
        assertTrue("--allow-ci" in args)
    }

    // ---- Gradle built-in option collisions ---------------------------------
    //
    // A task @Option cannot shadow one of Gradle's own global options: Gradle
    // parses built-ins first. `--dry-run` (-m) disables all task actions, so the
    // task reports SKIPPED and the build goes GREEN having done nothing;
    // `--priority` expects normal|low and makes Gradle print its help instead of
    // running anything. Both failed silently enough to look like success, which
    // is why these tests assert the task actually EXECUTED, not merely that the
    // build passed.

    @Test
    fun `stub --preview runs the task and reaches the CLI as --dry-run`() {
        buildFile.writeText(buildScript("""project = "PD""""))

        val result = run("observoStub", "--cases=PD-1", "--out=gen", "--preview")

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":observoStub")?.outcome,
            "task must actually run — SKIPPED here means the option name was eaten by a Gradle built-in",
        )
        assertTrue("--dry-run" in recordedArgs(), "CLI should receive --dry-run; got ${recordedArgs()}")
    }

    @Test
    fun `push --preview runs the task and reaches the CLI as --dry-run`() {
        buildFile.writeText(buildScript("""project = "PD""""))

        val result = run("observoPush", "--preview")

        assertEquals(TaskOutcome.SUCCESS, result.task(":observoPush")?.outcome)
        assertTrue("--dry-run" in recordedArgs(), "CLI should receive --dry-run; got ${recordedArgs()}")
    }

    @Test
    fun `import --case-priority runs the task and reaches the CLI as --priority`() {
        buildFile.writeText(buildScript("""project = "PD""""))

        val result = run("observoImport", "--case-priority=HIGH", "--layer=E2E", "--chain=flat")

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":observoImport")?.outcome,
            "task must actually run — Gradle's own --priority would have aborted the build instead",
        )
        val args = recordedArgs()
        assertContainsFlag(args, "--priority", "HIGH")
        assertContainsFlag(args, "--layer", "E2E")
        assertContainsFlag(args, "--chain", "flat")
    }

    @Test
    fun `push passes the allure results dir when it exists and warns when it does not`() {
        buildFile.writeText(buildScript("""project = "PD""""))

        // Absent: the flag is dropped, but audibly.
        val missing = run("observoPush")
        assertFalse("--from" in recordedArgs())
        assertTrue(
            missing.output.contains("no Allure results directory"),
            "a dropped report must be announced; output was:\n${missing.output}",
        )

        // Present: the flag carries the resolved directory.
        val allure = File(projectDir, "build/allure-results")
        allure.mkdirs()
        val found = run("observoPush")
        val args = recordedArgs()
        val i = args.indexOf("--from")
        assertTrue(i >= 0, "expected --from once allure-results exists; got $args")
        assertEquals(allure.canonicalPath, File(args[i + 1]).canonicalPath)
        assertFalse(found.output.contains("no Allure results directory"))
    }

    @Test
    fun `run wins over plan and the two are never passed together`() {
        // The CLI rejects both at once ("--plan and --run are mutually
        // exclusive"), so emitting both whenever both are configured would abort
        // every push rather than honour the documented precedence.
        buildFile.writeText(
            buildScript(
                """
                project = "PD"
                plan = "REGR-MAIN-CI"
                run = "RUN-42"
                """.trimIndent()
            )
        )

        run("observoPush")

        val args = recordedArgs()
        assertContainsFlag(args, "--run", "RUN-42")
        assertFalse("--plan" in args, "--plan must be dropped when --run is set; got $args")
    }

    @Test
    fun `a blank api key source falls through instead of shadowing the next one`() {
        // Gradle's `orElse` falls through on ABSENT, not on empty — and "" is a
        // value. A CI job declaring `OBSERVO_API_KEY: ""` would otherwise
        // shadow every remaining source and the push would run with no key.
        assumeTrue(
            System.getenv("OBSERVO_API_KEY") == null,
            "ambient OBSERVO_API_KEY would win over the keystore fallback under test",
        )
        File(projectDir, "keystore.properties").writeText("observo.apiKey=from-keystore\n")
        buildFile.writeText(
            buildScript(
                """
                project = "PD"
                apiKey = ""
                """.trimIndent()
            )
        )

        run("observoPush")

        assertEquals("from-keystore", envFile.readText())
    }

    @Test
    fun `api key reaches the CLI through the environment and never through argv`() {
        buildFile.writeText(
            buildScript(
                """
                project = "PD"
                apiKey = "super-secret"
                """.trimIndent()
            )
        )

        run("observoPush")

        assertEquals("super-secret", envFile.readText())
        assertFalse(
            recordedArgs().any { it.contains("super-secret") },
            "the API key must never appear in argv — `ps` would expose it",
        )
        assertFalse("--api-key" in recordedArgs())
    }

    // ---- testng-results.xml discovery --------------------------------------
    //
    // Getting this path wrong is silent: ObservoPushTask omits a report that
    // isn't there, the TestNG `groups` join disappears (Allure never carries
    // it), every case resolves untracked, and the push writes back nothing on a
    // green build. A guessed literal was wrong twice over — TestNG writes into
    // the task's HTML report dir, not build/test-results/, and the task is not
    // necessarily named `test`.

    @Test
    fun `testngResults is derived from the TestNG task's report dir, not a guessed path`() {
        // Mirrors the pilot client exactly: the `java` plugin contributes a
        // default JUnit `test` task, and the real suite is a separately
        // registered TestNG task with a non-default name. Keying on "the only
        // Test task" would infer nothing here — there are two.
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("ai.observo")
            }
            tasks.register<Test>("executeConfiguratedTest") {
                useTestNG { useDefaultListeners(true) }
            }
            observo {
                project = "PD"
                cliPath = file("fake-observo")
            }
            """.trimIndent()
        )
        // Where TestNG really writes it: TestNGOptions.outputDirectory is
        // convention-mapped to the task's HTML report directory.
        val results = File(projectDir, "build/reports/tests/executeConfiguratedTest/testng-results.xml")
        results.parentFile.mkdirs()
        results.writeText("<testng-results/>")

        run("observoPush")

        val args = recordedArgs()
        val i = args.indexOf("--testng-results")
        assertTrue(i >= 0, "expected --testng-results to be passed; got $args")
        assertEquals(
            results.canonicalPath,
            File(args[i + 1]).canonicalPath,
            "must point at the TestNG task's report dir",
        )
    }

    @Test
    fun `a JUnit-only project gets no testng-results flag and no warning about it`() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("ai.observo")
            }
            observo {
                project = "PD"
                cliPath = file("fake-observo")
            }
            """.trimIndent()
        )

        val result = run("observoPush")

        assertFalse("--testng-results" in recordedArgs())
        assertFalse(
            result.output.contains("no testng-results.xml"),
            "a JUnit project has no TestNG report by definition — warning about it is noise:\n${result.output}",
        )
    }

    @Test
    fun `a TestNG suite whose results file is missing warns instead of silently dropping it`() {
        // The real trap: useDefaultListeners defaults to FALSE in Gradle, so a
        // TestNG suite can produce no testng-results.xml at all. Silently
        // omitting the flag turns that into a zero-link push on a green build.
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("ai.observo")
            }
            tasks.register<Test>("executeConfiguratedTest") {
                useTestNG()
            }
            observo {
                project = "PD"
                cliPath = file("fake-observo")
            }
            """.trimIndent()
        )

        val result = run("observoPush")

        assertFalse("--testng-results" in recordedArgs())
        assertTrue(
            result.output.contains("no testng-results.xml"),
            "the drop must be announced; output was:\n${result.output}",
        )
    }

    @Test
    fun `test is finalized by observoPush by default`() {
        buildFile.writeText(buildScript("""project = "PD"""", extraPlugins = """id("java")"""))

        // --dry-run prints the task graph without executing it, which is enough
        // to prove the finalizer edge exists without needing real tests.
        val result = run("test", "--dry-run")

        assertTrue(
            result.output.contains(":observoPush"),
            "observoPush should be wired as a test finalizer; graph was:\n${result.output}",
        )
    }

    @Test
    fun `autoPush false removes the finalizer`() {
        buildFile.writeText(
            buildScript(
                """
                project = "PD"
                autoPush = false
                """.trimIndent(),
                extraPlugins = """id("java")""",
            )
        )

        val result = run("test", "--dry-run")

        assertFalse(
            result.output.contains(":observoPush"),
            "autoPush = false must unwire the finalizer; graph was:\n${result.output}",
        )
    }

    @Test
    fun `a failing push warns but leaves the build green by default`() {
        // Writeback is observability, not a gate: Observo being unreachable must
        // not turn a passing test run red.
        writeFakeCli(exitCode = 1)
        buildFile.writeText(buildScript("""project = "PD""""))

        val result = run("observoPush")

        assertEquals(TaskOutcome.SUCCESS, result.task(":observoPush")?.outcome)
        assertTrue(
            result.output.contains("pushFailsBuild"),
            "the warning must point at the knob that makes this fatal; output was:\n${result.output}",
        )
    }

    @Test
    fun `pushFailsBuild true makes a failing push fatal`() {
        writeFakeCli(exitCode = 1)
        buildFile.writeText(
            buildScript(
                """
                project = "PD"
                pushFailsBuild = true
                """.trimIndent()
            )
        )

        val result = runAndFail("observoPush")

        assertEquals(TaskOutcome.FAILED, result.task(":observoPush")?.outcome)
    }

    @Test
    fun `a missing cliPath fails with a message naming the path`() {
        buildFile.writeText(
            """
            plugins { id("ai.observo") }
            observo {
                project = "PD"
                cliPath = file("nope/observo")
            }
            """.trimIndent()
        )

        val result = runAndFail("observoPush")

        assertTrue(result.output.contains("cliPath does not exist"), result.output)
    }

    private fun assertContainsFlag(args: List<String>, flag: String, value: String) {
        val i = args.indexOf(flag)
        assertTrue(i >= 0, "expected $flag in $args")
        assertEquals(value, args.getOrNull(i + 1), "wrong value for $flag in $args")
    }
}
