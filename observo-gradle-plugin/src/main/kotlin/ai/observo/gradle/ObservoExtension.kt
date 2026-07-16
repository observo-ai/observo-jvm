package ai.observo.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * `observo { ... }` configuration block.
 *
 * ```kotlin
 * observo {
 *     project = "PD"
 *     plan = "REGR-MAIN-CI"
 *     autoPush = true
 * }
 * ```
 *
 * The API key is deliberately NOT expected here in the common case — it is
 * resolved from a system property, the environment, or `keystore.properties`
 * so it never lands in a committed build file. See [apiKey].
 */
abstract class ObservoExtension {

    /**
     * Observo project short code (e.g. `PD`) or UUID. Maps to the CLI's
     * `--project`.
     */
    abstract val project: Property<String>

    /**
     * observo-cli release to run, e.g. `0.7.0` (a leading `v` is accepted).
     * Defaults to the version this plugin was built and tested against.
     *
     * The plugin downloads this version's platform binary from GitHub Releases
     * on first use and caches it under the Gradle user home. Ignored when
     * [cliPath] is set.
     */
    abstract val cliVersion: Property<String>

    /**
     * Escape hatch: use this `observo` binary instead of downloading one.
     * Set it on air-gapped runners, or to pin a locally built CLI.
     */
    abstract val cliPath: RegularFileProperty

    /**
     * Observo account API key. Resolution order, first non-empty wins:
     *
     *  1. this property,
     *  2. system property `observo.apiKey` (`-Dobservo.apiKey=…`),
     *  3. environment variable `OBSERVO_API_KEY`,
     *  4. property `observo.apiKey` in [keystoreFile].
     *
     * Whatever the source, the key is handed to the CLI through the
     * `OBSERVO_API_KEY` environment variable of the child process — never as a
     * command-line argument, which would expose it to any `ps` on the machine.
     */
    abstract val apiKey: Property<String>

    /**
     * Properties file holding `observo.apiKey`, for teams that already keep
     * credentials this way. Defaults to `keystore.properties` in the root
     * project. Absent file = silently skipped; it is the last fallback, not a
     * requirement.
     */
    abstract val keystoreFile: RegularFileProperty

    /** Observo API base URL. Defaults to the CLI's own default. */
    abstract val baseUrl: Property<String>

    /** Plan key a push creates its run from, e.g. `REGR-MAIN-CI`. */
    abstract val plan: Property<String>

    /** Existing run to push into, e.g. `RUN-42`. Takes precedence over [plan]. */
    abstract val run: Property<String>

    /**
     * Directory holding `allure-results` from the test run. Defaults to
     * `build/allure-results`. Source of both the link manifest and the
     * HTTP-traffic evidence.
     */
    abstract val resultsDir: DirectoryProperty

    /**
     * Path to `testng-results.xml` — the source of the TestNG `groups` join,
     * which an Allure report does not carry.
     *
     * Defaulted from the Test task itself when the project has exactly one and
     * it runs TestNG: TestNG writes this file into `TestNGOptions.outputDirectory`,
     * which Gradle convention-maps to the task's HTML report directory
     * (`build/reports/tests/<task>/`). Set it explicitly for a multi-Test-task
     * build, or if you override `TestNGOptions.outputDirectory`.
     *
     * TestNG only emits the file when `useTestNG { useDefaultListeners(true) }`
     * is set — Gradle leaves it off by default. Without it there is nothing to
     * push and `observoPush` warns.
     */
    abstract val testngResults: RegularFileProperty

    /**
     * When true (the default), `test` is finalized by `observoPush`, so results
     * are written back automatically after `./gradlew test` with no extra
     * pipeline step.
     */
    abstract val autoPush: Property<Boolean>

    /**
     * Whether a failed `observoPush` fails the build. Default `false`: a
     * writeback problem warns and leaves a passing test run green, because
     * writeback is observability rather than a gate.
     *
     * Turn this on when push runs as its own pipeline step and CI should notice
     * that writeback broke.
     */
    abstract val pushFailsBuild: Property<Boolean>
}
