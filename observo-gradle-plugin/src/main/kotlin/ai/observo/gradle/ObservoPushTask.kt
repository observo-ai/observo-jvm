package ai.observo.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.options.Option
import java.io.File

/**
 * `observo jvm push` — writes run results and HTTP-traffic evidence back to an
 * Observo run.
 *
 * Wired as a `test` finalizer by default, so `./gradlew test` writes back with
 * no extra pipeline step.
 */
abstract class ObservoPushTask : ObservoCliTask() {

    /** Pre-built `observo-link-manifest.json`. Alternative to [from]. */
    @get:Internal
    abstract val manifest: RegularFileProperty

    /** `allure-results` directory — manifest and HTTP evidence are read from it. */
    @get:Internal
    abstract val from: DirectoryProperty

    /** `testng-results.xml` — supplies the TestNG `groups` join the Allure report omits. */
    @get:Internal
    abstract val testngResults: RegularFileProperty

    /** Existing run key/UUID, e.g. `RUN-42`. Takes precedence over [plan]. */
    @get:Internal
    abstract val run: Property<String>

    /** Plan key/UUID to create the run from, e.g. `REGR-MAIN-CI`. */
    @get:Internal
    abstract val plan: Property<String>

    /** Exposed as `--preview`; `--dry-run` is a Gradle built-in. See [ObservoStubTask.dryRun]. */
    @get:Internal
    @get:Option(option = "preview", description = "Print what would be pushed; call no write APIs")
    abstract val dryRun: Property<Boolean>

    /**
     * Whether a failed writeback fails the build. Default `false`.
     *
     * The flagship flow is `test.finalizedBy(observoPush)`, and a finalizer
     * failure fails the whole build in Gradle. Writeback is observability, not
     * a gate — a passing suite turning red because Observo was briefly
     * unreachable is how a team learns to rip the plugin out. So the default
     * warns loudly and leaves the build green.
     *
     * Set `observo { pushFailsBuild = true }` when push runs as its own
     * pipeline step and you want CI to notice a broken writeback.
     *
     * (This is a single knob rather than "tolerant as a finalizer, strict when
     * invoked directly" because both paths run the *same task instance* — the
     * task cannot tell which way it was reached.)
     */
    @get:Internal
    abstract val failOnError: Property<Boolean>

    override val tolerateFailure: Boolean
        get() = !failOnError.getOrElse(false)

    override fun cliArgs(): List<String> = buildList {
        add("push")

        // A report path is passed only when it exists: a suite legitimately has
        // Allure without TestNG XML (or the reverse), and handing the CLI a path
        // that isn't there would fail a run that had everything it needed.
        //
        // But dropping one SILENTLY is how a misconfiguration becomes invisible
        // data loss: without --testng-results a TestNG `groups` join vanishes
        // (Allure never surfaces it), every case resolves untracked, and the push
        // writes back nothing while exiting 0. So each drop is announced.
        flag("--manifest", existing(manifest.orNull?.asFile, "manifest") { it.isFile })
        flag("--from", existing(from.orNull?.asFile, "Allure results directory") { it.isDirectory })
        flag("--testng-results", existing(testngResults.orNull?.asFile, "testng-results.xml") { it.isFile })

        // --run and --plan are mutually exclusive in the CLI ("--plan and --run
        // are mutually exclusive", jvm_push.go resolvePushRun). Emitting both
        // whenever both are configured would abort the push, so honour the
        // documented precedence here: an explicit run wins over a plan.
        val runKey = run.orNull?.takeIf { it.isNotBlank() }
        if (runKey != null) {
            flag("--run", runKey)
        } else {
            flag("--plan", plan.orNull)
        }

        flag("--project", observoProject.orNull)
        switch("--dry-run", dryRun.getOrElse(false))
    }

    /**
     * Returns [file]'s path when [present] holds, else warns and returns null so
     * the flag is omitted.
     *
     * The warning is the point: a configured-but-absent path is either a typo or
     * a report that was never produced (e.g. TestNG without
     * `useTestNG { useDefaultListeners(true) }`), and both degrade the push into
     * a green no-op that reports nothing.
     */
    private fun existing(file: File?, label: String, present: (File) -> Boolean): String? {
        if (file == null) return null
        if (present(file)) return file.absolutePath
        logger.warn(
            "observo: no $label at $file — pushing without it. " +
                "Cases joined only through that report will not be linked."
        )
        return null
    }
}
